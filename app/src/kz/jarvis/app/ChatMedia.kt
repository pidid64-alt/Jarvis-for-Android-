package kz.jarvis.app

/**
 * Чистый разбор «внешних» голосовых команд — без Android, поэтому покрыт
 * scripts/test-logic.sh на обычной JVM:
 *  • «напиши маме в вацап: привет» — кто, куда (WhatsApp) и что писать;
 *  • «включи песню X на ютубе» / «включи X в спотифае» — платформа и запрос;
 *  • приведение номера к международному виду для WhatsApp (wa.me / jid).
 */
object ChatMedia {

    /** WhatsApp-пакеты (обычный + Business). */
    val WA_PACKAGES: List<Pair<String, String>> = listOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business"
    )

    /** «в вацап», «по вацапу», «в whatsapp»… — с любыми окончаниями. */
    private val WA_TOKEN = Regex(
        "\\b(?:в|по)?\\s*(?:whatsapp|ватап|васап|ватсап|вотсап|вацап|воцап)[а-я]*\\b",
        RegexOption.IGNORE_CASE
    )

    /** Глаголы, которыми просят написать. */
    private val VERB = Regex(
        "^(?:напиши|напишите|написать|отправь|отправить|скажи|передай|кинь|черкни|сбрось)\\s+"
    )

    /** Итог разбора: кому (как сказано) и что написать. */
    data class WaMsg(val name: String, val body: String)

    /** Содержит ли фраза упоминание WhatsApp (отправка именно туда). */
    fun isWaPhrase(s: String): Boolean = WA_TOKEN.containsMatchIn(s)

    /**
     * Разбирает «напиши маме в вацап: я уже еду», «отправь в вацап папе что я занят»…
     * Возвращает имя (как сказано, напр. «маме») и текст сообщения.
     * Текст может отделяться «что», «текст», двоеточием, тире или идти сразу за именем.
     */
    fun parseWa(s0: String): WaMsg? {
        val s = s0.trim()
        if (!isWaPhrase(s)) return null
        // убираем «в вацап/воцапе/по ватсапу…» из любого места фразы
        var rest = WA_TOKEN.replace(s, " ")
        rest = Regex("\\s*[,;]\\s*").replace(rest, " ")   // «маме, что я занят» → «маме что я занят»
        val verb = VERB.find(rest)?.value?.trim() ?: return null
        rest = rest.removePrefix(verb).trim()
        rest = rest.removePrefix("мне ").trim()
        if (rest.isEmpty()) return null

        // разделители: «что/текст/следующее/такое» или двоеточие/тире
        val intro = Regex("\\s(?:что|текст|следующее|такое)\\s|\\s*[:—\\-]\\s*").find(rest)
        if (intro == null) {
            // без разделителя: имя — первое слово, остальное — текст
            val sp = rest.indexOf(' ')
            if (sp < 0) return null
            return WaMsg(rest.substring(0, sp).trim(), rest.substring(sp + 1).trim())
        }
        val name = rest.substring(0, intro.range.first).trim()
        val body = rest.substring(intro.range.last + 1).trim()
        if (name.length < 2 || body.isEmpty()) return null
        return WaMsg(name, body)
    }

    // ------------------------------------------------------------- ютуб и спотифай

    /** Запрос для YouTube; null — фраза не про YouTube; "" — просто «открой ютуб». */
    fun youtubeQuery(s: String): String? {
        if (!Regex("ютуб|youtube|йоутуб|ютьюб|youtu", RegexOption.IGNORE_CASE).containsMatchIn(s)) return null
        if (!isPlayPhrase(s)) return null
        return cleanQuery(s, listOf("ютуб", "youtube", "йоутуб", "ютьюб"))
    }

    /** Запрос для Spotify; null — фраза не про Spotify; "" — просто «открой спотифай». */
    fun spotifyQuery(s: String): String? {
        if (!Regex("спотифай|спотифае|спотифей|спотифая|spotify|споти", RegexOption.IGNORE_CASE)
                .containsMatchIn(s)
        ) return null
        if (!isPlayPhrase(s)) return null
        return cleanQuery(s, listOf("спотифай", "спотифае", "спотифей", "спотифая", "spotify"))
    }

    private fun isPlayPhrase(s: String): Boolean =
        Regex("включ|запуст|поставь|постав|вруби|сыграй|слушать|послушать|покажи|найди|открой|давай", RegexOption.IGNORE_CASE)
            .containsMatchIn(s)

    /**
     * Выкидывает глагол, платформу и «песню/видео/клип…», оставляет чистый запрос
     * («shape of you», «как собрать стол»). Пусто — просто открыть приложение.
     */
    private fun cleanQuery(s: String, platform: List<String>): String {
        var q = s
        for (p in platform) {
            q = Regex("\\s*(?:на|в|по)?\\s*$p[а-я]*\\s*", RegexOption.IGNORE_CASE).replace(q, " ")
        }
        q = Regex("включи|включить|вруби|запусти|запустить|поставь|поставить|сыграй|сыграть|" +
            "слушать|послушать|покажи|найди|открой|открыть|давай|хочу", RegexOption.IGNORE_CASE)
            .replace(q, " ")
        q = Regex("песню|песня|песни|песен|музыку|музыка|музыки|трек|треки|трека|видео|ролик|" +
            "клип|клипы|канал|фильм|мультик|сериал|аудио|композицию|композиция", RegexOption.IGNORE_CASE)
            .replace(q, " ")
        q = Regex("\\s+", RegexOption.IGNORE_CASE).replace(q, " ")
        return q.trim(' ', '.', '!', '?', ',', ':', '—', '-')
    }

    // ------------------------------------------------------------- номер для WhatsApp

    /**
     * Приводит номер как он в контактах к виду для WhatsApp: только цифры,
     * международный, без «+». «8 771 234-56-78» → «77712345678»,
     * «7712345678» (10 цифр, местный) → «77712345678». null — явно битый.
     */
    fun phoneForWa(raw: String): String? {
        val d = raw.filter { it.isDigit() }
        val n = when {
            d.length == 11 && d.startsWith("8") -> "7" + d.drop(1)
            d.length == 10 && (d.startsWith("7") || d.startsWith("9") || d.startsWith("3")) ->
                "7" + d
            d.length == 11 && d.startsWith("7") -> d
            d.length == 12 && d.startsWith("7") -> d.drop(1)
            else -> return null
        }
        return n.takeIf { it.length in 11..12 }
    }
}
