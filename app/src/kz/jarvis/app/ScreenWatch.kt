package kz.jarvis.app

import java.util.Locale

/**
 * Наблюдение за экраном: как часто Джарвис читает то, что сейчас на экране,
 * и может ли сам заговорить по теме.
 *
 * Чистая логика (Android-часть — в [AutoSendService]), покрыта
 * scripts/test-logic.sh на JVM.
 *
 * Раньше период был зашит в коде (5 минут) и «смотреть чаще» было нельзя.
 * Теперь владелец говорит: «смотри на экран каждые две минуты» — или крутит
 * переключатель в настройках.
 */
object ScreenWatch {

    /** Доступные периоды, минуты — от «почти постоянно» до «редко». */
    val STEPS = listOf(1, 2, 3, 5, 10, 15, 30, 60)

    /** Период по умолчанию: нечасто, но и не раз в полчаса. */
    const val DEFAULT_MIN = 5

    /** Минимальный период — чаще раза в минуту читать экран смысла нет. */
    const val MIN_MIN = 1

    /** Фраза вообще про наблюдение за экраном. */
    fun isAbout(s0: String): Boolean {
        val s = s0.lowercase(Locale.ROOT).replace('ё', 'е')
        val screen = Regex("экран|экране|экрана|монитор|что на телефоне|что у меня на экране")
            .containsMatchIn(s)
        val watch = Regex("смотр|наблюд|следи|читай|анализ|подглядыва|замеча|видишь|видел|" +
            "комментиру|обсужда|разбирай").containsMatchIn(s)
        return screen && watch
    }

    /** true — включить, false — выключить, null — не приказ (вопрос/частота). */
    fun onOff(s0: String): Boolean? {
        if (!isAbout(s0)) return null
        val s = s0.lowercase(Locale.ROOT).replace('ё', 'е')
        if (Regex("включен|выключен|статус|работает|состояние|как часто|как теперь|сколько раз|" +
                "какой период|что умеет|зачем").containsMatchIn(s)
        ) return null
        val on = Regex("включ|вруб|актив|начни|смотри|наблюдай|следи|разреши").containsMatchIn(s)
        val off = Regex("выключ|отключ|выруб|перестань|не смотри|не надо смотреть|убери|прекрати")
            .containsMatchIn(s)
        return when {
            on && !off -> true
            off && !on -> false
            else -> null
        }
    }

    /**
     * Явный период из фразы: «каждые 2 минуты», «раз в пять минут»,
     * «каждую минуту». null — период не назван.
     */
    fun interval(s0: String): Int? {
        val s = s0.lowercase(Locale.ROOT).replace('ё', 'е')
        if (!Regex("кажд|раз в|период|интервал|чаще|реже|минут|час").containsMatchIn(s)) return null
        Regex("(\\d+)\\s*(?:минут|минута|минуты|мин)").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            return snap(if (n <= 0) MIN_MIN else n)
        }
        Regex("(\\d+)\\s*(?:час|часа|часов)").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            return snap(n * 60)
        }
        Regex("(?:каждую|одну|1)\\s*минуту|каждую минуту|раз в минуту").find(s)?.let { return MIN_MIN }
        wordMinutes(s)?.let { return snap(it) }
        return null
    }

    private val NUM = mapOf(
        "одну" to 1, "одна" to 1, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "пятнадцать" to 15, "двадцать" to 20, "тридцать" to 30, "полчаса" to 30, "час" to 60
    )

    private fun wordMinutes(s: String): Int? {
        val m = Regex("(каждые|каждую|раз в)\\s+([а-я]+)\\s*(минут|минуты|минуту|час|часа|часов)?")
            .find(s) ?: return null
        val w = m.groupValues[2]
        val n = NUM[w] ?: return null
        val unit = m.groupValues[3]
        return if (unit.startsWith("час")) n * 60 else n
    }

    /**
     * «чаще» / «реже» — сдвиг по списку [STEPS].
     * @return отрицательное число — чаще, положительное — реже, null — не про это
     */
    fun adjust(s0: String): Int? {
        val s = s0.lowercase(Locale.ROOT).replace('ё', 'е')
        val more = Regex("чаще|почаще|больше смотри|нередко|постоянно|все время|всё время")
            .containsMatchIn(s)
        val less = Regex("реже|пор еже|по реже|поменьше смотри|не так часто|редко").containsMatchIn(s)
        return when {
            more && !less -> -1
            less && !more -> +1
            else -> null
        }
    }

    /** Сдвигает период на шаг в сторону «чаще»/«реже». */
    fun shifted(current: Int, direction: Int): Int {
        val i = STEPS.indexOfFirst { it >= current }.let { if (it < 0) STEPS.size - 1 else it }
        val next = (i + direction).coerceIn(0, STEPS.size - 1)
        return STEPS[next]
    }

    /** Ближайший доступный период: 4 минуты → 5, 40 минут → 30. */
    fun snap(minutes: Int): Int {
        val m = minutes.coerceAtLeast(MIN_MIN)
        return STEPS.minByOrNull { kotlin.math.abs(it - m) } ?: DEFAULT_MIN
    }

    /** «каждую минуту», «каждые 2 минуты», «каждые 5 минут». */
    fun label(minutes: Int): String = when {
        minutes <= 1 -> "каждую минуту"
        minutes % 60 == 0 && minutes >= 60 -> {
            val h = minutes / 60
            "раз в $h ${TextTools.plural(h.toLong(), "час", "часа", "часов")}"
        }
        else -> "каждые $minutes ${TextTools.plural(minutes.toLong(), "минуту", "минуты", "минут")}"
    }

    /** Ответ на «как часто ты смотришь на экран». */
    fun statusText(on: Boolean, minutes: Int): String = when {
        !on -> "Наблюдение за экраном выключено, сэр. Скажите «включи наблюдение за экраном» — " +
            "нужен ещё сервис спец. возможностей, я открою его настройки."
        else -> "Смотрю на экран ${label(minutes)}, сэр. Скажите «смотри на экран чаще», " +
            "«каждые 2 минуты» или «выключи наблюдение за экраном»."
    }
}
