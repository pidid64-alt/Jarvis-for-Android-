package kz.jarvis.app

/**
 * Общая история диалога: ею пользуются и окно чата, и фоновая служба,
 * поэтому контекст разговора не теряется, когда приложение закрыто.
 */
object Conversation {

    private const val MAX = 24
    private val items = ArrayList<Pair<Boolean, String>>()

    var lastReply: String = ""
        private set

    fun history(): List<Pair<Boolean, String>> = synchronized(items) { items.toList() }

    /** @param fromUser true — фраза пользователя, false — ответ Джарвиса. */
    fun add(text: String, fromUser: Boolean) {
        if (text.isBlank()) return
        synchronized(items) {
            items.add(fromUser to text)
            while (items.size > MAX) items.removeAt(0)
            if (!fromUser) lastReply = text
        }
    }

    fun clear() = synchronized(items) {
        items.clear()
        lastReply = ""
    }
}
