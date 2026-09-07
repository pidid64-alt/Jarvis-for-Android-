package kz.jarvis.app

import java.util.Locale

/**
 * Большой конвертер величин: длина, масса, объём, площадь, скорость,
 * информация, время и температура.
 *
 * Понимает живой текст: «5 км в милях», «сколько метров в 5 километрах»,
 * «100 градусов фаренгейта в цельсиях», «2 гб в мб», «3 дня в часах».
 *
 * Как и MathEngine, файл НЕ зависит от Android — его компилирует и проверяет
 * на JVM scripts/test-logic.sh.
 */
object Units {

    /** Одна единица: псевдонимы (основы слов), множитель к базовой, подпись. */
    private class U(val aliases: List<String>, val factor: Double, val show: String)

    private class Group(val name: String, val units: List<U>)

    private val groups = listOf(
        Group("длина", listOf(
            U(listOf("миллиметр", "мм"), 0.001, "мм"),
            U(listOf("сантиметр", "см"), 0.01, "см"),
            U(listOf("дециметр", "дм"), 0.1, "дм"),
            U(listOf("километр", "км"), 1000.0, "км"),
            U(listOf("метр", "м"), 1.0, "м"),
            U(listOf("дюйм"), 0.0254, "дюймов"),
            U(listOf("фут"), 0.3048, "футов"),
            U(listOf("ярд"), 0.9144, "ярдов"),
            U(listOf("миль", "мил", "мили"), 1609.344, "миль")
        )),
        Group("масса", listOf(
            U(listOf("миллиграмм", "мг"), 0.000001, "мг"),
            U(listOf("килограмм", "кг", "кило"), 1.0, "кг"),
            U(listOf("грамм", "г"), 0.001, "г"),
            U(listOf("центнер"), 100.0, "центнеров"),
            U(listOf("тонна"), 1000.0, "тонн"),
            U(listOf("унц"), 0.0283495, "унций"),
            U(listOf("фунт", "фун"), 0.453592, "фунтов")
        )),
        Group("объём", listOf(
            U(listOf("миллилитр", "мл"), 0.001, "мл"),
            U(listOf("литр", "л"), 1.0, "л"),
            U(listOf("стложка"), 0.015, "ст. ложек"),
            U(listOf("члложка"), 0.005, "ч. ложек"),
            U(listOf("чаш"), 0.236588, "чашек"),
            U(listOf("стакан"), 0.2, "стаканов"),
            U(listOf("пинт"), 0.473176, "пинт"),
            U(listOf("кварт"), 0.946353, "кварт"),
            U(listOf("галлон", "гал"), 3.78541, "галлонов"),
            U(listOf("кубометр", "куб"), 1000.0, "м³")
        )),
        Group("площадь", listOf(
            U(listOf("квсм", "см2"), 0.0001, "см²"),
            U(listOf("квкм", "км2"), 1_000_000.0, "км²"),
            U(listOf("квфут", "фут2"), 0.092903, "кв. футов"),
            U(listOf("квм", "м2"), 1.0, "м²"),
            U(listOf("сотка", "соток"), 100.0, "соток"),
            U(listOf("гектар", "га"), 10_000.0, "га"),
            U(listOf("акр"), 4046.856, "акров")
        )),
        Group("скорость", listOf(
            U(listOf("км/ч", "кмч"), 1.0, "км/ч"),
            U(listOf("м/с", "мс"), 3.6, "м/с"),
            U(listOf("миль/ч", "мильч", "mph"), 1.609344, "миль/ч"),
            U(listOf("узел", "узл"), 1.852, "узлов")
        )),
        Group("информация", listOf(
            U(listOf("мбит"), 131072.0, "Мбит"),
            U(listOf("гбит"), 1_073_741_824.0, "Гбит"),
            U(listOf("килобайт", "кб"), 1024.0, "КБ"),
            U(listOf("мегабайт", "мб"), 1_048_576.0, "МБ"),
            U(listOf("гигабайт", "гб"), 1_073_741_824.0, "ГБ"),
            U(listOf("терабайт", "тб"), 1_099_511_627_776.0, "ТБ"),
            U(listOf("байт"), 1.0, "байт"),
            U(listOf("бит"), 0.125, "бит")
        )),
        Group("время", listOf(
            U(listOf("секунд", "сек"), 1.0, "сек."),
            U(listOf("минут", "мин"), 60.0, "мин."),
            U(listOf("час"), 3600.0, "ч."),
            U(listOf("сутки", "сут"), 86_400.0, "сут."),
            U(listOf("день", "дн"), 86_400.0, "дн."),
            U(listOf("недел"), 604_800.0, "нед.")
        )),
        Group("температура", listOf(
            U(listOf("цельси"), Double.NaN, "°C"),
            U(listOf("фаренгейт"), Double.NaN, "°F"),
            U(listOf("кельвин"), Double.NaN, "K")
        ))
    )

