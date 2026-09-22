package kz.jarvis.app

import java.util.Calendar
import java.util.Locale

/**
 * Погода «наперёд»: чистый разбор фразы и человекочитаемый текст прогноза.
 *
 * Класс не зависит от Android (данные тянет [Weather.forecast]), поэтому его
 * покрывает scripts/test-logic.sh на обычной JVM.
 *
 * Понимает:
 *  • «какая погода будет завтра», «прогноз на послезавтра»;
 *  • «будет ли дождь вечером», «что с погодой в субботу»;
 *  • «прогноз на неделю», «погода на три дня», «что будет через 4 дня».
 */
object Forecast {

    /** Часть суток — для «будет ли дождь вечером». */
    enum class Part(val label: String) {
        MORNING("утром"),
        DAY("днём"),
        EVENING("вечером"),
        NIGHT("ночью")
    }

    /**
     * @param offset сколько дней от сегодняшнего (0 — сегодня, 1 — завтра)
     * @param days   сколько дней прогноза показать
     * @param city   город, если назван («погода в Сочи завтра»)
     * @param part   часть суток, если спрошена («вечером»)
     */
    data class Query(val offset: Int, val days: Int, val city: String?, val part: Part?) {
        /** Сколько суток данных нужно запросить у сервера. */
        val totalDays: Int get() = (offset + days).coerceIn(1, 14)
    }

    /** Прогноз на одни сутки. */
    data class DayInfo(
        val tMin: Double,
        val tMax: Double,
        val code: Int,
        val precipProb: Int,
        val precipMm: Double,
        val wind: Double
    )

    /** Прогноз на часть суток (когда спросили «вечером»). */
    data class PartInfo(val t: Double, val code: Int, val precipProb: Int, val wind: Double)

    /** День прогноза: дата + сутки + (если спрошено) часть суток. */
    data class Day(val dateIso: String, val info: DayInfo, val part: PartInfo?)

    // ------------------------------------------------------------------ разбор

    private val WEATHERISH = Regex(
        "погод|прогноз|температур|градус|дожд|снег|ветер|туч|ясно|облач|пасмурн|" +
            "гроз|тепло|холод|мороз|жар|слякот|гололед|гололедиц|туман"
    )

    /** Признаки будущего: без них это запрос «какая погода сейчас». */
    private val FUTUREISH = Regex("прогноз|будет|ожидается|обещают|предскажи|прогнозиру")

    /** Слова, которые звучат как «город», но городом не являются. */
    private val NOT_CITY = setOf(
        "завтра", "послезавтра", "сегодня", "сейчас", "вечером", "вечер", "утром", "утро",
        "ночью", "ночь", "днем", "день", "дня", "дней", "неделю", "неделя", "недели",
        "выходные", "выходных", "субботу", "суббота", "воскресенье", "понедельник",
        "вторник", "среду", "среда", "четверг", "пятницу", "пятница", "будущем",
        "будущую", "следующей", "следующую", "этой", "этом", "ближайшие", "ближайшее",
        "месяц", "часа", "часов", "минут"
    )

    /** Хвост, который отсекаем от названия города: «в сочи завтра» → «сочи». */
    private val CITY_STOP = Regex(
        "\\s+(?:завтра|послезавтра|сегодня|сейчас|вечером|утром|ночью|днем|будет|" +
            "прогноз|ожидается|обещают|неделю|недели|дней|дня|через|в|на|по|градусов|" +
            "температура|ветер|дождь|снег)\\b"
    )

    private val WEEKDAYS = listOf(
        "воскресенье" to Calendar.SUNDAY,
        "понедельник" to Calendar.MONDAY,
        "вторник" to Calendar.TUESDAY,
        "среду" to Calendar.WEDNESDAY,
        "среды" to Calendar.WEDNESDAY,
        "четверг" to Calendar.THURSDAY,
        "пятницу" to Calendar.FRIDAY,
        "пятницы" to Calendar.FRIDAY,
        "субботу" to Calendar.SATURDAY,
        "субботы" to Calendar.SATURDAY
    )

    /** Числительные, которыми говорят дни: «на три дня». */
    private val NUM_WORDS = mapOf(
        "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "неделю" to 7, "неделя" to 7, "пару" to 2
    )

    /**
     * Разбирает фразу про будущую погоду. Возвращает null, если запрос про
     * погоду «сейчас» — его обрабатывает обычный ответ [Weather.fetch].
     */
    fun parse(raw: String, now: Long = System.currentTimeMillis()): Query? {
        val s = raw.lowercase(Locale.ROOT).replace('ё', 'е').trim()
        if (s.isEmpty()) return null
        val aboutWeather = WEATHERISH.containsMatchIn(s)
        if (!aboutWeather && !Regex("прогноз|будет ли|ожидается").containsMatchIn(s)) return null

        val part = part(s)
        val offset = offsetDays(s, now)
        val span = spanDays(s)

        // ни одного признака будущего — это «погода сейчас»
        if (offset == null && span == null && part == null && !FUTUREISH.containsMatchIn(s)) return null

        val today = Regex("\\bсегодня\\b").containsMatchIn(s)
        val off = (offset ?: 0).coerceIn(0, 13)
        val days = (span ?: when {
            offset != null -> 1
            today -> 1
            part != null -> 1        // «вечером» — остаток сегодняшнего дня
            else -> 3
        }).coerceIn(1, 10)
        return Query(offset = off, days = days, city = city(s), part = part)
    }

