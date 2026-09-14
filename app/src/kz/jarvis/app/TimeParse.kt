package kz.jarvis.app

/**
 * Разбор времени из живой речи: длительности («через пять минут»),
 * момента дня («на 7:30», «в шесть вечера») и напоминаний.
 *
 * Без Android-зависимостей — покрыт scripts/test-logic.sh.
 */
object TimeParse {

    data class Duration(val seconds: Int, val human: String)
    data class Clock(val hour: Int, val minute: Int, val human: String)

    /** Напоминание: либо через [delayMs], либо в [clock] сегодня/завтра. */
    data class Reminder(
        val delayMs: Long?,
        val clock: Clock?,
        val text: String,
        val human: String
    )

    private const val UNIT_REQ = "(секунд[а-яa-z0-9]*|минут[а-яa-z0-9]*|час[а-яa-z0-9]*)"
    private const val UNIT_OPT = "$UNIT_REQ?"

    private val ONES = linkedMapOf(
        "ноль" to 0,
        "один" to 1, "одна" to 1, "одно" to 1,
        "два" to 2, "две" to 2,
        "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6,
        "семь" to 7, "восемь" to 8, "девять" to 9
    )
    private val TEENS = linkedMapOf(
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12,
        "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15,
        "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
        "девятнадцать" to 19
    )
    private val TENS = linkedMapOf(
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40,
        "пятьдесят" to 50, "шестьдесят" to 60, "семьдесят" to 70,
        "восемьдесят" to 80, "девяносто" to 90
    )

    /** «пять» → 5, «двадцать пять» → 25, «полчаса» → «30 минут». */
    fun foldNumbers(raw: String): String {
        var t = raw.lowercase().replace('ё', 'е')
        t = t.replace(Regex("пол\\s*часа"), "30 минут")
        t = replaceWords(t, TEENS)
        val tensAlt = TENS.keys.joinToString("|")
        val onesAlt = ONES.keys.joinToString("|")
        t = Regex("(?<![а-яa-z0-9])($tensAlt)\\s+($onesAlt)(?![а-яa-z0-9])").replace(t) { m ->
            (TENS.getValue(m.groupValues[1]) + ONES.getValue(m.groupValues[2])).toString()
        }
        t = replaceWords(t, TENS)
        t = replaceWords(t, ONES)
        return t
    }

    /** «через 10 минут», «таймер на 5 минут», «через пять минут», «через час». */
    fun duration(s: String): Duration? {
        val t = foldNumbers(s)
        if (looksLikeCalendarOffset(t) && !Regex("таймер|засек").containsMatchIn(t)) return null

        Regex("через\\s+(\\d+)\\s+$UNIT_REQ").find(t)?.let { return build(it) }
        Regex("через\\s+час(?:а|ов)?(?![а-яa-z])").find(t)?.let {
            return Duration(3600, "1 ч.")
        }
        Regex("(?:таймер[а-яa-z0-9]*|засек[аичь]*)\\s+(?:на\\s+)?(\\d+)\\s*$UNIT_OPT").find(t)
            ?.let { return build(it) }
        Regex("(?:поставь|включи|запусти)\\s+таймер[а-яa-z0-9]*\\s+(?:на\\s+)?(\\d+)\\s*$UNIT_OPT")
            .find(t)?.let { return build(it) }
        return null
    }

    /** «будильник на 7:30», «разбуди меня в 6 вечера», «разбуди в шесть вечера». */
    fun clock(s: String): Clock? {
        val t = foldNumbers(s)
        val m = Regex("(?:будильник|разбуди(?:\\s+меня)?)\\s*(?:на|в)?\\s+(.+)$").find(t) ?: return null
        return clockOf(m.groupValues[1].trim())
    }

    /** Момент дня из хвоста фразы: «18:30», «7 вечера», «семь». */
    fun clockOf(rest: String): Clock? = splitClock(foldNumbers(rest))?.first