    /** Слова-паразиты, которые не влияют на смысл конвертации. */
    private val fillers = setOf(
        "переведи", "перевести", "перевод", "конвертируй", "посчитай", "сколько",
        "будет", "получится", "из", "пересчитай", "сделай"
    )

    /**
     * Слова-связки между величинами. Без них фразу конвертацией не считаем:
     * иначе «какой день недели 1 января 2030» превратилось бы в «1 день в неделях».
     */
    private val markers = setOf(
        "в", "во", "на", "это", "равно", "равняется", "составляет", "составит", "to", "->", "="
    )

    /** Словосочетания, которые надо склеить в один «токен» до разбора. */
    private val glued = listOf(
        Regex("(?:столов[а-яa-z0-9]*|ст)\\.?\\s+лож[а-яa-z0-9]*") to "стложка",
        Regex("(?:чайн[а-яa-z0-9]*|ч)\\.?\\s+лож[а-яa-z0-9]*") to "члложка",
        Regex("квадратн[а-яa-z0-9]*\\s+километр[а-яa-z0-9]*|кв\\.?\\s*км|км\\s*2(?![0-9])") to "квкм",
        Regex("квадратн[а-яa-z0-9]*\\s+сантиметр[а-яa-z0-9]*|кв\\.?\\s*см|см\\s*2(?![0-9])") to "квсм",
        Regex("квадратн[а-яa-z0-9]*\\s+фут[а-яa-z0-9]*|кв\\.?\\s*фут[а-яa-z0-9]*") to "квфут",
        Regex("квадратн[а-яa-z0-9]*\\s+метр[а-яa-z0-9]*|кв\\.?\\s*м(?![a-z0-9])|м\\s*2(?![0-9])") to "квм",
        Regex("кубич[а-яa-z0-9]*\\s+метр[а-яa-z0-9]*|м\\s*3(?![0-9])") to "кубометр",
        Regex("(?:км|километр[а-яa-z0-9]*)\\s*(?:[/в]\\s*)?час[а-яa-z0-9]*") to "км/ч",
        Regex("(?:миль|мили|миля|mph)\\s*(?:[/в]\\s*)?час[а-яa-z0-9]*") to "миль/ч",
        Regex("(?:км|километр[а-яa-z0-9]*)\\s*[/в]\\s*сек[а-яa-z0-9]*") to "км/с",
        Regex("(?:м|метр[а-яa-z0-9]*)\\s*[/в]\\s*сек[а-яa-z0-9]*") to "м/с",
        Regex("градус[а-яa-z0-9]*") to " ",
        Regex("град\\.?") to " "
    )

    /**
     * Разбирает фразу и возвращает готовый ответ («5 км — это 3,1069 миль.»)
     * либо null, если это не конвертация.
     */
    fun convert(raw: String): String? {
        val s = prepare(raw)
        val num = Regex("\\d+(?:[.,]\\d+)?").find(s) ?: return null
        val value = num.value.replace(',', '.').toDoubleOrNull() ?: return null
        if (value == 0.0 && !s.contains("0")) return null

        val hits = ArrayList<Hit>()
        val marks = ArrayList<Int>()
        for ((pos, tok) in tokens(s)) {
            if (tok in markers) { marks.add(pos); continue }
            // сам токен с числом не считаем («5» — это значение, не единица)
            if (pos < num.range.last + 1 && pos + tok.length > num.range.first) continue
            val hit = best(tok) ?: continue
            hits.add(Hit(pos, hit.first, hit.second))
        }
        if (hits.size < 2) return null

        val after = hits.filter { it.pos >= num.range.last + 1 }
        val before = hits.filter { it.pos < num.range.first }
        val src = after.firstOrNull() ?: before.firstOrNull() ?: return null
        val dst = (after + before).firstOrNull { it.unit !== src.unit } ?: return null
        if (src.group !== dst.group) return null // «5 км в килограммах» — не ко мне

        // между величинами обязана быть связка: «в», «на», «это», «равно»…
        val lo = minOf(src.pos, dst.pos)
        val hi = maxOf(src.pos, dst.pos)
        if (marks.none { it > lo && it < hi }) return null

        if (src.group.name == "температура") return temperature(value, src.unit.show, dst.unit.show)
        if (src.unit.factor == 0.0 || dst.unit.factor == 0.0) return null
        val res = value * src.unit.factor / dst.unit.factor
        return dot("${f(value)} ${src.unit.show} — это ${f(res)} ${dst.unit.show}")
    }

