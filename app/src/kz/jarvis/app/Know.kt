package kz.jarvis.app

import java.util.Locale

/**
 * Когда Джарвис не знает факт — он сам уточняет в интернете и отвечает,
 * не говоря «сейчас поищу» и не открывая браузер.
 *
 * Файл не зависит от Android — покрыт scripts/test-logic.sh.
 */
object Know {

    const val SILENT_SYSTEM: String =
        "Ты — Джарвис, голосовой ассистент. Ответь по-русски кратко, 1–3 предложения, " +
            "как будто сам это знаешь. Не упоминай поиск, интернет, источники, Википедию, " +
            "«нашёл», «погуглил», «сейчас посмотрю». Без списков, без markdown, без эмодзи."

    fun silentPrompt(topic: String, digest: String): String =
        "Вопрос или тема: «$topic». По материалу ниже ответь коротко, по делу.\n\n$digest"

    /**
     * Явный запрос «что такое X» / «кто такой Y» — сразу искать, не спрашивая модель.
     * null — это не фактический вопрос такого вида.
     */
    fun directQuery(raw: String): String? {
        val t = raw.trim().lowercase(Locale.ROOT).replace('ё', 'е')
            .trim('.', '!', '?', ' ')
        if (t.isEmpty()) return null
        if (Regex(
                "что ты умеешь|что ты можешь|что такое автоответ|" +
                    "что такое непрерывн|что такое шумоподав"
            ).containsMatchIn(t)
        ) return null

        val patterns = listOf(
            Regex("^(?:скажи|расскажи|объясни)?\\s*(?:мне\\s+)?что такое\\s+(.+)$"),
            Regex("^(?:скажи|расскажи)?\\s*(?:мне\\s+)?кто так(?:ой|ая|ое)\\s+(.+)$"),
            Regex("^(?:скажи)?\\s*что значит\\s+(.+)$"),
            Regex("^(?:скажи)?\\s*что означает\\s+(.+)$"),
            Regex("^что это такое\\s+(.+)$"),
            Regex("^объясни(?:\\s+мне)?\\s+(?:что такое\\s+)?(.+)$")
        )
        for (r in patterns) {
            val m = r.find(t) ?: continue
            val q = m.groupValues[1].trim().trim('.', ',', ':', ' ', '-')
            if (q.length >= 2) return q
        }
        return null
    }

    /** Ответ модели звучит как «я не знаю». */
    fun looksUnknown(answer: String): Boolean {
        val a = Emotion.visible(answer).lowercase(Locale.ROOT).replace('ё', 'е')
        if (a.length < 8) return false
        return Regex(
            "не знаю|нет (у меня )?данных|не могу сказать|затрудняюсь|" +
                "у меня нет информации|моя база знаний|не владею (информац|данн)|" +
                "не в курсе|не располагаю|не могу ответить точно|" +
                "нужно (погуглить|поискать|проверить в интернете)|" +
                "я не поисковик|как языковая модель|как ии[- ]?модель|" +
                "у меня нет доступа (в интернет|к интернету|к актуальн)|" +
                "не могу проверить|это вне моих данных|не обучен (на|после)|" +
                "к сожалению,? я не (знаю|могу)|мне это неизвестно"
        ).containsMatchIn(a)
    }

    fun hasSearchTag(answer: String): Boolean =
        Regex("^\\s*\\[поиск", RegexOption.IGNORE_CASE).containsMatchIn(answer)

    /** `[поиск]` или `[поиск: запрос]` в начале ответа модели. */
    fun parseSearchTag(answer: String): String? {
        val m = Regex(
            "^\\s*\\[поиск(?::\\s*([^\\]]+))?\\]",
            RegexOption.IGNORE_CASE
        ).find(answer) ?: return null
        return m.groupValues[1].trim()
    }

    fun stripMeta(answer: String): String =
        answer.replace(
            Regex("^\\s*\\[поиск(?::[^\\]]*)?\\]\\s*", RegexOption.IGNORE_CASE),
            ""
        ).trim()

    fun queryFrom(question: String): String {
        val t = question.trim()
        return (directQuery(t) ?: t).trim('.', '!', '?', ' ')
    }

    fun worthSearching(question: String): Boolean {
        val t = question.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        if (t.length < 4) return false
        return !Regex(
            "^(привет|пока|спасибо|благодарю|как дела|молодец|отлично|" +
                "хорошо|стоп|тихо|отмена)$"
        ).matches(t)
    }

    /**
     * Нужно ли после ответа модели самому сходить в интернет.
     * Возвращает поисковый запрос или null.
     */
    fun followUpQuery(question: String, answer: String): String? {
        if (!worthSearching(question)) return null
        if (hasSearchTag(answer)) {
            val tagged = parseSearchTag(answer)?.takeIf { it.length >= 2 }
            return (tagged ?: queryFrom(question)).takeIf { it.length >= 2 }
        }
        if (looksUnknown(answer)) return queryFrom(question).takeIf { it.length >= 2 }
        return null
    }

    /** Короткий ответ голосом из выдачи, без «я нашёл в интернете». */
    fun spokenFromHits(hits: List<WebSearch.Hit>): String {
        val best = hits.firstOrNull() ?: return "Пока не могу это уточнить, сэр."
        val text = best.snippet.ifBlank { best.title }
        val short = if (text.length > 420) text.take(420).substringBeforeLast(' ') + "." else text
        return short.trim().ifBlank { "Пока не могу это уточнить, сэр." }
    }
}
