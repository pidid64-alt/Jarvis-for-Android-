package kz.jarvis.app

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Почта» приоритетных контактов: то, что Джарвис НЕ имеет права решать сам.
 *
 * Поток (ровно по правилам владельца):
 *   1. пришло сообщение от приоритетного контакта → [register] + уведомление
 *      «Мама пишет: «…» — Что ответить Маме?»;
 *   2. владелец отвечает текстом, голосом или прямо из уведомления → [command]
 *      (из CommandEngine) или [answer] (из уведомления);
 *   3. Джарвис формулирует ответ (моделью, а нет модели — лёгкой обработкой)
 *      и показывает итог: «Отправляю Маме: «…» — ок?»;
 *   4. отправка — только после явного «да/ок/отправляй» ([send]).
 *
 * Отдельный режим [Kind.DRAFT] — для остальных контактов: Джарвис лишь
 * предлагает черновик (его придумал автоответчик) и всё равно спрашивает
 * разрешения. Без подтверждения не уходит ни одно сообщение: исключение —
 * режим шаблонов, и то первая отправка в каждой теме подтверждается.
 */
object PriorityInbox {

    /** Приоритетный контакт или обычный чат (для него только черновик). */
    enum class Kind { PRIORITY, DRAFT }

    class Request(
        val id: Int,
        val chatKey: String,
        val title: String,
        val appLabel: String,
        var text: String,
        val kind: Kind,
        var category: PriorityLogic.Category,
        val receivedAt: Long
    ) {
        /** Ждём ответа владельца или подтверждения черновика. */
        var stage: PriorityLogic.Stage = PriorityLogic.Stage.ASK
        var draft: String = ""
        /** Черновик подставлен Джарвисом (шаблон/обучение) — его надо показать. */
        var suggested: Boolean = false
        /** Системное действие «Ответить» из уведомления мессенджера. */
        var actions: Array<Notification.Action>? = null
        var askedAt: Long = receivedAt
        var lastRemindAt: Long = 0L
        val notifId: Int get() = 610 + id
        fun isPriority(): Boolean = kind == Kind.PRIORITY
    }

    /** Что Джарвис скажет после реплики владельца. */
    data class Step(val say: String, val done: Boolean, val sent: Boolean = false)

    private const val MAX_PENDING = 3
    private const val MAX_TEXT = 300

    private val lock = Any()
    private val pending = ArrayList<Request>()
    private var seq = 0