    /** Утро/день/вечер/ночь — если спрошено про часть суток. */
    fun part(s: String): Part? = when {
        Regex("(?:сегодня |завтра )?(?:утром|с утра|под утро)").containsMatchIn(s) -> Part.MORNING
        Regex("(?:сегодня |завтра )?(?:вечером|к вечеру|под вечер)").containsMatchIn(s) -> Part.EVENING
        Regex("(?:сегодня |завтра )?(?:ночью|в ночь|под ночь)").containsMatchIn(s) -> Part.NIGHT
        Regex("(?:сегодня |завтра )?(?:днем|в середине дня|после обеда)").containsMatchIn(s) -> Part.DAY
        else -> null
    }

    /** Сколько дней от сегодняшнего: «завтра» — 1, «в субботу» — до ближайшей субботы. */
    fun offsetDays(s: String, now: Long = System.currentTimeMillis()): Int? {
        if (Regex("послезавтра|после завтра").containsMatchIn(s)) return 2
        if (Regex("\\bзавтра\\b|на завтра|завтрашн").containsMatchIn(s)) return 1
        Regex("через\\s+(\\d+|два|три|четыре|пять)\\s*(?:день|дня|дней|дн)").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: NUM_WORDS[m.groupValues[1]]
            if (n != null && n in 1..13) return n
        }
        if (Regex("на следующей неделе|на будущую неделю|следующая неделя").containsMatchIn(s)) {
            return daysUntil(now, Calendar.MONDAY) + 7
        }
        if (Regex("на выходных|в выходные|на выходные|эти выходные").containsMatchIn(s)) {
            return daysUntil(now, Calendar.SATURDAY)
        }
        for ((name, cal) in WEEKDAYS) {
            if (Regex("(?:^|\\s)(?:в |на |до )?$name").containsMatchIn(s)) {
                val d = daysUntil(now, cal)
                return if (d == 0) 7 else d   // день уже прошёл — берём следующий
            }
        }
        return null
    }

    /** Сколько суток показать: «на неделю» — 7, «на три дня» — 3. */
    fun spanDays(s: String): Int? {
        Regex("на\\s+(\\d+|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|неделю|пару)\\s*" +
            "(?:дн|день|дня|дней|недел|недели|неделю|суток)?").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: NUM_WORDS[m.groupValues[1]]
            if (n != null && n in 1..10) return n
        }
        if (Regex("недельн|на неделю|вся неделя|всю неделю").containsMatchIn(s)) return 7
        if (Regex("на выходные|в выходные|на выходные").containsMatchIn(s)) return 2
        return null
    }

    /** Предлоги, после которых может начаться название города. */
    private val PREPS = setOf("в", "во", "на", "для", "по", "к", "у", "из", "до")

    /**
     * Город из «погода в Сочи завтра»; null — не назван.
     * Идём по словам: «на неделю в Астане» — первое «на» ведёт на слово
     * «неделю» (это не город), и берём второе — «в Астане».
     */
    fun city(s: String): String? {
        val toks = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var i = 0
        while (i < toks.size) {
            if (!PREPS.contains(toks[i])) {
                i++
                continue
            }
            var j = i + 1
            val buf = ArrayList<String>()
            while (j < toks.size && !PREPS.contains(toks[j])) {
                buf.add(toks[j])
                j++
            }
            if (buf.isNotEmpty() && buf[0].firstOrNull()?.isLetter() == true && !isTimeWord(buf[0])) {
                val name = StringBuilder(buf[0])
                for (k in 1 until buf.size) {
                    if (isTimeWord(buf[k])) break
                    name.append(' ').append(buf[k])
                }
                val res = name.toString().trim()
                if (res.length >= 3) return res
            }
            i = j
        }
        return null
    }

    private fun isTimeWord(w: String): Boolean =
        NOT_CITY.contains(w.lowercase(Locale.ROOT).replace('ё', 'е').trim('.', ',', '!'))

    /** Дней от [now] до ближайшего [weekday] (сегодня — 0). */
    fun daysUntil(now: Long, weekday: Int): Int {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val today = c.get(Calendar.DAY_OF_WEEK)
        var d = weekday - today
        if (d < 0) d += 7
        return d
    }

    // ------------------------------------------------------------------ текст

    /** Дни недели в том падеже, в каком их говорят: «в субботу», «во вторник». */
    private val ACCUSATIVE = mapOf(
        Calendar.MONDAY to "в понедельник",
        Calendar.TUESDAY to "во вторник",
        Calendar.WEDNESDAY to "в среду",
        Calendar.THURSDAY to "в четверг",
        Calendar.FRIDAY to "в пятницу",
        Calendar.SATURDAY to "в субботу",
        Calendar.SUNDAY to "в воскресенье"
    )

    /** «сегодня», «завтра», «послезавтра», «в субботу», «через 9 дней». */
    fun labelFor(offset: Int, now: Long = System.currentTimeMillis()): String {
        if (offset <= 0) return "сегодня"
        if (offset == 1) return "завтра"
        if (offset == 2) return "послезавтра"
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, offset)
        }
        if (offset <= 7) {
            ACCUSATIVE[c.get(Calendar.DAY_OF_WEEK)]?.let { return it }
        }
        return "через ${pluralDays(offset)}"
    }

    fun pluralDays(n: Int): String = TextTools.plural(n.toLong(), "день", "дня", "дней").let { "$n $it" }

    /** Часы суток для части дня: «вечером» → 17..22. */
    fun hours(part: Part): IntRange = when (part) {
        Part.MORNING -> 5..10
        Part.DAY -> 11..16
        Part.EVENING -> 17..22
        Part.NIGHT -> 0..4
    }

    /** Код погоды WMO (open-meteo) по-русски. */
    fun describe(code: Int): String = when (code) {
        0 -> "ясно"
        1 -> "почти ясно"
        2 -> "переменная облачность"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51 -> "слабая морось"
        53 -> "морось"
        55 -> "сильная морось"
        56, 57 -> "ледяная морось"
        61 -> "небольшой дождь"
        63 -> "дождь"
        65 -> "сильный дождь"
        66, 67 -> "ледяной дождь"
        71 -> "небольшой снег"
        73 -> "снег"
        75 -> "сильный снег"
        77 -> "снежные зёрна"
        80 -> "небольшой ливень"
        81 -> "ливень"
        82 -> "сильный ливень"
        85 -> "снежный ливень"
        86 -> "сильный снежный ливень"
        95 -> "гроза"
        96, 99 -> "гроза с градом"
        else -> "облачно"
    }

    /** Совет по погоде: «возьмите зонт», «оденьтесь теплее». */
    fun wetAdvice(code: Int, precipProb: Int): String? = when {
        code in listOf(95, 96, 99) -> "ожидается гроза — лучше остаться под крышей"
        precipProb >= 70 -> "возьмите зонт"
        code in listOf(61, 63, 65, 80, 81, 82) && precipProb >= 40 -> "возможен дождь, зонт не помешает"
        code in listOf(56, 57, 66, 67) -> "осторожно, гололёд"
        code in listOf(71, 73, 75, 77, 85, 86) -> "оденьтесь теплее, будет снег"
        else -> null
    }

    /** Температура со знаком, как её говорят вслух: «+18», «−4». */
    fun temp(v: Double): String {
        val r = Math.round(v).toInt()
        return if (r > 0) "+$r" else if (r < 0) "−${-r}" else "0"
    }

    /**
     * Собирает ответ голосом: одна фраза — на день, список — на несколько дней.
     * @param days пары «смещение от сегодня» → данные дня
     */
    fun speak(place: String, days: List<Pair<Int, Day>>, part: Part?): String {
        if (days.isEmpty()) return "Прогноз не пришёл, сэр."
        val where = if (place.isBlank()) "" else " в $place"
        if (days.size == 1) {
            val (off, d) = days[0]
            val whenText = labelFor(off)
            val p = if (part != null) d.part else null
            return if (part != null && p != null) {
                "$whenText ${part.label}$where ${temp(p.t)} градусов, ${describe(p.code)}, " +
                    "вероятность осадков ${p.precipProb} процентов, ветер ${Math.round(p.wind).toInt()} км/ч." +
                    advice(part, d)
            } else {
                "$whenText$where от ${temp(d.info.tMin)} до ${temp(d.info.tMax)}, " +
                    "${describe(d.info.code)}, вероятность осадков ${d.info.precipProb} процентов, " +
                    "ветер ${Math.round(d.info.wind).toInt()} км/ч." + advice(null, d)
            }
        }
        val head = "Прогноз$where на ${pluralDays(days.size)}:"
        val body = days.joinToString("; ") { (off, d) ->
            "${labelFor(off)} — ${temp(d.info.tMin)}…${temp(d.info.tMax)}, ${describe(d.info.code)}" +
                (if (d.info.precipProb >= 30) ", осадки ${d.info.precipProb} процентов" else "")
        }
        val worst = days.maxByOrNull { it.second.info.precipProb }
        val tail = when {
            worst != null && worst.second.info.precipProb >= 60 ->
                " Зонт пригодится ${labelFor(worst.first)}."
            days.any { it.second.info.code in listOf(95, 96, 99) } -> " Ожидается гроза — учитывайте."
            else -> ""
        }
        return "$head $body.$tail"
    }

    private fun advice(part: Part?, d: Day): String {
        val p = if (part != null) d.part else null
        val code = p?.code ?: d.info.code
        val prob = p?.precipProb ?: d.info.precipProb
        val tip = wetAdvice(code, prob) ?: return ""
        return " ${tip.replaceFirstChar { it.uppercase(Locale.getDefault()) }}."
    }
}
