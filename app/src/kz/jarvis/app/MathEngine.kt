package kz.jarvis.app

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Офлайн-калькулятор: понимает живой русский текст («сколько будет 12 умножить на 7»,
 * «посчитай 1,5 плюс 2», «20 процентов от 150», «корень из 144», «2+2*3»).
 *
 * Намеренно БЕЗ android-импортов: класс можно выполнять на обычной JVM,
 * чем и занимается scripts/test-logic.sh — тесты гоняют именно этот код.
 */
object MathEngine {

    /** Результат разбора: либо число, либо причина, почему не вышло. */
    data class Result(val value: Double?, val error: String? = null) {
        val ok: Boolean get() = value != null
    }

    private class DivByZero : RuntimeException("div0")

    // ------------------------------------------------------------------ API

    fun calc(raw: String): Result {
        val expr = toExpression(raw) ?: return Result(null)
        return try {
            val v = Parser(expr).parse()
            when {
                v.isNaN() -> Result(null, "Не число, сэр.")
                v.isInfinite() || abs(v) > 1e15 -> Result(null, "Слишком большое число, сэр.")
                else -> Result(v)
            }
        } catch (e: DivByZero) {
            Result(null, "На ноль делить нельзя, сэр.")
        } catch (e: Exception) {
            Result(null)
        }
    }

    /** «12 * 7 = 84» — готовый ответ для озвучки. null, если это не математика. */
    fun answer(raw: String): String? {
        val expr = toExpression(raw) ?: return null
        val r = calc(raw)
        if (!r.ok) return r.error ?: "Не смог посчитать, сэр."
        return "${humanize(expr)} = ${format(r.value!!)}"
    }