    /**
     * «напомни через пять минут выключить духовку»,
     * «напомни в 18:30 позвонить маме», «напомни через час».
     * Текст может быть пустым — тогда всё равно ставим напоминание.
     */
    fun reminder(s: String): Reminder? {
        val folded = foldNumbers(s)
        val m = Regex("(?:напомни|напомнить|напоминание)(?:\\s+мне)?").find(folded) ?: return null
        val rest = folded.substring(m.range.last + 1).trim()
            .removePrefix("пожалуйста").trim()
        if (rest.isEmpty()) return null

        Regex("^через\\s+(\\d+)\\s+$UNIT_REQ\\s*(.*)$").find(rest)?.let { x ->
            val d = build(x) ?: return@let
            return Reminder(d.seconds * 1000L, null, cleanText(x.groupValues[3]), "через ${d.human}")
        }
        Regex("^через\\s+час(?:а|ов)?\\s*(.*)$").find(rest)?.let { x ->
            return Reminder(3_600_000L, null, cleanText(x.groupValues[1]), "через 1 ч.")
        }

        val afterIn = Regex("^в\\s+(.+)$").find(rest)?.groupValues?.get(1) ?: return null
        val split = splitClock(afterIn) ?: return null
        return Reminder(null, split.first, cleanText(split.second), "в ${split.first.human}")
    }

    /** Момент [clock] сегодня, или завтра, если уже прошло. */
    fun atClock(clock: Clock, now: Long): Long {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        cal.set(java.util.Calendar.HOUR_OF_DAY, clock.hour)
        cal.set(java.util.Calendar.MINUTE, clock.minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    // ------------------------------------------------------------- внутри

    private fun looksLikeCalendarOffset(t: String): Boolean =
        Regex("через\\s+\\d+\\s*(?:день|дня|дней|дн[а-яa-z0-9]*|недел[а-яa-z0-9]*|месяц[а-яa-z0-9]*|год|лет)")
            .containsMatchIn(t)

    private fun replaceWords(src: String, map: Map<String, Int>): String {
        var t = src
        for ((word, n) in map) {
            t = t.replace(Regex("(?<![а-яa-z0-9])${Regex.escape(word)}(?![а-яa-z0-9])"), n.toString())
        }
        return t
    }

    private fun splitClock(raw: String): Pair<Clock, String>? {
        val s = raw.trim().removePrefix("на ").trim()
        val pmHint = Regex("вечера|\\bдня\\b").containsMatchIn(s)

        Regex("^(\\d{1,2})[:. ](\\d{2})\\s*(.*)$").find(s)?.let { m ->
            var h = m.groupValues[1].toIntOrNull() ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: return@let
            if (h in 1..11 && pmHint) h += 12
            if (h !in 0..23 || min !in 0..59) return@let
            val text = stripPeriod(m.groupValues[3])
            return Clock(h, min, "%02d:%02d".format(h, min)) to text
        }

        Regex("^(\\d{1,2})(?:\\s*(?:час|часа|часов))?(?:\\s*(вечера|дня|утра|ночи))?\\s*(.*)$")
            .find(s)?.let { m ->
                var h = m.groupValues[1].toIntOrNull() ?: return@let
                val period = m.groupValues[2]
                val text = stripPeriod(m.groupValues[3])
                if (h in 1..11 && (period == "вечера" || period == "дня" || (period.isEmpty() && pmHint))) {
                    h += 12
                }
                if (h == 12 && (period == "ночи" || period == "утра")) h = 0
                if (h !in 0..23) return@let
                return Clock(h, 0, "%02d:00".format(h)) to text
            }
        return null
    }

    private fun stripPeriod(t: String): String =
        t.trim().replace(Regex("^(?:час[а-я]*\\s*)?(?:вечера|утра|ночи|дня)\\s*"), "").trim()

    private fun cleanText(t: String): String =
        t.trim().trim(',', '.', '—', '-', ':')
            .removePrefix("что ").removePrefix("чтобы ").trim()

    private fun build(m: MatchResult): Duration? {
        val n = m.groupValues[1].toIntOrNull() ?: return null
        val unit = m.groupValues.getOrNull(2).orEmpty()
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
