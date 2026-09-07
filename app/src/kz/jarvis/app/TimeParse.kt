package kz.jarvis.app

/**
 * Разбор времени из живой речи: длительности («через 10 минут») и
 * момента дня («на 7:30», «в 6 вечера»).
 *
 * Без Android-зависимостей — покрыт scripts/test-logic.sh.
 */
object TimeParse {

    data class Duration(val seconds: Int, val human: String)
    data class Clock(val hour: Int, val minute: Int, val human: String)

    private const val UNIT = "(секунд\\w*|минут\\w*|час\\w*)?"

    /** «через 10 минут», «таймер на 5 минут», «напомни через 2 часа». */
    fun duration(s: String): Duration? {
        Regex("через\\s+(\\d+)\\s*$UNIT").find(s)?.let { return build(it) }
        Regex("таймер\\w*\\s+на\\s+(\\d+)\\s*$UNIT").find(s)?.let { return build(it) }
        return null
    }

    /** «будильник на 7:30», «разбуди меня в 6 вечера». */
    fun clock(s: String): Clock? {
        val m = Regex("(?:будильник|разбуди(?:\\s+меня)?)\\s*(?:на|в)?\\s+(.+)$").find(s) ?: return null
        val rest = m.groupValues[1].trim()
        val pm = Regex("вечера|дня|по вечерам").containsMatchIn(rest)
        val t = Regex("(\\d{1,2})[:.\\s]+(\\d{2})").find(rest)
            ?: Regex("(\\d{1,2})\\s*(?:час|часа|часов)?\\s*(\\d{1,2})?\\s*минут?").find(rest)
            ?: Regex("^(\\d{1,2})$").find(rest)
            // «в 6 вечера», «на 3 дня», «в 11 часов»
            ?: Regex("^(\\d{1,2})(?:\\s*(?:час|часа|часов))?(?:\\s*(?:вечера|дня|утра|ночи))?$").find(rest)
            ?: return null
        var h = t.groupValues[1].toIntOrNull() ?: return null
        // в последнем шаблоне («будильник на 7») группы минут нет вовсе
        val min = t.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        if (h in 1..11 && pm) h += 12
        if (h !in 0..23 || min !in 0..59) return null
        return Clock(h, min, "%02d:%02d".format(h, min))
    }

    private fun build(m: MatchResult): Duration? {
        val n = m.groupValues[1].toIntOrNull() ?: return null
        val unit = m.groupValues[2]
        val secs = when {
            unit.startsWith("секунд") -> n
            unit.startsWith("час") -> n * 3600
            else -> n * 60
        }
        if (secs <= 0) return null
        val human = when {
            secs < 60 -> "$secs сек."
            secs % 3600 == 0 -> "${secs / 3600} ч."
            else -> "${secs / 60} мин."
        }
        return Duration(secs, human)
    }
}
