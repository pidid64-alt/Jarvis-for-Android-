package kz.jarvis.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Календарные вопросы: «сколько дней до 31 декабря», «какой день недели
 * 1 января 2030», «сколько мне дней, если я родился 5 мая 1990»,
 * «сколько дней в феврале», «високосный ли 2028 год», «какая дата через 40 дней».
 *
 * Без Android-зависимостей, текущее время передаётся параметром — поэтому
 * scripts/test-logic.sh проверяет этот код на JVM с фиксированной датой.
 */
object DateFacts {

    private val months = listOf(
        "янв" to 0, "фев" to 1, "мар" to 2, "апр" to 3, "ма" to 4, "июн" to 5,
        "июл" to 6, "авг" to 7, "сен" to 8, "окт" to 9, "ноя" to 10, "дек" to 11
    )

    /** Индекс месяца (0-based) по слову в любом падеже: «мая», «марта», «декабре». */
    fun monthIndex(word: String): Int? {
        val w = word.lowercase(Locale.ROOT).replace('ё', 'е')
        var best = -1
        var bestLen = 0
        for ((stem, idx) in months) {
            if (w.startsWith(stem) && stem.length > bestLen) { best = idx; bestLen = stem.length }
        }
        return if (best < 0) null else best
    }

    /** Ответ на календарный вопрос либо null. [now] — текущее время (для тестов). */
    fun ask(raw: String, now: Long = System.currentTimeMillis()): String? {
        val s = raw.lowercase(Locale.ROOT).replace('ё', 'е').trim()
        if (s.isEmpty()) return null
        val cal = Calendar.getInstance().apply { timeInMillis = now }

        secondsIn(s)?.let { return it }

        // «сколько дней между 1 мая и 20 мая»
        if (Regex("между").containsMatchIn(s) && Regex("дн|суток|недел").containsMatchIn(s)) {
            val parts = splitBetween(s)
            val a = parts.firstOrNull()?.let { parseDate(it, cal.get(Calendar.YEAR)) }
            val b = parts.lastOrNull()?.let { parseDate(it, cal.get(Calendar.YEAR)) }
            if (a != null && b != null && parts.size >= 2) {
                val days = kotlin.math.abs(daysBetween(a, b))
                return "Между этими датами $days дн., сэр."
            }
        }

        // «сколько дней до 31 декабря», «сколько дней до дня рождения 5 мая»
        if (Regex("сколько\\D{0,20}(?:до|осталось)").containsMatchIn(s) ||
            Regex("(?:дней|дн)\\s+до").containsMatchIn(s)
        ) {
            val at = parseDate(s, cal.get(Calendar.YEAR))
            if (at != null) {
                val days = daysToNext(at, now)
                val whenTxt = SimpleDateFormat("d MMMM", Locale("ru")).format(Date(at))
                return if (days == 0L) "Это сегодня, сэр!" else "До $whenTxt — $days дн., сэр."
            }
        }

        // «сколько дней до конца месяца / года / недели»
        Regex("до конца (месяца|года|недели)").find(s)?.let { m ->
            val c = Calendar.getInstance().apply { timeInMillis = now }
            when (m.groupValues[1]) {
                "месяца" -> c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH))
                "года" -> c.set(Calendar.DAY_OF_YEAR, c.getActualMaximum(Calendar.DAY_OF_YEAR))
                else -> c.set(Calendar.DAY_OF_WEEK, c.getActualMaximum(Calendar.DAY_OF_WEEK))
            }
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            val today = startOfDay(now)
            val days = daysBetween(today, c.timeInMillis)
            return "До конца ${m.groupValues[1]} — $days дн., сэр."
        }

        // «сколько мне дней, если я родился 5 мая 1990»
        if (Regex("родил|день рождения|возраст в днях|сколько мне дней|прожил").containsMatchIn(s)) {
            val at = parseDate(s, 1990)
            if (at != null) {
                val days = daysBetween(at, now)
                if (days < 0) return "Эта дата ещё не наступила, сэр."
                val years = days / 365.25
                return "С этой даты прошло $days дн. — примерно ${MathEngine.format(years)} года."
            }
        }

        // «мне 30 лет сколько это дней»
        Regex("(\\d{1,3})\\s*(?:лет|год|года)\\s*(?:сколько|это|в днях)").find(s)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return@let
            val days = (y * 365.25).toLong()
            return "$y лет — это примерно $days дн., сэр."
        }

        // «какая дата будет через 40 дней»
        Regex("(?:како[ей]|какая|что за)\\D{0,20}(?:число|дата|день)\\D{0,15}через\\s+(\\d+)\\s*" +
            "(день|дн[а-яa-z0-9]*|недел[а-яa-z0-9]*|месяц[а-яa-z0-9]*|год|лет)?").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            val c = Calendar.getInstance().apply { timeInMillis = now }
            when {
                m.groupValues[2].startsWith("недел") -> c.add(Calendar.DAY_OF_YEAR, n * 7)
                m.groupValues[2].startsWith("месяц") -> c.add(Calendar.MONTH, n)
                m.groupValues[2] == "год" || m.groupValues[2] == "лет" -> c.add(Calendar.YEAR, n)
                else -> c.add(Calendar.DAY_OF_YEAR, n)
            }
            return "Через $n — ${fmt(c.timeInMillis)}, сэр."
        }

        // «какой день недели 1 января 2030»
        if (Regex("день недели").containsMatchIn(s)) {
            val at = parseDate(s, cal.get(Calendar.YEAR))
            if (at != null) {
                val wd = SimpleDateFormat("EEEE", Locale("ru")).format(Date(at))
                return "${fmtDate(at)} — это $wd, сэр."
            }
        }

        // «какое число завтра / вчера»
        if (Regex("завтра").containsMatchIn(s) && Regex("число|дата|день").containsMatchIn(s)) {
            val c = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, 1) }
            return "Завтра — ${fmt(c.timeInMillis)}, сэр."
        }
        if (Regex("вчера").containsMatchIn(s) && Regex("число|дата|день").containsMatchIn(s)) {
            val c = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
            return "Вчера было ${fmt(c.timeInMillis)}, сэр."
        }

        // «сколько дней в феврале», «сколько дней в мае 2028», «сколько дней в году 2028»
        Regex("сколько дней в (году|месяце|неделе|\\D{3,15})").find(s)?.let { m ->
            val what = m.groupValues[1].trim()
            if (what.startsWith("году") || what.startsWith("год")) {
                val y = Regex("(\\d{4})").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: cal.get(Calendar.YEAR)
                val days = if (isLeap(y)) 366 else 365
                return "В $y году $days дн., сэр."
            }
            if (what.startsWith("неделе")) return "В неделе 7 дн., сэр."
            if (what.startsWith("месяце")) {
                return "В этом месяце ${cal.getActualMaximum(Calendar.DAY_OF_MONTH)} дн., сэр."
            }
            val mi = monthIndex(what.split(" ").first())
            if (mi != null) {
                val y = Regex("(\\d{4})").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: cal.get(Calendar.YEAR)
                val c = Calendar.getInstance().apply { clear(); set(y, mi, 1) }
                return "В ${monthName(mi)} $y года ${c.getActualMaximum(Calendar.DAY_OF_MONTH)} дн., сэр."
            }
        }

        // «високосный ли 2028 год»
        if (Regex("високосн").containsMatchIn(s)) {
            val y = Regex("(\\d{4})").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: cal.get(Calendar.YEAR)
            return if (isLeap(y)) "$y — високосный, в нём 366 дней." else "$y — не високосный, в нём 365 дней."
        }

        // «какая сейчас неделя года»
        if (Regex("недел[а-яa-z0-9]* года|номер недели|какая неделя").containsMatchIn(s)) {
            return "Сейчас ${cal.get(Calendar.WEEK_OF_YEAR)}-я неделя года, сэр."
        }

        return null
    }

    // ------------------------------------------------------------- внутри

    private fun secondsIn(s: String): String? {
        val m = Regex("сколько\\D{0,15}(?:секунд|минут|часов)\\s+в\\s+(секунде|минуте|часе|сутках|дне|неделе|месяце|году)")
            .find(s) ?: return null
        val v = when (m.groupValues[1]) {
            "минуте" -> 60L
            "часе" -> 3600L
            "сутках", "дне" -> 86_400L
            "неделе" -> 604_800L
            "месяце" -> 2_629_800L // средний месяц
            "году" -> 31_557_600L // 365,25 суток
            else -> 1L
        }
        return "В ${m.groupValues[1]} $v секунд, сэр."
    }

    private fun splitBetween(s: String): List<String> {
        val m = Regex("между\\s+(.+?)\\s+и\\s+(.+)$").find(s) ?: return emptyList()
        return listOf(m.groupValues[1], m.groupValues[2])
    }

    /** Названия месяцев в предложном падеже («в феврале») — SimpleDateFormat так не умеет. */
    private val monthNames = listOf(
        "январе", "феврале", "марте", "апреле", "мае", "июне",
        "июле", "августе", "сентябре", "октябре", "ноябре", "декабре"
    )

    private fun monthName(idx: Int): String = monthNames.getOrElse(idx) { "месяце" }

    fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    private fun startOfDay(millis: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Целое число суток между двумя моментами. */
    fun daysBetween(from: Long, to: Long): Long =
        (startOfDay(to) - startOfDay(from)) / 86_400_000L

    /** Дней до ближайшего в будущем вхождения даты (день/месяц из [at]). */
    private fun daysToNext(at: Long, now: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val target = Calendar.getInstance().apply {
            clear()
            set(today.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        }
        if (target.timeInMillis < startOfDay(now)) target.add(Calendar.YEAR, 1)
        return daysBetween(startOfDay(now), target.timeInMillis)
    }

    private fun fmt(millis: Long): String {
        val d = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru")).format(Date(millis))
        return d.replaceFirstChar { it.uppercase(Locale("ru")) }
    }

    private fun fmtDate(millis: Long): String {
        val d = SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(millis))
        return d.replaceFirstChar { it.uppercase(Locale("ru")) }
    }

    /**
     * Разбирает дату из фразы: «5 мая», «5 мая 2030», «05.12», «05.12.2030».
     * Год по умолчанию — [defaultYear].
     */
    fun parseDate(text: String, defaultYear: Int): Long? {
        Regex("(\\d{1,2})\\s*[-–]?\\s*([а-я]+)\\s*(\\d{4})?").findAll(text).forEach { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@forEach
            val mo = monthIndex(m.groupValues[2]) ?: return@forEach
            val y = m.groupValues[3].toIntOrNull() ?: defaultYear
            if (d in 1..31) return at(y, mo, d)
        }
        Regex("(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?").find(text)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let
            val mo = (m.groupValues[2].toIntOrNull() ?: return@let) - 1
            val y = m.groupValues[3].toIntOrNull() ?: defaultYear
            if (d in 1..31 && mo in 0..11) return at(y, mo, d)
        }
        return null
    }

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear(); set(year, month, day, 0, 0, 0)
        }.timeInMillis

    /** Краткая справка по календарным командам. */
    fun help(): String = """
        Календарь и время, сэр:
        • «сколько дней до 31 декабря», «сколько дней до конца месяца»
        • «сколько дней между 1 мая и 20 мая»
        • «какой день недели 1 января 2030», «какая дата через 40 дней»
        • «сколько мне дней, если я родился 5 мая 1990», «мне 30 лет сколько это дней»
        • «сколько дней в феврале», «високосный ли 2028 год», «какая сейчас неделя года»
        • «сколько секунд в сутках», «какое число завтра»
    """.trimIndent()
}