    /** 84 -> «84», 12.5 -> «12,5», 0.33333 -> «0,3333». */
    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "не число"
        val r = Math.round(v * 10000.0) / 10000.0
        return if (abs(r - Math.rint(r)) < 1e-9 && abs(r) < 1e15) {
            r.toLong().toString()
        } else {
            var s = String.format(java.util.Locale.US, "%.4f", r)
            s = s.trimEnd('0').trimEnd('.')
            s.replace('.', ',')
        }
    }

    // ------------------------------------------------- текст -> выражение

    /** Превращает фразу в арифметическое выражение. null — если математики нет. */
    fun toExpression(raw: String): String? {
        if (raw.isBlank()) return null
        var s = raw.lowercase(java.util.Locale.ROOT).replace('ё', 'е')

        // «сколько процентов составляет 20 от 200»
        Regex("сколько\\s+процентов\\s+составляет\\s+(\\d+(?:[.,]\\d+)?)\\s+(?:от|из)\\s+(\\d+(?:[.,]\\d+)?)")
            .find(s)?.let {
                return "(${it.groupValues[1].replace(',', '.')}/${it.groupValues[2].replace(',', '.')})*100"
            }

        // «20 процентов от 150»
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:процентов|процента|процент|%)\\s*(?:от|из|с)\\s+(.+)")
            .find(s)?.let {
                val head = it.groupValues[1].replace(',', '.')
                val tail = it.groupValues[2]
                s = "($head/100)*($tail)"
            }

        // Формы «глагол + число + предлог + число»: порядок операндов важен,
        // поэтому разбираем их до общей замены слов.
        fun num(g: List<String>, i: Int) = g[i].replace(',', '.')
        Regex("(?:отними|отнять|отнимите|вычти|вычесть)\\s+(\\d+(?:[.,]\\d+)?)\\s+(?:от|из)\\s+(\\d+(?:[.,]\\d+)?)")
            .find(s)?.let { return "(${num(it.groupValues, 2)})-(${num(it.groupValues, 1)})" }
        Regex("(?:раздели|разделить|разделите|подели|поделить|делим|делить)\\s+(\\d+(?:[.,]\\d+)?)\\s+на\\s+(\\d+(?:[.,]\\d+)?)")
            .find(s)?.let { return "(${num(it.groupValues, 1)})/(${num(it.groupValues, 2)})" }
        Regex("(?:умножь|умножить|умножим|помножь|помножить)\\s+(\\d+(?:[.,]\\d+)?)\\s+на\\s+(\\d+(?:[.,]\\d+)?)")
            .find(s)?.let { return "(${num(it.groupValues, 1)})*(${num(it.groupValues, 2)})" }
        Regex("(?:прибавь|прибавить|добавь|добавить)\\s+(\\d+(?:[.,]\\d+)?)\\s+к\\s+(\\d+(?:[.,]\\d+)?)")
            .find(s)?.let { return "(${num(it.groupValues, 1)})+(${num(it.groupValues, 2)})" }

        val words = listOf(
            "умножить на" to " * ", "умножить" to " * ", "помножить на" to " * ",
            "помножить" to " * ", "умножим на" to " * ", "раз на" to " * ",
            "разделить на" to " / ", "поделить на" to " / ", "делить на" to " / ",
            "раздели на" to " / ", "подели на" to " / ", "делим на" to " / ",
            "плюс" to " + ", "прибавить" to " + ", "прибавь" to " + ",
            "сложить с" to " + ", "добавить" to " + ",
            "минус" to " - ", "отнять" to " - ", "отними" to " - ",
            "вычесть" to " - ", "вычти" to " - ",
            "в квадрате" to "^2", "квадрат числа" to "^2",
            "в кубе" to "^3", "куб числа" to "^3",
            "квадратный корень из" to "sqrt(", "корень из" to "sqrt(",
            "корень квадратный из" to "sqrt(", "корень" to "sqrt(",
            "сколько будет" to " ", "сколько получится" to " ", "сколько" to " ",
            "посчитай" to " ", "посчитать" to " ", "посчитайте" to " ",
            "вычисли" to " ", "вычислить" to " ", "калькулятор" to " ",
            "равняется" to " ", "равно" to " ", "получится" to " ", "будет" to " ",
            "результат" to " ", "это" to " ", "сумма" to " + ", "разность" to " - "
        )
        for ((w, r) in words) s = s.replace(w, r)

        // «в степени 5»
        s = Regex("в\\s+степени\\s+(\\d+)").replace(s) { "^" + it.groupValues[1] }

        // «12 на 7» после умножения/деления
        s = Regex("([*/])\\s*на\\b").replace(s) { it.groupValues[1] }

        // десятичная запятая
        s = Regex("(\\d)\\s*,\\s*(?=\\d)").replace(s) { it.groupValues[1] + "." }

        // «2х2», «5 х 3»
        s = Regex("(?<=[\\d)\\s])[xх](?=[\\s\\d(])").replace(s, "*")

        // закрыть sqrt(, если пользователь не поставил скобку
        if (s.contains("sqrt(") && s.count { it == '(' } > s.count { it == ')' }) s += ")"

        // оставить только математику
        s = s.replace(Regex("[^0-9a-z.+\\-*/^()\\s]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()

        val hasDigit = s.any { it.isDigit() }
        val hasOp = s.any { it in "+-*/^" } || s.contains("sqrt")
        if (!hasDigit || !hasOp) return null
        if (s.count { it.isDigit() } == 0) return null
        return s
    }

    /** Делает выражение читаемым для озвучки: «12 * 7» -> «12 * 7» (как есть). */
    private fun humanize(expr: String): String = expr

    // -------------------------------------------------------------- парсер

    private class Parser(private val src: String) {
        private var i = 0

        fun parse(): Double {
            val v = parseExpr()
            skipWs()
            if (i < src.length) throw IllegalStateException("tail at $i: $src")
            return v
        }

        private fun peek(): Char = if (i < src.length) src[i] else ' '

        private fun skipWs() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        private fun expect(c: Char) {
            if (peek() != c) throw IllegalStateException("want '$c' at $i in $src")
            i++
        }

        private fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                skipWs()
                when (peek()) {
                    '+' -> { i++; v += parseTerm() }
                    '-' -> { i++; v -= parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseUnary()
            while (true) {
                skipWs()
                when (peek()) {
                    '*' -> { i++; v *= parseUnary() }
                    '/' -> {
                        i++
                        val d = parseUnary()
                        if (d == 0.0) throw DivByZero()
                        v /= d
                    }
                    else -> {
                        // неявное умножение: «2(3+4)», «12 7»
                        if (i < src.length && (src[i].isDigit() || src[i] == '(' || src[i].isLetter())) {
                            v *= parseUnary()
                        } else return v
                    }
                }
            }
        }

        private fun parseUnary(): Double {
            skipWs()
            return when (peek()) {
                '-' -> { i++; -parseUnary() }
                '+' -> { i++; parseUnary() }
                else -> parsePower()
            }
        }

        private fun parsePower(): Double {
            val base = parsePrimary()
            skipWs()
            return if (peek() == '^') {
                i++
                base.pow(parseUnary())
            } else base
        }

        private fun parsePrimary(): Double {
            skipWs()
            if (i >= src.length) throw IllegalStateException("eof")
            val c = src[i]
            if (c == '(') {
                i++
                val v = parseExpr()
                skipWs()
                expect(')')
                return v
            }
            if (c.isLetter()) {
                val start = i
                while (i < src.length && src[i].isLetter()) i++
                val name = src.substring(start, i)
                skipWs()
                if (peek() == '(') i++
                val arg = parseUnary()
                skipWs()
                if (peek() == ')') i++
                return when (name) {
                    "sqrt" -> if (arg < 0) Double.NaN else sqrt(arg)
                    "abs" -> abs(arg)
                    else -> throw IllegalStateException("unknown func $name")
                }
            }
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                return src.substring(start, i).toDoubleOrNull()
                    ?: throw IllegalStateException("bad number ${src.substring(start, i)}")
            }
            throw IllegalStateException("unexpected '$c' at $i in $src")
        }
    }
}
