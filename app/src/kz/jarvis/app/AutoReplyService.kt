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
        /** Ключ в extras, под которым MessagingStyle хранит сообщения (см. AOSP). */
        private const val EXTRA_MESSAGES = "android.messages"
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

        /** Есть ли у самого Джарвиса право показывать свои уведомления. */
        private fun ownNotifsAllowed(c: Context): Boolean =
            Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        /** Выдан ли доступ к телефонной книге (по ней отличаем своих от чужих). */
        fun contactsGranted(c: Context): Boolean = try {
            c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

        /**
         * Честная карточка «почему автоответчик не работает / что готово».
         * Первые строки — жёсткие блокеры, дальше — предупреждения. Возвращает
         * текст с переносами строк; используется голосовой командой «проверь
         * автоответчик» и кнопкой в настройках.
         */
        fun health(c: Context): String = buildString {
            if (!Prefs.autoReply(c)) {
                append("Автоответчик выключен, сэр. Включите его: «включи автоответ» или переключатель в настройках.")
                return@buildString
            }
            append("Автоответчик включён.\n")
            fun mark(ok: Boolean, okText: String, badText: String) {
                if (ok) append("✔ ").append(okText).append("\n")
                else append("✖ ").append(badText).append("\n")
            }
            mark(
                isGranted(c),
                "Доступ к уведомлениям выдан.",
                "Нет «Доступа к уведомлениям» — система не передаёт Джарвису сообщения вообще. " +
                    "Настройки Джарвиса → Автоответчик → кнопка «Доступ к уведомлениям» и включите «Джарвис — автоответчик» в системном списке."
            )
            mark(
                Llm.ready(c),
                "Провайдер ИИ настроен.",
                "Нет настроенного провайдера ИИ (ключа) — некому придумывать ответы. Настройки Джарвиса → Провайдер ИИ."
            )
            mark(
                contactsGranted(c),
                "Доступ к контактам есть.",
                "Нет доступа к контактам: Джарвис не отличает своих от чужих и пропускает чаты, " +
                    "названные именами (отвечает только чатам-номерам). Дайте доступ в настройках Джарвиса."
            )
            mark(
                ownNotifsAllowed(c),
                "Уведомления Джарвиса видны.",
                "Джарвису запрещены свои уведомления — вы не увидите, что он ответил или почему не ответил. " +
                    "Дайте «Уведомления» в системных настройках приложения."
            )
            if (Prefs.autoReplyScreenOff(c)) {
                append("Экран: отвечаю только когда он погашен — при включённом экране сообщения пропускаю (вы же читаете чат сами).\n")
            } else {
                append("Экран: отвечаю и при включённом экране.\n")
            }
            val geoOk = c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (Prefs.autoReplyLoc(c) && !geoOk) {
                append("⚠ Геолокация включена, но разрешения нет — на «где ты» отвечу без адреса.\n")
            }
            append("Проверка: погасите экран и попросите кого-нибудь написать вам (или напишите со второго номера) — " +
                "в чате появится ответ, а Джарвис пришлёт уведомление «ответил…».")
        }

        /** Одна подсказка-предупреждение в день (не чаще). */
        fun hintOnce(c: Context, text: String) {
            if (Prefs.autoHintPosted(c)) return
            Prefs.markAutoHintPosted(c)
            Notify.autoReplyHint(c, text)
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

    /** Система подключила слушателя — значит, «Доступ к уведомлениям» реально выдан. */
    override fun onListenerConnected() {
        AutoLog.add(this, "✔ Служба подключена системой — уведомления мессенджеров будут приходить сюда.")
        super.onListenerConnected()
    }

    /** Пользователь (или система) отключил доступ к уведомлениям. */
    override fun onListenerDisconnected() {
        AutoLog.add(this, "✖ Система отключила слушателя. " +
            "Проверьте «Доступ к уведомлениям» в системных настройках.")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            onIncoming(sbn)
        } catch (e: Throwable) {
            // служба не должна падать из-за одного странного уведомления
        }
    }

    private fun onIncoming(sbn: StatusBarNotification) {
        // смотрим только уведомления известных мессенджеров
        val appLabel = AutoReplyLogic.messengerLabel(sbn.packageName) ?: return
        val n = sbn.notification ?: return
        if (sbn.isOngoing) return
        val extras = n.extras ?: return

        // вытаскиваем «кто» и «что написал» — пробуем все форматы, которые
        // используют мессенджеры (обычный текст, BigText, MessagingStyle, строки)
        val title = extractTitle(n)
        val text = extractText(n)
        if (title.isEmpty() || text.isEmpty()) {
            AutoLog.add(
                this,
                "Пропустил уведомление $appLabel: не удалось прочитать отправителя или текст " +
                    "(title=${title.take(40)}, text=${text.take(40)})"
            )
            return
        }
        val actionsCount = n.actions?.size ?: 0

        if (!Prefs.autoReply(this)) {
            AutoLog.add(
                this,
                "Пропустил «$title» ($appLabel): автоответчик выключен. Скажите «включи автоответ»."
            )
            return
        }

        AutoLog.add(this, "Пришло сообщение от «$title» ($appLabel): «${text.take(90)}» (действий: $actionsCount)")

        if (AutoReplyLogic.shouldSkip(title, text)) {
            AutoLog.add(this, "Пропустил «$title»: служебное сообщение (шифрование/звонок/прочее).")
            return
        }
        if (!AutoReplyLogic.isKnownPerson(title, if (contactsGranted(this)) contactNames() else emptyList())) {
            // группа, рассылка или незнакомый чат — автоответа нет.
            // Но если нет доступа к контактам, Джарвис не может отличить
            // «своего» от чужого вообще — подскажем, что нужно выдать доступ.
            AutoLog.add(this, "Пропустил «$title»: группа/рассылка/незнакомый чат — не отвечаю.")
            if (!contactsGranted(this)) {
                hintOnce(
                    this,
                    "Пропустил сообщение из «$title»: нет доступа к контактам, и я не могу понять, свой это или чужой. " +
                        "Дайте доступ: Настройки Джарвиса → кнопка «Контакты»."
                )
            }
            return
        }

        // владелец сейчас смотрит в экран — он сам ответит (настройка)
        if (Prefs.autoReplyScreenOff(this) && screenOn()) {
            // режим «только при выключенном экране»: пропускаем молча, но раз в день
            // напомним, почему ничего не происходит (иначе выглядит как поломка)
            AutoLog.add(this, "Пропустил «$title»: экран включён, а включён режим «только при выключенном экране».")
            hintOnce(
                this,
                "Сообщение из «$title» пропустил: включён режим «отвечать, только когда экран выключен», а экран сейчас горит. " +
                    "Погасите экран и попросите написать снова — или выключите эту опцию в Настройках Джарвиса → Автоответчик."
            )
            return
        }

        val now = System.currentTimeMillis()
        if (now - sbn.postTime > 60_000) {
            AutoLog.add(this, "Пропустил «$title»: уведомление старше минуты.")
            return
        }

        // один и тот же текст несколько раз подряд — это дубль уведомления
        val dk = "$appLabel|$title|${AutoReplyLogic.norm(text)}"
        val was = seen[dk]
        if (was != null && now - was < 15_000) {
            AutoLog.add(this, "Пропустил «$title»: дубль того же сообщения.")
            return
        }
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
            } else {
                AutoLog.add(this, "«$title»: в уведомлении нет действия «Ответить» — ответить не смогу.")
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

    /**
     * Кто написал: заголовок уведомления, а если его нет — имя из первого
     * сообщения MessagingStyle.
     */
    private fun extractTitle(n: Notification): String {
        n.extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        try {
            val msgs = n.extras.getParcelableArray(EXTRA_MESSAGES)
            if (msgs != null) {
                val m = msgs.filterIsInstance<Notification.MessagingStyle.Message>()
                    .lastOrNull()
                if (m?.sender?.isNotBlank() == true) return m.sender.toString().trim()
            }
        } catch (e: Throwable) { }
        return ""
    }

    /**
     * Текст сообщения: обычный текст, иначе строки-массив, иначе BigText,
     * иначе сообщения MessagingStyle (последнее как текущее, остальные как
     * контекст в конце). Пусто — прочитать не удалось.
     */
    private fun extractText(n: Notification): String {
        val extras = n.extras
        extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        lines?.joinToString("\n") { it.toString().trim() }
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        // последний способ — MessagingStyle («android.messages»)
        return try {
            val msgs = extras.getParcelableArray(EXTRA_MESSAGES)
            if (msgs == null) return ""
            val style = msgs.filterIsInstance<Notification.MessagingStyle.Message>()
            if (style.isEmpty()) return ""
            style.takeLast(6).joinToString("\n") { m ->
                val who = m.sender?.toString()?.trim().orEmpty()
                val t = m.text?.toString()?.trim().orEmpty()
                if (who.isEmpty()) t else "$who: $t"
            }.trim().takeIf { it.isNotEmpty() }.orEmpty()
        } catch (e: Throwable) {
            ""
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
            AutoLog.add(this, "«${chat.title}»: нет настроенного ИИ — ответить не могу.")
            Notify.autoReplyNoModel(this, chat.title)
            return
        }

        val nowText = SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(now))
        val prompt = AutoReplyLogic.decisionPrompt(
            chat.title, snapshot,
            Prefs.autoReplyRules(this), location, nowText
        )

        AutoLog.add(this, "«${chat.title}»: думаю над ответом (сообщений: ${snapshot.size})")
        val reply: String
        try {
            val raw = Llm.complete(this, AUTO_SYSTEM, prompt, maxTokens = 260)
            reply = AutoReplyLogic.cleanReply(AutoReplyLogic.parseReply(raw))
        } catch (e: Throwable) {
            AutoLog.add(this, "«${chat.title}»: ошибка модели: ${e.message?.take(120) ?: "неизвестно"}")
            Notify.autoReplyError(this, chat.title, e.message)
            return
        }

        if (reply.isEmpty()) {
            // модель решила, что отвечать не нужно
            AutoLog.add(this, "«${chat.title}»: модель решила, что ответ не нужен.")
            if (Prefs.autoReplyVoice(this) && now - lastVoiceNote > 60_000) {
                lastVoiceNote = now
                speak(listOfNotNull(chat.title, last).joinToString(": ").take(160))
            }
            return
        }

        val allowed = synchronized(repliesAt) {
            AutoReplyLogic.allowed(repliesAt, System.currentTimeMillis())
        }
        if (!allowed) {
            AutoLog.add(this, "«${chat.title}»: лимит частоты — пропустил.")
            return
        }

        val sent = sendReply(actions, reply)
        if (sent) {
            synchronized(repliesAt) { repliesAt.add(System.currentTimeMillis()) }
            chat.lastReplyAt = System.currentTimeMillis()
            AutoLog.add(this, "✔ Отправил «${chat.title}»: $reply")
            Notify.autoReplySent(this, chat.appLabel, chat.title, reply)
            if (Prefs.autoReplyVoice(this)) {
                speak("Ответил ${chat.title}: $reply".take(200))
            }
        } else {
            AutoLog.add(this, "✖ «${chat.title}»: действие «Ответить» не сработало — ответьте сами.")
            Notify.autoReplyNoAction(this, chat.title)
        }
    }

    /** Отправка через системное действие «Ответить» (как с часов). */
    private fun sendReply(actions: Array<Notification.Action>?, reply: String): Boolean {
        if (actions.isNullOrEmpty()) return false
        var lastErr: String? = null
        for (a in actions) {
            val ris = a.remoteInputs ?: continue
            if (ris.isEmpty()) continue
            try {
                // кладём текст во все поля ввода действия и отправляем через
                // PendingIntent самого уведомления (канонический способ)
                val intent = Intent()
                val results = Bundle()
                for (ri in ris) results.putCharSequence(ri.resultKey, reply)
                RemoteInput.addResultsToIntent(ris, intent, results)
                a.actionIntent.send(this, 0, intent)
                return true
            } catch (e: Exception) {
                lastErr = e.message
                // пробуем следующее действие
            }
        }
        AutoLog.add(this, "Отправка не удалась: ${lastErr?.take(120) ?: "действий с полем ввода нет"}")
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