    /**
     * Отложенные напоминания крутим на главном потоке приложения. Handler
     * создаём лениво: сам по себе [PriorityInbox] должен подниматься и без
     * Android-окружения (так его видит scripts/test-commands.sh).
     */
    private val ui: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }

    // ------------------------------------------------------------------ вход

    /**
     * Регистрирует входящее сообщение, по которому Джарвис обязан спросить
     * владельца. Возвращает созданный (или обновлённый) запрос.
     *
     * @param draft      уже готовый черновик (для режима «предложить остальным»)
     * @param suggested  черновик придумал Джарвис, а не владелец
     */
    fun register(
        c: Context,
        title: String,
        appLabel: String,
        text: String,
        kind: Kind,
        actions: Array<Notification.Action>? = null,
        draft: String = "",
        suggested: Boolean = false
    ): Request {
        val now = System.currentTimeMillis()
        val key = "$appLabel|$title"
        val cleanText = text.trim().take(MAX_TEXT)
        val r: Request
        synchronized(lock) {
            val same = pending.firstOrNull { it.chatKey == key }
            if (same != null) {
                // догоняющее сообщение в том же чате: дописываем и переспрашиваем
                same.text = if (same.text == cleanText) cleanText
                else (same.text + "\n" + cleanText).take(MAX_TEXT)
                same.category = PriorityLogic.categorize(cleanText)
                same.stage = PriorityLogic.Stage.ASK
                same.draft = draft
                same.suggested = suggested
                same.askedAt = now
                if (!actions.isNullOrEmpty()) same.actions = actions
                r = same
            } else {
                while (pending.size >= MAX_PENDING) pending.removeAt(0)
                r = Request(
                    id = ++seq, chatKey = key, title = title, appLabel = appLabel,
                    text = cleanText, kind = kind,
                    category = PriorityLogic.categorize(cleanText), receivedAt = now
                ).apply {
                    this.actions = actions
                    this.draft = draft
                    this.suggested = suggested
                }
                pending.add(r)
            }
        }
        AutoLog.add(
            c,
            "★ ${if (r.isPriority()) "Приоритетный контакт" else "Черновик"}: " +
                "«${r.title}» ($appLabel) — «${r.text.take(80)}»" +
                (if (draft.isBlank()) "" else " · черновик: «${draft.take(80)}»")
        )
        // в режиме шаблонов готовый ответ подставляем сразу — но первый раз в
        // теме всё равно спрашиваем владельца (см. PriorityLogic.autoPlan)
        if (draft.isBlank() && r.isPriority()) {
            val plan = PriorityLogic.autoPlan(
                r.text,
                Prefs.priorityTemplates(c),
                Prefs.priorityLearned(c),
                Prefs.priorityApproved(c),
                Prefs.priorityAutoTemplate(c)
            )
            if (plan.hasDraft) {
                r.draft = plan.draft ?: ""
                r.suggested = true
                if (!plan.needConfirm && Prefs.priorityAutoTemplate(c)) {
                    AutoLog.add(c, "«${r.title}»: шаблон подтверждён раньше — отвечаю по шаблону сам.")
                    sendNow(c, r)
                    return r
                }
                AutoLog.add(c, "«${r.title}»: предлагаю шаблон «${r.draft}».")
            }
        }
        Notify.priorityAsk(c, r)
        if (Prefs.priorityVoice(c)) speak(c, notifyText(r))
        scheduleReminder(c, r)
        return r
    }

    /** Текущий (самый старый) неотвеченный запрос. */
    fun current(): Request? = synchronized(lock) { pending.firstOrNull() }

    fun byId(id: Int): Request? = synchronized(lock) { pending.firstOrNull { it.id == id } }

    fun count(): Int = synchronized(lock) { pending.size }

    private fun resolve(r: Request) {
        synchronized(lock) { pending.remove(r) }
    }

    /** Текст уведомления/вопроса: с черновиком или без. */
    fun notifyText(r: Request): String = when {
        r.stage == PriorityLogic.Stage.CONFIRM -> PriorityLogic.confirmLine(r.title, r.draft)
        r.suggested && r.draft.isNotBlank() ->
            "${PriorityLogic.incomingLine(r.title, r.text)}\nПредлагаю ответить: «${r.draft}» — отправляю?"
        else -> PriorityLogic.askLine(r.title, r.text)
    }

    // ------------------------------------------------- разбор реплики владельца

    /**
     * Вход из CommandEngine: реплика владельца (голосом или текстом).
     * null — это не про приоритетные контакты, пусть отвечает обычный движок.
     */
    fun command(ctx: Context, raw: String): CommandEngine.Outcome? {
        val s = CommandEngine.normalize(raw)
        if (s.isEmpty()) return null

        val cmd = PriorityLogic.adminCommand(s)
        if (cmd != null) return admin(ctx, s, cmd, raw)

        val r = current() ?: return null
        return when (PriorityLogic.intentOf(r.stage, s, r.suggested && r.draft.isNotBlank())) {
            PriorityLogic.ReplyIntent.SERVICE -> null   // это команда Джарвису — пусть движок
            PriorityLogic.ReplyIntent.REPEAT ->
                CommandEngine.Outcome(notifyText(r))
            PriorityLogic.ReplyIntent.SKIP -> {
                resolve(r)
                Notify.cancelPriority(ctx, r.notifId)
                AutoLog.add(ctx, "«${r.title}»: владелец решил не отвечать.")
                CommandEngine.Outcome(PriorityLogic.skipLine(r.title))
            }
            PriorityLogic.ReplyIntent.CANCEL -> {
                resolve(r)
                Notify.cancelPriority(ctx, r.notifId)
                AutoLog.add(ctx, "«${r.title}»: владелец отменил отправку.")
                CommandEngine.Outcome(PriorityLogic.cancelledLine(r.title))
            }
            PriorityLogic.ReplyIntent.CONFIRM -> {
                // подтверждение предложенного черновика приравниваем к стадии подтверждения
                r.stage = PriorityLogic.Stage.CONFIRM
                sendOutcome(ctx, r)
            }
            PriorityLogic.ReplyIntent.ANSWER -> {
                val answer = PriorityLogic.stripLeadingYes(WakeWords.removeFromText(raw).trim())
                if (answer.isBlank()) {
                    CommandEngine.Outcome("Что передать ${PriorityLogic.dative(r.title)}, сэр?")
                } else {
                    draftOutcome(ctx, r, answer)
                }
            }
        }
    }

    /** Тот же разбор, но из уведомления (уже в фоновом потоке). */
    fun answer(ctx: Context, id: Int, raw: String): Step {
        val r = byId(id) ?: return Step("Сообщение уже закрыто, сэр.", true)
        val s = CommandEngine.normalize(raw)
        return when (PriorityLogic.intentOf(r.stage, s, r.suggested && r.draft.isNotBlank())) {
            PriorityLogic.ReplyIntent.SERVICE ->
                Step("Это команда мне, сэр, а не ответ собеседнику. Скажите, что передать.", false)
            PriorityLogic.ReplyIntent.REPEAT -> Step(notifyText(r), false)
            PriorityLogic.ReplyIntent.SKIP -> {
                resolve(r)
                Notify.cancelPriority(ctx, r.notifId)
                Step(PriorityLogic.skipLine(r.title), true)
            }
            PriorityLogic.ReplyIntent.CANCEL -> {
                resolve(r)
                Notify.cancelPriority(ctx, r.notifId)
                Step(PriorityLogic.cancelledLine(r.title), true)
            }
            PriorityLogic.ReplyIntent.CONFIRM -> {
                r.stage = PriorityLogic.Stage.CONFIRM
                val ok = send(ctx, r)
                if (ok) Step(PriorityLogic.sentLine(r.title, r.draft), true, true)
                else Step(PriorityLogic.failedLine(r.title), true)
            }
            PriorityLogic.ReplyIntent.ANSWER -> {
                val answer = PriorityLogic.stripLeadingYes(WakeWords.removeFromText(raw).trim())
                if (answer.isBlank()) {
                    return Step("Что передать ${PriorityLogic.dative(r.title)}, сэр?", false)
                }
                val draft = buildDraft(ctx, r, answer)
                r.draft = draft
                r.stage = PriorityLogic.Stage.CONFIRM
                r.suggested = false
                Notify.priorityConfirm(ctx, r)
                Step(PriorityLogic.confirmLine(r.title, draft), false)
            }
        }
    }

    /** Отправить по подтверждению из уведомления. */
    fun sendById(ctx: Context, id: Int): Step {
        val r = byId(id) ?: return Step("Сообщение уже закрыто, сэр.", true)
        val ok = send(ctx, r)
        return if (ok) Step(PriorityLogic.sentLine(r.title, r.draft), true, true)
        else Step(PriorityLogic.failedLine(r.title), true)
    }

    /** Отменить запрос (кнопка «Не отвечать»). */
    fun cancelById(ctx: Context, id: Int): Step {
        val r = byId(id) ?: return Step("Сообщение уже закрыто, сэр.", true)
        resolve(r)
        Notify.cancelPriority(ctx, r.notifId)
        AutoLog.add(ctx, "«${r.title}»: владелец решил не отвечать.")
        return Step(PriorityLogic.skipLine(r.title), true)
    }

    // --------------------------------------------------------- черновик ответа

    /** Outcome с формулировкой: модель оформляет ответ, затем показываем его. */
    private fun draftOutcome(ctx: Context, r: Request, answer: String): CommandEngine.Outcome {
        if (!Llm.ready(ctx)) {
            val draft = buildDraft(ctx, r, answer)
            r.stage = PriorityLogic.Stage.CONFIRM
            r.draft = draft
            r.suggested = false
            Notify.priorityConfirm(ctx, r)
            return CommandEngine.Outcome(
                PriorityLogic.confirmLine(r.title, draft) + " Скажите «да» — и я отправлю."
            )
        }
        return CommandEngine.Outcome(
            "Сформулирую и покажу, сэр.",
            async = { c, done ->
                val draft = buildDraft(c, r, answer)
                r.stage = PriorityLogic.Stage.CONFIRM
                r.draft = draft
                r.suggested = false
                Notify.priorityConfirm(c, r)
                done(PriorityLogic.confirmLine(r.title, draft) + " Скажите «да» — и я отправлю.")
            }
        )
    }

    /**
     * Собирает итоговый текст: модель оформляет смысл владельца в живое
     * сообщение, а если модели нет (или она ушла в сторону) — аккуратная
     * обработка без изменения смысла.
     */
    private fun buildDraft(ctx: Context, r: Request, answer: String): String {
        val style = PriorityLogic.styleOf(Prefs.priorityStyles(ctx), r.title)
        val fallback = PriorityLogic.polish(answer, style).ifBlank { answer.trim() }
        if (!Llm.ready(ctx)) return fallback
        return try {
            val prompt = PriorityLogic.draftPrompt(
                r.title, r.text, answer, style.hint(),
                Prefs.autoReplyRules(ctx), nowText()
            )
            val raw = Llm.complete(ctx, PriorityLogic.draftSystem(), prompt, maxTokens = 220)
            val parsed = AutoReplyLogic.cleanReply(AutoReplyLogic.parseReply(raw))
            if (parsed.isBlank()) {
                AutoLog.add(ctx, "«${r.title}»: модель не дала текста — отдаю вашу формулировку.")
                fallback
            } else if (!PriorityLogic.keepsMeaning(parsed, answer)) {
                AutoLog.add(ctx, "«${r.title}»: ответ модели ушёл от смысла — оставляю вашу формулировку.")
                fallback
            } else {
                AutoLog.add(ctx, "«${r.title}»: черновик готов: «${parsed.take(80)}»")
                parsed
            }
        } catch (e: Throwable) {
            AutoLog.add(ctx, "«${r.title}»: ошибка модели: ${e.message?.take(80) ?: "неизвестно"}")
            fallback
        }
    }

    private fun nowText(): String =
        SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date())

    // --------------------------------------------------------------- отправка

    private fun sendOutcome(ctx: Context, r: Request): CommandEngine.Outcome =
        CommandEngine.Outcome(
            "Отправляю, сэр.",
            async = { c, done ->
                val ok = send(c, r)
                done(
                    if (ok) PriorityLogic.sentLine(r.title, r.draft)
                    else PriorityLogic.failedLine(r.title)
                )
            }
        )

    /** Отправить (в фоне) и показать результат — для шаблонного автоответа. */
    private fun sendNow(c: Context, r: Request) {
        Thread {
            val ok = try {
                send(c, r)
            } catch (t: Throwable) {
                false
            }
            if (ok) Notify.prioritySent(c, r) else Notify.priorityFailed(c, r)
        }.start()
    }

    /**
     * Отправка через системное действие «Ответить» из уведомления мессенджера
     * (тот же механизм, что в умных часах). Возвращает false, если мессенджер
     * не прислал такого действия.
     */
    fun send(c: Context, r: Request): Boolean {
        val text = r.draft.trim()
        if (text.isEmpty()) return false
        val actions = r.actions
        if (actions.isNullOrEmpty()) {
            AutoLog.add(c, "«${r.title}»: нет действия «Ответить» — отправить не могу.")
            return false
        }
        val ok = sendViaActions(c, actions, text)
        if (ok) {
            remember(c, r)
            AutoLog.add(c, "✔ Отправил «${r.title}» ($r.appLabel): $text")
            Notify.prioritySent(c, r)
            if (Prefs.priorityVoice(c)) speak(c, PriorityLogic.sentLine(r.title, text))
        } else {
            AutoLog.add(c, "✖ «${r.title}»: действие «Ответить» не сработало.")
            Notify.priorityFailed(c, r)
        }
        // уведомление не гасим: на его месте уже висит «отправил» (или «не смог»)
        resolve(r)
        return ok
    }

    /** Кладём текст во все поля ввода действия и жмём «Отправить». */
    private fun sendViaActions(
        c: Context,
        actions: Array<Notification.Action>,
        reply: String
    ): Boolean {
        var lastErr: String? = null
        for (a in actions) {
            val ris = a.remoteInputs ?: continue
            if (ris.isEmpty()) continue
            try {
                val intent = Intent()
                val results = Bundle()
                for (ri in ris) results.putCharSequence(ri.resultKey, reply)
                RemoteInput.addResultsToIntent(ris, intent, results)
                a.actionIntent.send(c, 0, intent)
                return true
            } catch (e: Exception) {
                lastErr = e.message
            }
        }
        if (lastErr != null) AutoLog.add(c, "Отправка не удалась: ${lastErr.take(120)}")
        return false
    }

    /** Запоминаем стиль, частый ответ и подтверждение темы. */
    private fun remember(c: Context, r: Request) {
        if (Prefs.priorityLearn(c)) {
            Prefs.setPriorityLearned(
                c, PriorityLogic.learn(Prefs.priorityLearned(c), r.category, r.draft)
            )
            Prefs.setPriorityStyles(
                c, PriorityLogic.addStyle(Prefs.priorityStyles(c), r.title, r.draft)
            )
        }
        // владелец подтвердил отправку в этой теме — шаблону можно доверять
        Prefs.setPriorityApproved(c, PriorityLogic.approve(Prefs.priorityApproved(c), r.category))
    }

    // --------------------------------------------------- напоминание о сообщении

    private fun scheduleReminder(c: Context, r: Request) {
        val delay = PriorityLogic.nextRemindDelayMs(Prefs.priorityRemindMin(c).toLong())
        ui.postDelayed({
            val cur = byId(r.id) ?: return@postDelayed
            val now = System.currentTimeMillis()
            val remindedRecently = cur.lastRemindAt > 0 && now - cur.lastRemindAt < delay
            if (PriorityLogic.shouldRemind(
                    cur.askedAt, now, delay, remindedRecently, Prefs.priorityRemind(c)
                )
            ) {
                cur.lastRemindAt = now
                val minutes = (now - cur.receivedAt) / 60_000L
                AutoLog.add(c, "Напоминаю о неотвеченном «${cur.title}» (${PriorityLogic.minutesString(minutes)}).")
                Notify.priorityRemind(c, cur, minutes)
                if (Prefs.priorityVoice(c)) {
                    speak(c, PriorityLogic.remindLine(cur.title, cur.text, minutes))
                }
            }
            if (cur.stage == PriorityLogic.Stage.ASK || cur.stage == PriorityLogic.Stage.CONFIRM) {
                scheduleReminder(c, cur)     // пока ждём — напоминаем мягко, но настойчиво
            }
        }, delay)
    }

    // -------------------------------------------------- управление голосом

    private fun admin(
        ctx: Context,
        s: String,
        cmd: PriorityLogic.AdminCmd,
        raw: String
    ): CommandEngine.Outcome? = when (cmd) {
        PriorityLogic.AdminCmd.ADD -> {
            val name = PriorityLogic.contactFromCommand(raw)
            if (name.isNullOrBlank()) {
                CommandEngine.Outcome(
                    "Кого записать в приоритетные, сэр? Например: «добавь приоритетный контакт мама»."
                )
            } else {
                Prefs.setPriorityList(ctx, PriorityLogic.addName(Prefs.priorityList(ctx), name))
                CommandEngine.Outcome(
                    "Записал «$name» в приоритетные контакты, сэр. " +
                        "Теперь, когда $name напишет, я спрошу вас, что ответить."
                )
            }
        }

        PriorityLogic.AdminCmd.REMOVE -> {
            val name = PriorityLogic.contactFromCommand(raw)
            if (name.isNullOrBlank()) {
                CommandEngine.Outcome("Кого убрать из приоритетных, сэр?")
            } else {
                val (list, was) = PriorityLogic.removeName(Prefs.priorityList(ctx), name)
                Prefs.setPriorityList(ctx, list)
                CommandEngine.Outcome(
                    if (was) "Убрал «$name» из приоритетных контактов, сэр."
                    else "«$name» и так не было в списке приоритетных, сэр."
                )
            }
        }

        PriorityLogic.AdminCmd.SHOW -> CommandEngine.Outcome(listText(ctx))

        PriorityLogic.AdminCmd.ON -> {
            Prefs.setPriorityOn(ctx, true)
            CommandEngine.Outcome("Приоритетные контакты включены, сэр. " + status(ctx))
        }

        PriorityLogic.AdminCmd.OFF -> {
            Prefs.setPriorityOn(ctx, false)
            CommandEngine.Outcome(
                "Приоритетные контакты выключены, сэр: входящие сообщения я больше не показываю " +
                    "и ни о чём не спрашиваю."
            )
        }

        PriorityLogic.AdminCmd.STATUS -> CommandEngine.Outcome(status(ctx))

        PriorityLogic.AdminCmd.TEMPLATE_SET -> {
            val pair = PriorityLogic.templateFromCommand(raw)
            if (pair == null) {
                CommandEngine.Outcome(
                    "Какой шаблон записать, сэр? Например: «шаблон: еда = Да, мам, уже поел»."
                )
            } else {
                val (list, ok) = PriorityLogic.setTemplate(
                    Prefs.priorityTemplates(ctx), pair.first, pair.second
                )
                if (!ok) {
                    CommandEngine.Outcome(
                        "Тему «${pair.first}» не знаю, сэр. Доступные темы: ${PriorityLogic.templateHint()}."
                    )
                } else {
                    Prefs.setPriorityTemplates(ctx, list)
                    CommandEngine.Outcome("Шаблон записан, сэр: «${pair.first} = ${pair.second.trim()}».")
                }
            }
        }

        PriorityLogic.AdminCmd.TEMPLATE_SHOW -> {
            val t = Prefs.priorityTemplates(ctx)
            CommandEngine.Outcome(
                if (t.isBlank()) "Шаблонов ответов пока нет, сэр. Скажите, например: «шаблон: еда = Да, мам, уже поел»."
                else "Шаблоны ответов, сэр:\n$t"
            )
        }

        PriorityLogic.AdminCmd.TEMPLATE_CLEAR -> {
            Prefs.setPriorityTemplates(ctx, "")
            CommandEngine.Outcome("Шаблоны ответов очищены, сэр.")
        }

        PriorityLogic.AdminCmd.APPROVE_RESET -> {
            Prefs.setPriorityApproved(ctx, PriorityLogic.resetApproved())
            CommandEngine.Outcome(
                "Сбросил подтверждения, сэр: в каждой теме снова спрошу вас перед первой отправкой."
            )
        }

        PriorityLogic.AdminCmd.HELP -> CommandEngine.Outcome(
            "Режим приоритетных контактов, сэр. Когда пишет кто-то из списка, я не отвечаю сам: " +
                "показываю сообщение, спрашиваю «что ответить», оформляю ваш ответ и отправляю " +
                "только после вашего «да». Управление: «добавь приоритетный контакт мама», " +
                "«покажи приоритетные контакты», «удали приоритетный контакт папа», " +
                "«шаблон: еда = Да, мам, уже поел», «включи/выключи приоритетные контакты»."
        )
    }

    /** Список приоритетных — для голосовой команды «покажи …». */
    fun listText(ctx: Context): String {
        val list = Prefs.priorityContacts(ctx)
        return when {
            list.isEmpty() ->
                "Список приоритетных контактов пуст, сэр. Скажите «добавь приоритетный контакт мама»."
            else -> "Приоритетные контакты, сэр: ${list.joinToString(", ")}."
        }
    }

    /** Короткий статус режима (голосом: «приоритетные контакты включены?»). */
    fun status(ctx: Context): String {
        val list = Prefs.priorityContacts(ctx)
        if (!Prefs.priorityOn(ctx)) {
            return "Режим приоритетных контактов выключен, сэр. Скажите «включи приоритетные контакты»."
        }
        return buildString {
            append("Режим приоритетных контактов включён, сэр. ")
            if (list.isEmpty()) {
                append("Список пуст — добавьте контакт: «добавь приоритетный контакт мама». ")
            } else {
                append("Следжу за ").append(list.joinToString(", ")).append(". ")
            }
            append(
                when {
                    !AutoReplyService.isGranted(ctx) ->
                        "Но без «доступа к уведомлениям» я не вижу сообщения — включите «Джарвис — автоответчик» в настройках."
                    !Llm.ready(ctx) ->
                        "Провайдера ИИ нет — буду оформлять ваши ответы как есть, без обработки."
                    else -> "Отправляю только после вашего подтверждения."
                }
            )
        }
    }

    private fun speak(c: Context, text: String) {
        try {
            Speaker.once(c.applicationContext, text)
        } catch (e: Exception) {
            // озвучка — не главное, молча терпим
        }
    }
}
