package kz.jarvis.app

import java.util.Locale

/**
 * Разбор команд вида «поищи инфу про X и сделай презентацию».
 *
 * Здесь же — тексты запросов, которые уходят Клоду (или другому провайдеру):
 * системный промпт, задание на презентацию, задание на устный пересказ и
 * сообщение, которое вставляется прямо в приложение Claude, если ключа нет.
 *
 * Файл не зависит от Android — покрыт scripts/test-logic.sh.
 */
object ResearchPlan {

    enum class Kind {
        /** Просто найти и пересказать голосом. */
        RESEARCH,
        /** Найти и собрать презентацию. */
        PRESENTATION,
        /** Отправить текст/вопрос прямо в Клод. */
        CLAUDE,
        /** Открыть последнюю сделанную презентацию. */
        OPEN_LAST
    }

    class Plan(
        val kind: Kind,
        val topic: String,
        val slides: Int = DEFAULT_SLIDES,
        /** Работать через приложение/сайт Claude, а не через API. */
        val viaClaudeApp: Boolean = false
    )

    const val DEFAULT_SLIDES = 8
    const val MIN_SLIDES = 3
    const val MAX_SLIDES = 16

    private val PRESENTATION_WORDS =
        Regex("презентац|презу|слайд|доклад|реферат|презентуй|питч")

    private val RESEARCH_VERBS =
        Regex("^(?:поищи|поиши|найди|нагугли|погугли|загугли|собери|изучи|исследуй|разузнай|" +
            "посмотри|глянь|узнай|проанализируй|подними)(?=\\s|$)")

    private val RESEARCH_OBJECTS =
        Regex("инфу|инфо|информаци|данные|материал|сведени|факт|что известно|подробност|" +
            "статистик|источник")

    private val CLAUDE_WORDS = Regex("клод|клауд|claude")

    // ------------------------------------------------------------------ разбор

    fun parse(raw: String): Plan? {
        val s = raw.trim().lowercase(Locale.ROOT).replace('ё', 'е').trim('.', '!', '?', ' ')
        if (s.isEmpty()) return null

        // «открой презентацию», «покажи последнюю презентацию»
        if (Regex("^(?:открой|покажи|запусти|верни)\\s+(?:мою\\s+|последнюю\\s+|прошлую\\s+)?" +
                "(?:презентацию|презу|слайды|доклад)\\s*$").containsMatchIn(s)
        ) return Plan(Kind.OPEN_LAST, "")

        val wantsPresentation = PRESENTATION_WORDS.containsMatchIn(s)
        val wantsClaude = CLAUDE_WORDS.containsMatchIn(s)
        val looksLikeSearch = RESEARCH_VERBS.containsMatchIn(s) && RESEARCH_OBJECTS.containsMatchIn(s)
        val makeVerb = Regex("^(?:сделай|собери|подготовь|создай|составь|сгенерируй|набросай|склей)(?=\\s|$)")
            .containsMatchIn(s)

        // «отправь это в клод», «спроси у клода …», «напиши клоду …»
        if (wantsClaude && !wantsPresentation && !looksLikeSearch) {
            val m = Regex("^(?:отправь|скинь|закинь|перекинь|передай|напиши|спроси|уточни|задай)\\s+" +
                "(?:вопрос\\s+)?(?:это\\s+)?(?:у\\s+)?(?:в\\s+|к\\s+)?(?:клоду|клода|клод|клауду|" +
                "клауд|claude)\\s*(.*)$").find(s)
            if (m != null) return Plan(Kind.CLAUDE, cleanTopic(m.groupValues[1]))
            if (Regex("^(?:открой|запусти)\\s+(?:клод|клауд|claude)").containsMatchIn(s)) {
                return Plan(Kind.CLAUDE, "")
            }
        }

        if (!wantsPresentation && !looksLikeSearch) return null
        if (wantsPresentation && !makeVerb && !looksLikeSearch &&
            !Regex("презентац|презу|доклад|реферат|слайд").containsMatchIn(s)
        ) return null

        val kind = if (wantsPresentation) Kind.PRESENTATION else Kind.RESEARCH
        return Plan(
            kind = kind,
            topic = cleanTopic(s),
            slides = slidesCount(s),
            viaClaudeApp = wantsClaude
        )
    }

    /** «на 10 слайдов» → 10; по умолчанию — восемь. */
    fun slidesCount(s: String): Int {
        val m = Regex("(\\d{1,2})\\s*(?:слайд|стран|пункт)").find(s)
            ?: Regex("(?:на|из)\\s+(\\d{1,2})(?!\\d)").find(s)
        val n = m?.groupValues?.get(1)?.toIntOrNull() ?: return DEFAULT_SLIDES
        return n.coerceIn(MIN_SLIDES, MAX_SLIDES)
    }

