package kz.jarvis.app

import java.util.Locale

/**
 * Своё кодовое слово владельца — «сказал фразу, Джарвис включил диктофон».
 *
 * Слово НЕ зашито в программу: человек задаёт его сам («кодовое слово:
 * пиши меня») голосом или в настройках. Здесь — только чистая логика сравнения
 * распознанной фразы с заданными словами, поэтому она покрыта JVM-тестами.
 *
 * Распознаватель ошибается («жарвис» вместо «джарвис»), поэтому сравнение
 * терпимое: каждое слово фразы ищется в услышанном с расстоянием Левенштейна
 * не больше 1 (для слов от 5 букв).
 */
object CodeWords {

    /** Приводим к виду «буквы, цифры и пробелы» — так сравнение надёжнее. */
    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Список кодовых фраз из одной строки: разделители — запятая, точка с
     * запятой, «или» и перевод строки («пиши меня, запись пошла»).
     */
    fun parseList(raw: String): List<String> =
        raw.split(Regex("[,;\\n]|\\s+или\\s+"))
            .map { normalize(it) }
            .filter { it.length >= 3 }
            .distinct()

    /** Годится ли фраза как кодовая: не слишком короткая и не «Джарвис». */
    fun validate(phrase: String): String? {
        val n = normalize(phrase)
        if (n.length < 3) return "Кодовое слово слишком короткое, сэр — скажите хотя бы три буквы."
        if (n.split(' ').size > 6) return "Слишком длинная фраза, сэр: кодовое слово — это 1–6 слов."
        if (WakeWords.contains(n)) {
            return "Это слово я и так слушаю как «Джарвис», сэр. Выберите другое кодовое слово."
        }
        return null
    }

    /** Сказана ли одна из кодовых фраз [phrases] в услышанном [heard]. */
    fun matchesAny(phrases: List<String>, heard: String): Boolean =
        phrases.any { matches(it, heard) }

    /**
     * Сказана ли кодовая фраза [phrase]. Фраза ищется целиком, как окно слов
     * в услышанном тексте: «ну всё, пиши меня» → кодовое слово «пиши меня».
     */
    fun matches(phrase: String, heard: String): Boolean {
        val want = normalize(phrase).split(' ').filter { it.isNotEmpty() }
        if (want.isEmpty()) return false
        val got = normalize(heard).split(' ').filter { it.isNotEmpty() }
        if (got.size < want.size) return false
        for (i in 0..got.size - want.size) {
            if (windowMatches(want, got, i)) return true
        }
        return false
    }

    /** Убирает кодовую фразу из услышанного — остаётся то, что сказано следом. */
    fun strip(phrase: String, heard: String): String {
        val want = normalize(phrase).split(' ').filter { it.isNotEmpty() }
        if (want.isEmpty()) return normalize(heard)
        val got = normalize(heard).split(' ').filter { it.isNotEmpty() }.toMutableList()
        var i = 0
        while (i <= got.size - want.size) {
            if (windowMatches(want, got, i)) {
                repeat(want.size) { got.removeAt(i) }
            } else {
                i++
            }
        }
        return got.joinToString(" ").trim()
    }

    /** Все слова фразы совпали с окном услышанного (с допуском на ошибку). */
    private fun windowMatches(want: List<String>, got: List<String>, from: Int): Boolean {
        for (j in want.indices) {
            if (!wordMatches(want[j], got[from + j])) return false
        }
        return true
    }

    private fun wordMatches(a: String, b: String): Boolean {
        if (a == b) return true
        val limit = if (a.length >= 6) 2 else if (a.length >= 4) 1 else 0
        if (limit == 0) return false
        // ошибка распознавания не должна «съедать» больше букв, чем в слове
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        return levenshtein(a, b) <= limit
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[b.length]
    }

    // ------------------------------------------------------------- стоп-фразы

    /**
     * Фразы, которыми останавливают запись. Работают и во время записи
     * (в паузах между кусками), и как обычная команда «стоп запись».
     */
    val STOP = Regex(
        "^(?:стоп|стоп запись|стоп записи|останови запись|остановить запись|прекрати запись|" +
            "выключи диктофон|выключи запись|закончи запись|заверши запись|конец записи|" +
            "хватит писать|хватит записывать|больше не пиши|запись окончена|запись завершена|" +
            "отбой записи|сохрани запись|сохранить запись)$"
    )

    fun isStop(heard: String): Boolean = STOP.matches(normalize(heard))

    /**
     * Человек сказал кодовое слово ещё раз — это тоже «останови»:
     * «пиши меня» → запись, «пиши меня» снова → стоп. Лишних слов почти нет,
     * иначе фразой считалось бы любое упоминание слова в разговоре.
     */
    fun isStopByPhrase(phrases: List<String>, heard: String): Boolean {
        val n = normalize(heard)
        if (n.isEmpty()) return false
        val heardWords = n.split(' ').size
        return phrases.any { p ->
            val pn = normalize(p)
            pn.isNotEmpty() && matches(p, n) && heardWords <= pn.split(' ').size + 1
        }
    }

}
