package kz.jarvis.app

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale

/**
 * Автоответчик в мессенджерах.
 *
 * Что делает: пока включён «автоответ», Джарвис следит за уведомлениями
 * WhatsApp / Telegram / Viber и, когда приходит сообщение от личного
 * контакта (из телефонной книги или по номеру), решает за владельца:
 *  1. собирает контекст (переписка, личные правила, геолокация — если
 *     спрашивают «где ты»);
 *  2. отдаёт это языковой модели — она решает, отвечать ли вообще и что
 *     написать от первого лица, как сам владелец;
 *  3. отправляет ответ через системное действие «Ответить» из уведомления
 *     (тот же механизм, что у часов) и показывает, что сделал.
 *
 * Безопасные границы:
 *  • отвечаем только личным контактам — группам и рассылкам нет;
 *  • по умолчанию — только когда экран выключен (владелец не читает чат сам);
 *  • лимиты частоты: не больше 4 автоответов в минуту;
 *  • мастер-тумблер в настройках по умолчанию ВЫКЛЮЧЕН — включается явно.
 *
 * Честно об ограничениях: нужен «Доступ к уведомлениям» в системных
 * настройках и действие «Ответить» в уведомлении мессенджера (есть у
 * WhatsApp/Telegram/Viber). Если мессенджер его не прислал — Джарвис
 * сообщит, что не смог ответить сам.
 */
class AutoReplyService : NotificationListenerService() {

