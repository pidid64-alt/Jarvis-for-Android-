package kz.jarvis.app

/**
 * Распознавание слова-триггера «Джарвис» в тексте распознавания речи.
 *
 * Как и MathEngine, класс не зависит от Android: его покрывает
 * scripts/test-logic.sh, который выполняется на JVM.
 */
object WakeWords {

    /** Варианты, которые реально выдаёт распознаватель (в т.ч. ошибочные). */
    private val VARIANTS = listOf(
        "джарвис", "джервис", "джарвиз", "джарвикс", "джарвиса", "джарвису",
        "джарус", "джарусь", "джарви", "джавис", "джавиц", "ярвис", "джарв", "jarvis"
    )

    private val WAKE = Regex("(?:^|\\s)(" + VARIANTS.joinToString("|") + ")(?:\\s|$)")

    /** Тот же список, но годится для текста со знаками препинания и операторами. */
    private val WAKE_IN_TEXT =
        Regex("(?:^|(?<=[\\s,.!?:]))(" + VARIANTS.joinToString("|") + ")(?=[\\s,.!?:]|$)")

    /** Приводим текст к виду «буквы и пробелы» — так сравнение надёжнее. */
    fun normalize(text: String): String =
        text.lowercase(java.util.Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Есть ли в фразе слово-триггер. */
    fun contains(text: String): Boolean {
        if (text.isBlank()) return false
        return WAKE.containsMatchIn(normalize(text))
    }

    /**
     * Убирает «Джарвис» из фразы, оставляя саму команду:
     * «джарвис, который час» -> «который час».
     */
    fun strip(text: String): String = removeFromText(normalize(text))

    /**
     * То же, но сохраняет остальные символы (знаки математических операций,
     * цифры с точкой и т.п.) — для разбора команд.
     */
    fun removeFromText(text: String): String {
        var s = text
        var guard = 0
        while (WAKE_IN_TEXT.containsMatchIn(s) && guard++ < 4) {
            s = WAKE_IN_TEXT.replace(s, " ")
        }
        return s.replace(Regex("\\s+"), " ").trim().trim(',', '.', '!', '?')
    }
}
