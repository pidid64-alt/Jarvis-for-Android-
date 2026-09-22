package kz.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import android.os.PowerManager

/**
 * Сервис специальных возможностей «Джарвис — автоотправка».
 *
 * Android не даёт обычному приложению жать кнопки в чужих окнах. Этот сервис:
 *  1. жмёт «Отправить» в WhatsApp, когда сам Джарвис начал отправку
 *     (см. AutoSend.arm);
 *  2. выполняет системные жесты «Назад» / «Домой» / недавние — команда
 *     «закрой это» (см. Hands).
 *
 * Включается один раз: Настройки → Спец. возможности → «Джарвис —
 * автоотправка».
 */
class AutoSendService : AccessibilityService() {
    private var lastScreenReview = 0L
    private var lastScreenText = ""
    private var lastDiscordTap = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        try {
            if (AutoSend.active() && pkg == AutoSend.targetPkg()) tryClickSend()
            if (DiscordCall.active() && pkg == DiscordCall.targetPkg()) tryTapDiscordCall()
            if (Prefs.screenWatch(this) && pkg != packageName) maybeDiscussScreen()
        } catch (e: Throwable) {
            // не роняем процесс из-за одного странного окна или ответа сети
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Hands.bind(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Hands.unbind(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Hands.unbind(this)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /**
     * Опциональный режим: не снимает скриншоты, а читает только доступный текст UI.
     *
     * Период задаёт владелец — голосом («смотри на экран каждые 2 минуты») или
     * в настройках; раньше он был зашит в коде и равнялся пяти минутам.
     */
    private fun maybeDiscussScreen() {
        val now = SystemClock.elapsedRealtime()
        val everyMin = Prefs.screenWatchMin(this)
        if (now - lastScreenReview < everyMin * 60_000L) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (!pm.isInteractive || !Llm.ready(this)) return
        val root = rootInActiveWindow ?: return
        val text = collectVisibleText(root).trim().replace(Regex("\\s+"), " ").take(1800)
        if (text.length < 40 || text == lastScreenText) return
        lastScreenReview = now
        lastScreenText = text
        Thread {
            val answer = try {
                Llm.complete(this, "Ты наблюдаешь экран владельца. Если видишь понятную тему, которую уместно обсудить, ответь одной короткой фразой по-русски. Если повода нет, верни ровно NO.", "Текст экрана: $text", maxTokens = 80)
            } catch (_: Throwable) { "NO" }
            val clean = answer.trim()
            if (clean.isNotEmpty() && !clean.equals("NO", true) && clean.length < 220) {
                try { Speaker.once(applicationContext, clean) } catch (_: Throwable) { }
            }
        }.start()
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo): String {
        val out = StringBuilder()
        fun walk(n: AccessibilityNodeInfo) {
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append(' ') }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append(' ') }
            for (i in 0 until n.childCount) {
                try { n.getChild(i)?.let { ch -> walk(ch); ch.recycle() } } catch (_: Throwable) { }
                if (out.length > 2200) return
            }
        }
        walk(node)
        return out.toString()
    }

    /**
     * Discord: после «позвони маме в дискорде» приложение открылось — ищем
     * кнопку звонка и жмём её. Кнопки Discord подписаны для спец. возможностей
     * («Start Voice Call» / «Начать голосовой вызов»), поэтому ищем по подписи
     * и по view-id, а не по картинке.
     */
    private fun tryTapDiscordCall() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiscordTap < 1_200L) return
        val root = rootInActiveWindow ?: return
        val video = DiscordCall.wantVideo()
        val node = findCallButton(root, video)
        // корневой узел не перерабатываем: им владеет сервис (как и в остальных местах)
        if (node == null) return
        lastDiscordTap = now
        val clicked = node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        try { node.recycle() } catch (e: Exception) { }
        if (clicked) DiscordCall.markClicked()
    }

    private fun findCallButton(root: AccessibilityNodeInfo, video: Boolean): AccessibilityNodeInfo? {
        val own = if (video) listOf("видеовызов", "видео звонок", "видеозвонок", "video call", "start video call")
        else listOf("голосовой вызов", "начать голосовой", "голосовой звонок", "voice call", "start voice call")
        val other = if (video) listOf("голосовой вызов", "voice call", "start voice call")
        else listOf("видеовызов", "video call", "start video call")
        val common = listOf("позвонить", "звонок", "call", "join", "подключиться", "войти")

        // сначала «своя» кнопка (голос/видео), потом общая, чужая — в последнюю очередь
        for (group in listOf(own, common)) {
            val found = search(root) { desc, text, id ->
                group.any { desc.contains(it) || text.contains(it) || id.contains(it.replace(" ", "_")) }
            }
            if (found != null && !isOtherKind(found, other)) return found
        }
        return null
    }

    /** Не перепутать голосовой звонок с видео: «чужая» кнопка не подходит. */
    private fun isOtherKind(node: AccessibilityNodeInfo, other: List<String>): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
        val text = node.text?.toString()?.lowercase()?.trim().orEmpty()
        return other.any { desc.contains(it) || text.contains(it) }
    }

    /** Обход дерева: первый кликабельный узел, подходящий под условие. */
    private fun search(
        node: AccessibilityNodeInfo,
        hit: (desc: String, text: String, id: String) -> Boolean
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
        val text = node.text?.toString()?.lowercase()?.trim().orEmpty()
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (node.isClickable && hit(desc, text, id)) return node
        for (i in 0 until node.childCount) {
            val ch = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            val found = search(ch, hit)
            if (found != null) {
                if (found !== ch) ch.recycle()
                return found
            }
            ch.recycle()
        }
        return null
    }

    /** Ищет кнопку отправки и жмёт её, если текст уже вставлен в поле ввода. */
    private fun tryClickSend() {
        val root = rootInActiveWindow ?: return
        val want = AutoSend.expectedText() ?: return
        if (want.isNotBlank() && !hasComposerText(root, want)) return  // текст ещё не вставлен
        val target = findSendButton(root) ?: return
        if (target.isEnabled && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            AutoSend.markClicked()
        }
    }

    /** Есть ли уже набранный текст в поле ввода (EditText). */
    private fun hasComposerText(root: AccessibilityNodeInfo, want: String): Boolean {
        var found = false
        fun walk(n: AccessibilityNodeInfo) {
            if (found) return
            val cls = n.className?.toString().orEmpty()
            if (cls.endsWith("EditText")) {
                val t = n.text?.toString()?.trim().orEmpty()
                if (t == want || (want.length >= 6 && t.contains(want))) found = true
            }
            for (i in 0 until n.childCount) {
                val ch = try {
                    n.getChild(i)
                } catch (e: Exception) {
                    null
                }
                if (ch != null) {
                    walk(ch)
                    ch.recycle()
                }
            }
        }
        try {
            walk(root)
        } catch (e: Throwable) { }
        return found
    }

    /** Находит кликабельную кнопку отправки (по view-id или подписи). */
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1) точный view-id кнопки отправки WhatsApp / WhatsApp Business
        for (pkg in ChatMedia.WA_PACKAGES.map { it.first }) {
            val byId = try {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
            } catch (e: Exception) {
                null
            }
            if (!byId.isNullOrEmpty()) return byId.first()
        }
        // 2) обход: кликабельная кнопка с подписью «send»/«отправить» или id со «send»
        val names = setOf("send", "отправить", "отправ", "snd", "done", "послать")
        var best: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (best != null) return
            if (n.isClickable) {
                val desc = n.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
                val vid = n.viewIdResourceName?.lowercase().orEmpty()
                val txt = n.text?.toString()?.lowercase()?.trim().orEmpty()
                if (desc in names || txt in names || vid.contains(":id/send") || vid.contains(":id/single_btn")) {
                    best = n
                    return
                }
            }
            for (i in 0 until n.childCount) {
                val ch = try {
                    n.getChild(i)
                } catch (e: Exception) {
                    null
                }
                if (ch != null) {
                    walk(ch)
                    ch.recycle()
                }
            }
        }
        try {
            walk(root)
        } catch (e: Throwable) { }
        return best
    }
}
