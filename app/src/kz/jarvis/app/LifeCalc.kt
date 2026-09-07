package kz.jarvis.app

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Бытовые калькуляторы, которые не покрыты MathEngine: ИМТ, чаевые, скидка,
 * деление счёта, площадь комнаты и круга, среднее, НОД/НОК, факториал,
 * римские числа, знак зодиака, норма воды.
 *
 * Без Android-зависимостей — покрыт scripts/test-logic.sh.
 */
object LifeCalc {

    /** Возвращает готовый ответ либо null, если фраза не про это. */
    fun ask(raw: String): String? {
        val s = raw.lowercase(Locale.ROOT).replace('ё', 'е').trim()
        if (s.isEmpty()) return null
        return bmi(s) ?: tips(s) ?: discount(s) ?: split(s) ?: geometry(s) ?:
            average(s) ?: factorial(s) ?: gcdLcm(s) ?: roman(s) ?:
            percentDiff(s) ?: zodiac(s) ?: water(s)
    }

    private fun nums(s: String): List<Double> =
        Regex("\\d+(?:[.,]\\d+)?").findAll(s).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toList()

    private fun f(v: Double) = MathEngine.format(v)

    // --------------------------------------------------------------- ИМТ

    private fun bmi(s: String): String? {
        val byKeyword = Regex("(?:имт|индекс массы тела|индекс массы|идеальный вес)").containsMatchIn(s) ||
            (Regex("(?:^|\\s)вес[а-яa-z0-9]*").containsMatchIn(s) && Regex("(?:^|\\s)рост[а-яa-z0-9]*").containsMatchIn(s))
        if (!byKeyword) return null
        val all = nums(s)
        if (all.size < 2) return "Скажите вес и рост, например: «мой вес 80 рост 180», сэр."
        var kg: Double? = null
        var cm: Double? = null
        Regex("вес\\D{0,10}(\\d+(?:[.,]\\d+)?)").find(s)?.let { kg = it.groupValues[1].replace(',', '.').toDoubleOrNull() }
        Regex("рост\\D{0,10}(\\d+(?:[.,]\\d+)?)").find(s)?.let { cm = it.groupValues[1].replace(',', '.').toDoubleOrNull() }
        if (kg == null || cm == null) {
            // «имт 80 180»: первое число — вес, второе — рост
            kg = all[0]; cm = all[1]
        }
        if (kg !in 20.0..400.0 || cm !in 90.0..260.0) return "Проверьте вес и рост, сэр: не похоже на правду."
        val m = cm / 100.0
        val v = kg / (m * m)
        val cat = when {
            v < 16 -> "выраженный дефицит веса"
            v < 18.5 -> "небольшой дефицит веса"
            v < 25 -> "норма"
            v < 30 -> "избыточный вес"
            v < 35 -> "ожирение первой степени"
            v < 40 -> "ожирение второй степени"
            else -> "ожирение третьей степени"
        }
        val lo = f(18.5 * m * m)
        val hi = f(24.9 * m * m)
        return "ИМТ ${f(v)} — это $cat. Нормальный вес для вашего роста: $lo–$hi кг."
    }

    // ------------------------------------------------------------ чаевые

