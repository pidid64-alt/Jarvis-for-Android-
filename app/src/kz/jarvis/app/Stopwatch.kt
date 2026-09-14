package kz.jarvis.app

/**
 * Секундомер: разбор команд и формат прошедшего времени.
 * Состояние (идёт / пауза / накоплено) хранит [Prefs] — этот объект чистый Kotlin.
 */
object Stopwatch {

    enum class Cmd { START, STOP, RESET, STATUS }

    /**
     * «запусти секундомер», «останови секундомер», «сбрось секундомер»,
     * «сколько на секундомере», «засеки время».
     * «засеки 5 минут» — не секундомер (это таймер).
     */
    fun parse(s: String): Cmd? {
        val t = s.lowercase().replace('ё', 'е').trim()
        val named = Regex("секундомер|stopwatch").containsMatchIn(t)
        val markTime = Regex("^(?:засеки|засечь|засекай)(?:\\s+время)?$").matches(t)
        if (!named && !markTime) return null
        if (TimeParse.duration(t) != null) return null

        if (Regex("сброс|обнул|заново|сначала|с нуля").containsMatchIn(t)) return Cmd.RESET
        if (Regex("стоп|останов|пауз|останови|выключ").containsMatchIn(t)) return Cmd.STOP
        if (Regex("старт|запуст|включ|засек|поехал|продолж").containsMatchIn(t) || markTime) {
            return Cmd.START
        }
        if (Regex("сколько|статус|показа|что на|какой").containsMatchIn(t)) return Cmd.STATUS
        // просто «секундомер» — сказать, сколько уже
        return Cmd.STATUS
    }

    fun format(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
        else "%d:%02d".format(m, sec)
    }

    fun elapsed(running: Boolean, startedAt: Long, accrued: Long, now: Long): Long {
        val extra = if (running && startedAt > 0L) (now - startedAt).coerceAtLeast(0L) else 0L
        return accrued + extra
    }

    fun spoken(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return buildString {
            if (h > 0) append("$h ч. ")
            if (m > 0 || h > 0) append("$m мин. ")
            append("$sec сек.")
        }.trim()
    }
}