    companion object {
        private const val MAX_CHATS = 24
        private const val MIN_REPLY_INTERVAL_MS = 15_000   // один чат не чаще раза в 15 сек
        private const val AUTO_SYSTEM =
            "Ты — Джарвис, личный ассистент владельца телефона. " +
                "Ты составляешь ответы в мессенджерах от его имени. " +
                "Следуй инструкции пользователя точно и отвечай только одним JSON."

        /** Выдал ли пользователь нашей службе «доступ к уведомлениям». */
        fun isGranted(c: Context): Boolean = try {
            val cn = ComponentName(c, AutoReplyService::class.java)
            // системный список разрешённых слушателей (без reflection)
            val raw = android.provider.Settings.Secure.getString(
                c.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            raw.split(':').any {
                it == cn.flattenToString() || it == cn.flattenToShortString()
            }
        } catch (e: Exception) {
            false
        }
    }

    private class Chat(val key: String, val title: String, val appLabel: String) {
        /** Тексты сообщений (старые сверху) и их время. */
        val msgs = ArrayList<Pair<String, Long>>()
        var latestActions: Array<Notification.Action>? = null
        var busy = false
        var rerun = false
        var lastReplyAt = 0L
    }

    private val chats = HashMap<String, Chat>()
    private val repliesAt = ArrayList<Long>()          // все автоответы (для лимита)
    private val seen = HashMap<String, Long>()          // защита от дублей уведомлений
    private var lastVoiceNote = 0L

    // --------------------------------------------------------- вход из системы

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            onIncoming(sbn)
        } catch (e: Throwable) {
            // служба не должна падать из-за одного странного уведомления
        }
    }

    private fun onIncoming(sbn: StatusBarNotification) {
        if (!Prefs.autoReply(this)) return
        val appLabel = AutoReplyLogic.messengerLabel(sbn.packageName) ?: return
        val n = sbn.notification ?: return
        if (sbn.isOngoing) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            text = lines?.joinToString("\n") { it.toString().trim() }?.trim().orEmpty()
        }
        if (text.isEmpty()) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?.toString()?.trim().orEmpty()
        }
        if (title.isEmpty() || text.isEmpty()) return
        if (AutoReplyLogic.shouldSkip(title, text)) return
        if (!AutoReplyLogic.isKnownPerson(title, contactNames())) {
            // группа, рассылка или незнакомый чат — автоответа нет
            return
        }

        // владелец сейчас смотрит в экран — он сам ответит (настройка)
        if (Prefs.autoReplyScreenOff(this) && screenOn()) return

        val now = System.currentTimeMillis()
        if (now - sbn.postTime > 60_000) return   // очень старое уведомление

        // один и тот же текст несколько раз подряд — это дубль уведомления
        val dk = "$appLabel|$title|${AutoReplyLogic.norm(text)}"
        val was = seen[dk]
        if (was != null && now - was < 15_000) return
        pruneSeen(now)
        seen[dk] = now

        synchronized(chats) {
            pruneChats(now)
            val key = "$appLabel|$title"
            val chat = chats.getOrPut(key) { Chat(key, title, appLabel) }
            chat.msgs.add(text to now)
            while (chat.msgs.size > 12) chat.msgs.removeAt(0)

            // действия «Ответить» берём из самого свежего уведомления чата
            val replyActions = n.actions
                ?.filter { it.remoteInputs?.isNotEmpty() == true }
            if (!replyActions.isNullOrEmpty()) {
                chat.latestActions = replyActions.toTypedArray()
            }

            if (!chat.busy) {
                chat.busy = true
                chat.rerun = false
                runWorker(chat)
            } else {
                chat.rerun = true   // пока думаем — пришли ещё сообщения; учтём
            }
        }
    }

    private fun pruneSeen(now: Long) {
        if (seen.size < 100) return
        val it = seen.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > 900_000) it.remove()
    }

    private fun pruneChats(now: Long) {
        if (chats.size < MAX_CHATS) return
        val itr = chats.entries.iterator()
        while (itr.hasNext()) {
            val e = itr.next()
            val last = e.value.msgs.lastOrNull()?.second ?: 0L
            if (now - last > 86_400_000L) {   // сутки без сообщений — чат можно забыть
                itr.remove()
                return
            }
        }
    }

    // ------------------------------------------------------------ фоновый поток

    /** Крутит цикл обработки чата: пока приходят новые сообщения — переспрашиваем. */
    private fun runWorker(chat: Chat) {
        Thread {
            try {
                var again = true
                while (again) {
                    try {
                        processOnce(chat)
                    } catch (t: Throwable) {
                        // сеть/модель упали — не роняем процесс и не спамим
                    }
                    synchronized(chats) {
                        again = chat.rerun && Prefs.autoReply(this@AutoReplyService)
                        chat.rerun = false
                    }
                }
            } finally {
                synchronized(chats) { chat.busy = false }
            }
        }.start()
    }

    private fun processOnce(chat: Chat) {
        val now = System.currentTimeMillis()

        // защита от «диалога Джарвиса с самим собой»: один чат не чаще раза в 15 сек
        if (now - chat.lastReplyAt < MIN_REPLY_INTERVAL_MS) return

        val snapshot: List<String>
        val actions: Array<Notification.Action>?
        synchronized(chats) {
            snapshot = chat.msgs.map { it.first }.toList()
            actions = chat.latestActions
        }
        if (snapshot.isEmpty()) return

        val last = snapshot.last()

        // геолокацию подключаем, только когда спрашивают «где я»
        var location: String? = null
        if (Prefs.autoReplyLoc(this) && AutoReplyLogic.asksLocation(last)) {
            try {
                location = Geo.contextText(applicationContext)
            } catch (e: Throwable) { location = null }
        }

        if (!Llm.ready(this)) {
            Notify.autoReplyNoModel(this, chat.title)
            return
        }

        val nowText = SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(now))
        val prompt = AutoReplyLogic.decisionPrompt(
            chat.title, snapshot,
            Prefs.autoReplyRules(this), location, nowText
        )

        val reply: String
        try {
            val raw = Llm.complete(this, AUTO_SYSTEM, prompt, maxTokens = 260)
            reply = AutoReplyLogic.cleanReply(AutoReplyLogic.parseReply(raw))
        } catch (e: Throwable) {
            Notify.autoReplyError(this, chat.title, e.message)
            return
        }

        if (reply.isEmpty()) {
            // модель решила, что отвечать не нужно
            if (Prefs.autoReplyVoice(this) && now - lastVoiceNote > 60_000) {
                lastVoiceNote = now
                speak(listOfNotNull(chat.title, last).joinToString(": ").take(160))
            }
            return
        }

        val allowed = synchronized(repliesAt) {
            AutoReplyLogic.allowed(repliesAt, System.currentTimeMillis())
        }
        if (!allowed) return   // лимит частоты — молча пропускаем

        val sent = sendReply(actions, reply)
        if (sent) {
            synchronized(repliesAt) { repliesAt.add(System.currentTimeMillis()) }
            chat.lastReplyAt = System.currentTimeMillis()
            Notify.autoReplySent(this, chat.appLabel, chat.title, reply)
            if (Prefs.autoReplyVoice(this)) {
                speak("Ответил ${chat.title}: $reply".take(200))
            }
        } else {
            Notify.autoReplyNoAction(this, chat.title)
        }
    }

    /** Отправка через системное действие «Ответить» (как с часов). */
    private fun sendReply(actions: Array<Notification.Action>?, reply: String): Boolean {
        if (actions.isNullOrEmpty()) return false
        for (a in actions) {
            val ris = a.remoteInputs ?: continue
            if (ris.isEmpty()) continue
            try {
                val intent = Intent()
                val results = Bundle()
                results.putCharSequence(ris[0].resultKey, reply)
                RemoteInput.addResultsToIntent(ris, intent, results)
                a.actionIntent.send(this, 0, intent)
                return true
            } catch (e: Exception) {
                // пробуем следующее действие
            }
        }
        return false
    }

    // ------------------------------------------------------------- окружение

    private fun screenOn(): Boolean = try {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= 20) pm.isInteractive else pm.isScreenOn
    } catch (e: Exception) {
        false
    }

    /** Имена из телефонной книги (нужно разрешение «Контакты»). */
    private fun contactNames(): List<String> {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        return try {
            val names = ArrayList<String>()
            val cur = contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            )
            cur?.use { c ->
                val col = c.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(col)
                    if (!name.isNullOrBlank()) names.add(name)
                }
            }
            names.take(500)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun speak(text: String) {
        try {
            Speaker.once(applicationContext, text)
        } catch (e: Exception) { }
    }
}