    /** Одиночная температура: «100 по фаренгейту», «36,6 по цельсию». */
    fun temperatureOnly(raw: String): String? {
        val s = prepare(raw)
        if (!Regex("по\\s+(?:цельси|фаренгейт|кельвин)[а-яa-z0-9]*").containsMatchIn(s)) return null
        val num = Regex("\\d+(?:[.,]\\d+)?").find(s) ?: return null
        val value = num.value.replace(',', '.').toDoubleOrNull() ?: return null
        val unit = Regex("цельси|фаренгейт|кельвин").find(s)?.value ?: return null
        val show = when {
            unit.startsWith("цельси") -> "°C"
            unit.startsWith("фаренгейт") -> "°F"
            else -> "K"
        }
        val to = if (show == "°C") "°F" else "°C"
        return temperature(value, show, to)
    }

    /** Подписи единиц могут заканчиваться точкой («дн.», «ч.») — точка нужна одна. */
    private fun dot(text: String): String = text.trimEnd('.', ' ') + "."

    private class Hit(val pos: Int, val group: Group, val unit: U)

    private fun temperature(v: Double, from: String, to: String): String {
        val c = when (from) {
            "°C" -> v
            "°F" -> (v - 32) * 5 / 9
            else -> v - 273.15
        }
        val res = when (to) {
            "°C" -> c
            "°F" -> c * 9 / 5 + 32
            else -> c + 273.15
        }
        return dot("${f(v)} $from — это ${f(res)} $to")
    }

    /** Ищет в токене самую длинную известную основу. */
    private fun best(token: String): Pair<Group, U>? {
        var bestGroup: Group? = null
        var bestUnit: U? = null
        var bestLen = 0
        for (g in groups) for (u in g.units) for (a in u.aliases) {
            if (token.startsWith(a) && a.length > bestLen) {
                bestGroup = g; bestUnit = u; bestLen = a.length
            }
        }
        return if (bestUnit == null) null else bestGroup!! to bestUnit
    }

    private fun prepare(raw: String): String {
        var s = raw.lowercase(Locale.ROOT).replace('ё', 'е')
            .replace('²', '2').replace('³', '3')
        for ((rx, to) in glued) s = rx.replace(s, to)
        return s.split(Regex("\\s+")).filter { it.isNotBlank() && it !in fillers }
            .joinToString(" ")
    }

    /** Токены вместе с их позициями в строке. */
    private fun tokens(s: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            val st = i
            while (i < s.length && !s[i].isWhitespace()) i++
            if (i > st) out.add(st to s.substring(st, i))
        }
        return out
    }

    private fun f(v: Double): String = MathEngine.format(v)

    /** Краткая справка по конвертеру (для «команды конвертера»). */
    fun help(): String = """
        Конвертер понимает, сэр:
        • длина: «5 км в милях», «180 см в дюймах», «сколько метров в 5 километрах»
        • масса: «70 кг в фунтах», «2 тонны в килограммах», «100 грамм в унциях»
        • объём: «2 литра в галлонах», «500 мл в чашках», «3 столовые ложки в мл»
        • площадь: «6 соток в м2», «1 гектар в акрах», «100 м2 в кв футах»
        • скорость: «100 км/ч в м/с», «60 миль в час в км/ч»
        • информация: «2 гб в мб», «100 мбит в мегабайтах»
        • время: «3 дня в часах», «2 недели в секундах»
        • температура: «100 фаренгейта в цельсиях», «0 кельвинов в цельсиях»
    """.trimIndent()
}
