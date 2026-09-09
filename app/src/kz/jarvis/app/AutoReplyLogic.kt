package kz.jarvis.app

/**
 * Чистая логика автоответчика в мессенджерах — без Android, поэтому покрыта
 * scripts/test-logic.sh (выполняется на обычной JVM).
 *
 * Что здесь: какие приложения Джарвис умеет читать, отличаем личного
 * собеседника от группы/рассылки, сборка задания для модели, разбор её
 * ответа и лимиты частоты, чтобы автоответчик не «разговаривал» сам с собой.
 */
object AutoReplyLogic {

    /** Пакеты мессенджеров, у которых есть системное действие «ответить». */
    val MESSENGERS: List<Pair<String, String>> = listOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "com.viber.voip" to "Viber"
    )

    /** Человеческое имя мессенджера по пакету; null — приложение не из списка. */
    fun messengerLabel(pkg: String): String? =
        MESSENGERS.firstOrNull { it.first == pkg }?.second

    /** Приводим имя к виду «буквы и пробелы» для сравнения. */
    fun norm(name: String): String =
        name.lowercase(java.util.Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Можно ли отвечать этому чату: это личный контакт из телефонной книги
     * или «сырой» номер телефона. Группы и рассылки обычно не совпадают ни
     * с одним контактом — им Джарвис не отвечает.
     */
    fun isKnownPerson(chatTitle: String, contactNames: List<String>): Boolean {
        val t = norm(chatTitle)
        if (t.isEmpty()) return false
        // «+7 912 345-67-89», «89123456789» — личный чат по номеру
        if (Regex("^\\+?[\\d\\s()\\-]{6,}$").matches(t)) return true
        return contactNames.any { cn ->
            val n = norm(cn)
            n.isNotEmpty() && (n == t || n.startsWith(t) || (n.length >= 4 && t.startsWith(n)))
        }
    }

    /** Служебный мусор, на который автоответ не нужен. */
    private val JUNK = listOf(
        "шифрование", "ваше сообщение защищено", "вы в сети", "нажмите, чтобы открыть",
        "сообщение удалено", "это сообщение удалено", "звонок в", "видеозвонок",
        "неотвеченный", "входящий звонок", "удерживайте"
    )

    fun shouldSkip(chatTitle: String, text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty()) return true
        if (norm(chatTitle).isEmpty()) return true
        return JUNK.any { j -> t.contains(norm(j)) }
    }

    /** Похоже ли сообщение на вопрос «где ты» — тогда подключаем геолокацию. */
    fun asksLocation(text: String): Boolean {
        val t = norm(text)
        return Regex("(^|\\s)(где|куда|гд|ты где|вы где|далеко|близко|локаци|координат|доехал|приехал|скоро будешь)(\\s|$|[!?.])")
            .containsMatchIn(t) || t.contains("где ты") || t.contains("где вы")
    }

    // ------------------------------------------------------------- разбор ответа модели

    /** Забирает «reply» из JSON-ответа модели. Пусто — отвечать не нужно. */
    fun parseReply(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        var s = t
        // убрать markdown-обёртку ```json … ``` / ```…```
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            if (s.endsWith("```")) s = s.dropLast(3).trim()
        }
        Regex("\"reply\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", RegexOption.IGNORE_CASE)
            .find(s)?.let { m ->
                return unescape(m.groupValues[1]).trim()
            }
        // модель иногда отвечает просто текстом без JSON — берём его,
        // но только если это не «скобочное» рассуждение
        if (!s.startsWith("{") && !s.startsWith("[")) {
            val clean = s.trim().trim('"', '\'', ' ', '\n')
            if (clean.length in 1..280) return clean
        }
        return ""
    }

    private fun unescape(s: String): String = s
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", " ")
        .replace("\\t", " ")

    /** Короткая нормализация текста ответа перед отправкой. */
    fun cleanReply(reply: String): String =
        reply.replace(Regex("\\s+"), " ").trim().take(300)

    // --------------------------------------------------------------- задание модели

    /**
     * Собирает инструкцию для модели: кто пишет, что писать, когда молчать.
     *
     * @param history       недавние сообщения собеседника (старые сверху)
     * @param ownerRules    личные правила владельца («если зовут гулять — занят»)
     * @param locationCtx   строка о геолокации владельца или null
     */
    fun decisionPrompt(
        chatTitle: String,
        history: List<String>,
        ownerRules: String,
        locationCtx: String?,
        nowText: String
    ): String {
        val sb = StringBuilder()
        sb.append("Ты — личный ассистент владельца телефона. Ты отвечаешь в мессенджере ")
            .append("ОТ ИМЕНИ владельца его собеседнику «").append(chatTitle).append("».\n")
        sb.append("Правила:\n")
        sb.append("- отвечай по-русски коротко и естественно, как живой человек в чате: ")
            .append("1–2 коротких предложения, без канцелярита, списков, разметки и эмодзи;\n")
        sb.append("- никогда не пиши, что ты ИИ, робот, Джарвис или ассистент, ")
            .append("и не говори о себе в третьем лице — пиши от первого лица, как сам владелец;\n")
        if (ownerRules.isNotBlank()) {
            sb.append("- личные правила владельца: ").append(ownerRules).append("\n")
        }
        sb.append("- если собеседник задал вопрос или ждёт реакции — ответь по делу;\n")
        sb.append("- если сообщение не требует ответа (болтовня, «спасибо», реклама, ")
            .append("рассылка, поздравление, сообщение не владельцу) — НЕ отвечай;\n")
        sb.append("- если это групповая беседа, а не личный чат — НЕ отвечай.\n")
        if (history.isNotEmpty()) {
            sb.append("Недавние сообщения собеседника (старые сверху):\n")
            for (h in history.takeLast(8)) sb.append("> ").append(h).append("\n")
        }
        sb.append("Сейчас: ").append(nowText).append(".\n")
        if (locationCtx != null) sb.append(locationCtx).append("\n")
        sb.append("Ответь СТРОГО одним JSON без пояснений и без слов до/после него: ")
            .append("{\"reply\": \"текст ответа\"} или {\"reply\": \"\"}, если отвечать не нужно.")
        return sb.toString()
    }

    // ------------------------------------------------------------- лимиты частоты

    /**
     * Разрешён ли ещё автоответ: не чаще [cap] раз за [windowMs] (список — время
     * предыдущих отправок). Защита от «диалога Джарвиса самого с собой».
     */
    fun allowed(times: List<Long>, now: Long, cap: Int = 4, windowMs: Long = 60_000): Boolean {
        if (cap <= 0) return false
        val fresh = times.filter { now - it < windowMs }
        return fresh.size < cap
    }
}
