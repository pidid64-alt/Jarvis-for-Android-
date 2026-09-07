package kz.jarvis.app

import java.util.Locale

/**
 * Команды для работы с текстом: посчитать слова и буквы, перевернуть фразу,
 * написать капсом, перевести в азбуку Морзе или в транслит, повторить фразу.
 *
 * Без Android-зависимостей — покрыт scripts/test-logic.sh.
 */
object TextTools {

    fun ask(raw: String): String? {
        val s = raw.lowercase(Locale.ROOT).replace('ё', 'е').trim()
        if (s.isEmpty()) return null

        Regex("сколько\\D{0,12}слов").find(s)?.let {
            val body = after(s, it.range.last + 1, "в тексте", "в фразе", "в предложении", "тут", "здесь")
            if (body.isBlank()) return "Скажите фразу, и я посчитаю слова, сэр."
            val n = words(body).toLong()
            return "В этой фразе $n ${plural(n, "слово", "слова", "слов")}."
        }
        Regex("сколько\\D{0,12}(букв|символов|знаков)").find(s)?.let { m ->
            val body = after(s, m.range.last + 1, "в тексте", "в фразе", "тут", "здесь")
            if (body.isBlank()) return "Скажите фразу — посчитаю символы."
            val letters = body.count { it.isLetter() }
            return "Всего символов: ${body.length}, из них букв: $letters."
        }
        Regex("сколько\\D{0,12}предложений").find(s)?.let {
            val body = after(s, it.range.last + 1, "в тексте", "тут")
            if (body.isBlank()) return "Скажите текст, сэр."
            return "Предложений: ${body.split(Regex("[.!?]+")).count { p -> p.isNotBlank() }}."
        }

        Regex("^(переверни|перевернуть|наоборот|задом наперед|задом наперёд)(?:\\s+(фразу|текст|слово|строку))?\\s+(.+)$")
            .find(s)?.let { m ->
                return "«" + m.groupValues[3].trim().reversed() + "»"
            }

        Regex("^(заглавными|капсом|большими буквами|прописными)\\s+(.+)$").find(s)?.let { m ->
            return "«" + m.groupValues[2].uppercase(Locale.ROOT) + "»"
        }
        Regex("^(поменяй порядок слов|слова наоборот|переверни порядок слов)\\s+(.+)$").find(s)?.let { m ->
            return "«" + m.groupValues[2].split(" ").reversed().joinToString(" ") + "»"
        }
        Regex("^(маленькими буквами|строчными)\\s+(.+)$").find(s)?.let { m ->
            return "«" + m.groupValues[2] + "»"
        }
        Regex("^(каждое слово с большой буквы|с большой буквы)\\s+(.+)$").find(s)?.let { m ->
            val t = m.groupValues[2].split(" ").joinToString(" ") {
                it.replaceFirstChar { c -> c.uppercase(Locale.ROOT) }
            }
            return "«$t»"
        }

        Regex("(морзе|морзянк)").find(s)?.let { m ->
            val body = after(s, m.range.last + 1, "текст", "фразу", "слово")
            if (body.isBlank()) return "Скажите, что перевести в азбуку Морзе, сэр."
            return toMorse(body)
        }

        Regex("^(транслит[а-яa-z0-9]*|латиницей|по-английски напиши)\\s+(.+)$").find(s)?.let { m ->
            return "«" + toTranslit(m.groupValues[2]) + "»"
        }

        Regex("^(повтори за мной|скажи за мной|проговори)\\s+(.+)$").find(s)?.let { m ->
            return m.groupValues[2].replaceFirstChar { it.uppercase(Locale.ROOT) } + "."
        }

        Regex("^(сколько слогов)\\s+(.+)$").find(s)?.let { m ->
            val body = m.groupValues[2]
            val n = body.count { it in "аеёиоуыэюя" }
            return "Слогов примерно: $n."
        }

        return null
    }

    private fun after(s: String, from: Int, vararg skip: String): String {
        var body = if (from <= s.length) s.substring(from) else ""
        for (w in skip) body = body.replace(w, " ")
        return body.trim().trim(':', ',', ' ')
    }

    private fun words(s: String): Int = s.split(Regex("\\s+")).count { it.isNotBlank() }

    private val morse = mapOf(
        'а' to ".-", 'б' to "-...", 'в' to ".--", 'г' to "--.", 'д' to "-..", 'е' to ".",
        'ж' to "...-", 'з' to "--..", 'и' to "..", 'й' to ".---", 'к' to "-.-", 'л' to ".-..",
        'м' to "--", 'н' to "-.", 'о' to "---", 'п' to ".--.", 'р' to ".-.", 'с' to "...",
        'т' to "-", 'у' to "..-", 'ф' to "..-.", 'х' to "....", 'ц' to "-.-.", 'ч' to "---.",
        'ш' to "----", 'щ' to "--.-", 'ъ' to "--.--", 'ы' to "-.--", 'ь' to "-..-", 'э' to "..-..",
        'ю' to "..--", 'я' to ".-.-",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----."
    )

    fun toMorse(text: String): String {
        val sb = StringBuilder()
        for (word in text.trim().split(Regex("\\s+"))) {
            for (c in word) {
                morse[c]?.let { sb.append(it).append(' ') }
            }
            sb.append("/ ")
        }
        val out = sb.toString().trim()
        return if (out.isEmpty()) "Тут нечего переводить, сэр." else out
    }

    private val translit = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    /** Русское склонение: plural(5, «пункт», «пункта», «пунктов») -> «пунктов». */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val v = kotlin.math.abs(n) % 100
        val d = v % 10
        return when {
            v in 11..14 -> many
            d == 1L -> one
            d in 2..4 -> few
            else -> many
        }
    }

    fun toTranslit(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            val low = c.lowercaseChar()
            val t = translit[low]
            if (t == null) sb.append(c)
            else if (c.isUpperCase()) sb.append(t.replaceFirstChar { it.uppercase(Locale.ROOT) })
            else sb.append(t)
        }
        return sb.toString()
    }

    fun help(): String = """
        Команды для текста, сэр:
        • «сколько слов в тексте раз два три», «сколько букв в фразе …»
        • «переверни фразу …», «заглавными …», «каждое слово с большой буквы …»
        • «морзе сос», «транслит привет»
        • «повтори за мной …», «сколько слогов в слове …»
    """.trimIndent()
}