    /** Вырезает служебные слова и оставляет саму тему. */
    fun cleanTopic(raw: String): String {
        var t = raw.trim().lowercase(Locale.ROOT).replace('ё', 'е')

        val heads = listOf(
            Regex("^(?:а\\s+|ну\\s+|и\\s+|давай\\s+|можешь\\s+|пожалуйста\\s+)+"),
            Regex("^(?:поищи|поиши|найди|нагугли|погугли|загугли|собери|изучи|исследуй|разузнай|" +
                "посмотри|глянь|узнай|проанализируй|подними|сделай|подготовь|создай|составь|" +
                "сгенерируй|набросай|склей|скинь|отправь|напиши|спроси)\\s+"),
            Regex("^(?:мне\\s+|нам\\s+|быстро\\s+|срочно\\s+|пожалуйста\\s+)+"),
            Regex("^(?:всю\\s+|всякую\\s+|любую\\s+|краткую\\s+|подробную\\s+|свежую\\s+|" +
                "актуальную\\s+|нормальную\\s+|хорошую\\s+|небольшую\\s+|короткую\\s+)+"),
            // порядок важен: длинные слова раньше коротких, иначе «информацию»
            // обрежется до «рмацию»
            Regex("^(?:информацию|информации|материалов|подробности|презентации|" +
                "презентацию|статистику|сведения|материалы|материал|источники|слайдов|" +
                "реферат|справку|доклад|данные|данных|слайды|факты|инфу|инфо|презу)(?=\\s|$)\\s*"),
            Regex("^(?:в интернете|в инете|в сети|в гугле|в google|онлайн)\\s+"),
            Regex("^(?:про|обо|об|о|по теме|на тему|по|для|касательно|насчет)\\s+")
        )
        val tails = listOf(
            Regex("\\s+и\\s+(?:сделай|собери|подготовь|создай|составь|сгенерируй|набросай|" +
                "склей|оформи)\\s+(?:мне\\s+)?(?:из\\s+нее\\s+|из\\s+этого\\s+)?" +
                "(?:презентацию|презу|доклад|реферат|слайды|презентации).*$"),
            Regex("\\s+и\\s+(?:сделай|собери|подготовь|оформи)\\s*$"),
            Regex("\\s+(?:и\\s+)?(?:отправь|скинь|закинь|перекинь|загрузи)\\s+(?:это\\s+)?" +
                "(?:в|к)\\s+(?:клод|клауд|claude).*$"),
            Regex("\\s+(?:в|через|с помощью)\\s+(?:клоде|клод|клауде|клауд|claude)(?:\\s.*)?$"),
            Regex("\\s+(?:на|из)\\s+\\d{1,2}\\s*(?:слайд[а-я]*|стран[а-я]*|пункт[а-я]*).*$"),
            Regex("\\s+\\d{1,2}\\s*(?:слайд[а-я]*|стран[а-я]*)\\s*$"),
            Regex("\\s+(?:презентацию|презу|доклад|реферат|слайды)\\s*$"),
            Regex("\\s+(?:пожалуйста|спасибо|будь добр|срочно|быстро)\\s*$"),
            Regex("^(?:презентацию|презу|доклад|реферат)\\s*$")
        )

        var changed = true
        var guard = 0
        while (changed && guard++ < 12) {
            changed = false
            for (r in heads) {
                val next = r.replace(t, "")
                if (next != t) { t = next.trim(); changed = true }
            }
            for (r in tails) {
                val next = r.replace(t, "")
                if (next != t) { t = next.trim(); changed = true }
            }
        }
        return t.trim().trim('.', ',', ':', ';', '-', ' ')
    }

    // ------------------------------------------------------------- поисковые запросы

    /** Несколько запросов к поиску: сама тема + уточнения. */
    fun searchQueries(topic: String): List<String> {
        val t = topic.trim()
        if (t.isEmpty()) return emptyList()
        return listOf(t, "$t это простыми словами", "$t факты цифры статистика")
            .distinct()
    }

    // ------------------------------------------------------------------ промпты

    fun deckSystem(): String =
        "Ты — аналитик и автор презентаций. На вход ты получаешь тему и выжимку из интернет-поиска. " +
            "Ты пишешь по-русски, коротко и по делу, без воды, без эмодзи и без markdown-разметки. " +
            "Опираешься только на переданный материал; чего в нём нет — не выдумываешь, а помечаешь " +
            "как «нужно уточнить». Ответ выдаёшь строго в заданном формате."

    fun deckPrompt(topic: String, digest: String, slides: Int): String = buildString {
        append("Тема презентации: «").append(topic).append("».\n")
        append("Сделай презентацию на ").append(slides).append(" слайдов.\n\n")
        append("Формат ответа — ровно такой, без лишних строк:\n")
        append("ЗАГОЛОВОК: <название презентации>\n")
        append("ПОДЗАГОЛОВОК: <одна строка о чём это>\n")
        append("СЛАЙД: <название слайда>\n")
        append("- <пункт, до 12 слов>\n")
        append("- <пункт>\n")
        append("ЗАМЕТКА: <1-2 предложения, что сказать вслух на этом слайде>\n")
        append("(и так каждый слайд)\n")
        append("ИТОГ: <одно предложение — главный вывод>\n\n")
        append("Правила: 3-5 пунктов на слайд; первый слайд — вводный, последний — выводы; ")
        append("конкретика, цифры и даты из материала приветствуются; никакой воды.\n\n")
        append("Материал, найденный в интернете:\n")
        append(digest)
    }

    fun digestSystem(): String =
        "Ты — Джарвис, голосовой ассистент. Ты получаешь выжимку интернет-поиска и пересказываешь " +
            "её вслух: по-русски, 5-7 предложений, без списков, без markdown, без эмодзи. " +
            "Только то, что есть в материале. В конце одной фразой называешь источники."

    fun digestPrompt(topic: String, digest: String): String =
        "Тема: «$topic». Расскажи главное так, чтобы это было понятно на слух.\n\n" +
            "Материал из интернета:\n$digest"

    /** Текст, который вставляется прямо в приложение Claude, когда работаем без ключа. */
    fun claudeAppMessage(topic: String, digest: String, slides: Int, presentation: Boolean): String =
        buildString {
            append("Это Джарвис. Я поискал в интернете по теме «").append(topic).append("».\n")
            if (presentation) {
                append("Задача: собери из этого презентацию на ").append(slides).append(" слайдов — ")
                append("заголовок, 3-5 пунктов и заметку докладчика на каждый слайд, в конце вывод.\n")
            } else {
                append("Задача: разложи материал по полочкам и выдели главное — коротко и по делу.\n")
            }
            append("\n--- ЧТО НАШЁЛ ---\n")
            append(digest)
        }
}