    private fun tips(s: String): String? {
        if (!Regex("чаев|на чай").containsMatchIn(s)) return null
        val all = nums(s)
        if (all.isEmpty()) return "Скажите сумму счёта, сэр."
        val pct = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:процент[а-яa-z0-9]*|%)").find(s)
            ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 10.0
        val sum = all.firstOrNull { abs(it - pct) > 0.0001 } ?: all.last()
        val tip = sum * pct / 100.0
        return "Чаевые ${f(pct)}% от ${f(sum)} — это ${f(tip)}. Итого ${f(sum + tip)}."
    }

    // ------------------------------------------------------------ скидка

    private fun discount(s: String): String? {
        if (!Regex("скидк|наценк").containsMatchIn(s)) return null
        val up = Regex("наценк").containsMatchIn(s)
        val all = nums(s)
        if (all.isEmpty()) return "Скажите цену, сэр."
        val pct = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:процент[а-яa-z0-9]*|%)").find(s)
            ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 10.0
        val price = all.firstOrNull { abs(it - pct) > 0.0001 } ?: all.last()
        val diff = price * pct / 100.0
        val res = if (up) price + diff else price - diff
        return if (up) {
            "С наценкой ${f(pct)}% — ${f(res)} (плюс ${f(diff)})."
        } else {
            "Со скидкой ${f(pct)}% — ${f(res)}, экономия ${f(diff)}."
        }
    }

    // --------------------------------------------------- деление счёта

    private val wordNumbers = linkedMapOf(
        "двое" to 2, "двоих" to 2, "двум" to 2, "двоим" to 2, "два" to 2,
        "трое" to 3, "троих" to 3, "трем" to 3, "троим" to 3, "три" to 3,
        "четверо" to 4, "четверых" to 4, "четверым" to 4, "четыре" to 4,
        "пятеро" to 5, "пятерых" to 5, "пятерым" to 5, "пять" to 5,
        "шестеро" to 6, "шестерых" to 6, "шесть" to 6,
        "семеро" to 7, "семерых" to 7, "семь" to 7,
        "восьмеро" to 8, "восьмерых" to 8, "восемь" to 8,
        "девятеро" to 9, "девятерых" to 9, "девять" to 9,
        "десятеро" to 10, "десятерых" to 10, "десять" to 10
    )

    private fun split(s: String): String? {
        if (!Regex("раздел|подел|делим|скинул|поровну|на каждого").containsMatchIn(s)) return null
        val all = nums(s)
        if (all.isEmpty()) return null
        val words = wordNumbers.keys.sortedByDescending { it.length }.joinToString("|")
        val m = Regex("на\\s+(\\d+|$words)").find(s) ?: return null
        val word = m.groupValues[1]
        val digit = word.toIntOrNull()
        val people = digit ?: wordNumbers.entries.firstOrNull { word.startsWith(it.key) }?.value ?: return null
        // «раздели 100 на 4» — это арифметика, а не счёт в кафе: нужны люди
        val hasPeople = digit == null ||
            Regex("человек[а-яa-z0-9]*|персон[а-яa-z0-9]*|гост[а-яa-z0-9]*|лиц|нас|друз[а-яa-z0-9]*|компани[а-яa-z0-9]*|порций[а-яa-z0-9]*").containsMatchIn(s)
        if (!hasPeople) return null
        if (people <= 0 || people > 100) return null
        val total = all.first()
        val each = total / people
        return "На каждого из $people — ${f(each)}."
    }

    // -------------------------------------------------------- геометрия

    private fun geometry(s: String): String? {
        val all = nums(s)
        if (all.isEmpty()) return null

        if (Regex("площадь круга|круга по радиусу|круг по радиусу").containsMatchIn(s)) {
            val r = radius(s, all) ?: return null
            return "Площадь круга — ${f(PI * r * r)} (радиус ${f(r)})."
        }
        if (Regex("длина окружности|окружность|периметр круга").containsMatchIn(s)) {
            val r = radius(s, all) ?: return null
            return "Длина окружности — ${f(2 * PI * r)} (радиус ${f(r)})."
        }
        if (Regex("объем шара|обьём шара|обьем шара").containsMatchIn(s)) {
            val r = radius(s, all) ?: return null
            return "Объём шара — ${f(4.0 / 3.0 * PI * r * r * r)} (радиус ${f(r)})."
        }
        Regex("(?:площадь|квадратура|размер)\\s+(?:комнат[а-яa-z0-9]*|пол[а-яa-z0-9]*|стен[а-яa-z0-9]*|квартир[а-яa-z0-9]*|участк[а-яa-z0-9]*|помещени[а-яa-z0-9]*)?\\s*" +
            "(\\d+(?:[.,]\\d+)?)\\s*(?:на|х|x|\\*)\\s*(\\d+(?:[.,]\\d+)?)").find(s)?.let { m ->
            val a = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            val b = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return@let
            val area = a * b
            val per = 2 * (a + b)
            return "Площадь — ${f(area)} м², периметр — ${f(per)} м."
        }
        return null
    }

    private fun radius(s: String, all: List<Double>): Double? {
        Regex("диаметр\\D{0,6}(\\d+(?:[.,]\\d+)?)").find(s)?.let {
            val d = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return d / 2.0
        }
        Regex("радиус\\D{0,6}(\\d+(?:[.,]\\d+)?)").find(s)?.let {
            return it.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        return all.firstOrNull()
    }

    // ----------------------------------------------------------- среднее

    private fun average(s: String): String? {
        if (!Regex("средн(?:ее|яя|ю)").containsMatchIn(s)) return null
        val all = nums(s)
        if (all.size < 2) return "Назовите хотя бы два числа, сэр."
        val avg = all.sum() / all.size
        return "Среднее из ${all.size} чисел — ${f(avg)}."
    }

    // --------------------------------------------------------- факториал

    private fun factorial(s: String): String? {
        val m = Regex("факториал[а-яa-z0-9]*\\s*(?:числа\\s+)?(\\d+)").find(s) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        if (n > 170) return "Слишком большое число для факториала, сэр."
        var r = 1.0
        for (i in 2..n) r *= i
        return "Факториал $n — ${f(r)}."
    }

    // ---------------------------------------------------------- НОД / НОК

    private fun gcdLcm(s: String): String? {
        Regex("нод[а-яa-z0-9]*\\s*(?:чисел\\s+)?(\\d+)\\D{0,6}(\\d+)").find(s)?.let { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return@let
            val b = m.groupValues[2].toLongOrNull() ?: return@let
            if (a <= 0 || b <= 0) return@let
            return "НОД($a, $b) = ${gcd(a, b)}."
        }
        Regex("нок[а-яa-z0-9]*\\s*(?:чисел\\s+)?(\\d+)\\D{0,6}(\\d+)").find(s)?.let { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return@let
            val b = m.groupValues[2].toLongOrNull() ?: return@let
            if (a <= 0 || b <= 0) return@let
            return "НОК($a, $b) = ${a / gcd(a, b) * b}."
        }
        return null
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    // ------------------------------------------------------ римские числа

    private val romans = listOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
        50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
    )

    private fun roman(s: String): String? {
        Regex("(\\d{1,4})\\s*(?:римскими|римское|римским|римская)").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            if (n < 1) return@let
            return "$n римскими — ${toRoman(n)}."
        }
        Regex("([ivxlcdm]{2,15})").findAll(s).forEach { m ->
            val tok = m.groupValues[1]
            val mentioned = Regex("римск").containsMatchIn(s)
            // не превращаем в число обычные слова: рядом не должно быть других
            // латинских слов, цифр и «математических» глаголов
            val otherLatin = s.split(Regex("\\s+"))
                .any { it != tok && it.any { c -> c in 'a'..'z' } }
            val alone = !otherLatin && !s.any { it.isDigit() } &&
                !Regex("посчита|умножь|раздели|прибавь|отними").containsMatchIn(s)
            if (!alone && !mentioned) return@forEach
            val v = fromRoman(tok) ?: return@forEach
            return "${tok.uppercase(Locale.ROOT)} — это $v."
        }
        return null
    }

    fun toRoman(n: Int): String {
        var x = n
        val sb = StringBuilder()
        for ((v, sym) in romans) while (x >= v) { sb.append(sym); x -= v }
        return sb.toString()
    }

    fun fromRoman(text: String): Int? {
        val t = text.uppercase(Locale.ROOT)
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var prev = 0
        for (c in t.reversed()) {
            val v = map[c] ?: return null
            total += if (v < prev) -v else v
            if (v > prev) prev = v
        }
        return if (total <= 0) null else total
    }

    // ------------------------------------------------- на сколько процентов

    private fun percentDiff(s: String): String? {
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(больше|меньше|выше|ниже)\\s*(?:чем\\s+)?(\\d+(?:[.,]\\d+)?)\\s*" +
            "(?:в\\s*(\\d+(?:[.,]\\d+)?))?").find(s)?.let { m ->
            if (!Regex("процент|во сколько раз|на сколько").containsMatchIn(s)) return@let
            val a = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            val b = m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return@let
            if (b == 0.0) return@let
            val diff = (a - b) / b * 100.0
            val word = if (diff >= 0) "больше" else "меньше"
            return "${f(a)} $word ${f(b)} на ${f(abs(diff))}%."
        }
        Regex("во сколько раз\\D{0,30}(\\d+(?:[.,]\\d+)?)\\D{0,10}(\\d+(?:[.,]\\d+)?)").find(s)?.let { m ->
            val a = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            val b = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return@let
            if (b == 0.0) return@let
            return "${f(a)} больше ${f(b)} в ${f(a / b)} раза."
        }
        return null
    }

    // -------------------------------------------------------- знак зодиака

    /** День/месяц из фразы: «5 мая», «05.05». */
    fun dayMonth(s: String): Pair<Int, Int>? {
        Regex("(\\d{1,2})\\s*([а-я]+)").findAll(s).forEach { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@forEach
            val mo = DateFacts.monthIndex(m.groupValues[2]) ?: return@forEach
            if (d in 1..31) return d to mo
        }
        Regex("(\\d{1,2})\\.(\\d{1,2})").find(s)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let
            val mo = m.groupValues[2].toIntOrNull()?.minus(1) ?: return@let
            if (d in 1..31 && mo in 0..11) return d to mo
        }
        return null
    }

    /** Начало знака: месяц (0-based) и день. */
    private val zodiacStarts = listOf(
        Triple(0, 20, "Водолей"), Triple(1, 19, "Рыбы"), Triple(2, 21, "Овен"),
        Triple(3, 20, "Телец"), Triple(4, 21, "Близнецы"), Triple(5, 21, "Рак"),
        Triple(6, 23, "Лев"), Triple(7, 23, "Дева"), Triple(8, 23, "Весы"),
        Triple(9, 23, "Скорпион"), Triple(10, 22, "Стрелец"), Triple(11, 22, "Козерог")
    )

    private fun zodiac(s: String): String? {
        if (!Regex("зодиак").containsMatchIn(s)) return null
        val (d, m) = dayMonth(s) ?: return "Назовите дату, например: «какой знак зодиака 5 мая»."
        if (d !in 1..31 || m !in 0..11) return "Такой даты не бывает, сэр."
        var sign = "Козерог" // до 20 января
        for ((mo, day, name) in zodiacStarts) {
            if (m > mo || (m == mo && d >= day)) sign = name
        }
        return "Знак зодиака — $sign."
    }

    // --------------------------------------------------------- норма воды

    private fun water(s: String): String? {
        if (!Regex("сколько\\D{0,12}воды|норма воды|воды в день").containsMatchIn(s)) return null
        val kg = nums(s).firstOrNull() ?: return "Скажите свой вес, сэр."
        if (kg !in 20.0..400.0) return "Похоже, это не вес в килограммах, сэр."
        val l = kg * 0.033
        return "При весе ${f(kg)} кг стоит пить около ${f(l)} литра воды в день — это ${(l * 1000 / 250).roundToInt()} стаканов."
    }

    /** Краткая справка по бытовым расчётам. */
    fun help(): String = """
        Считать умею разное, сэр:
        • «мой вес 80 рост 180» — индекс массы тела
        • «чаевые 10 процентов от 3500», «раздели 9000 на троих»
        • «скидка 20 процентов с 5000»
        • «площадь комнаты 3 на 4», «площадь круга радиус 5»
        • «среднее 4 5 6», «факториал 5», «нод 12 и 18»
        • «1994 римскими», «на сколько процентов 120 больше 100»
        • «какой знак зодиака 5 мая», «сколько воды пить при весе 80»
    """.trimIndent()
}
