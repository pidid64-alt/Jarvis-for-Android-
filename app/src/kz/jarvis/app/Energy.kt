package kz.jarvis.app

/**
 * Чистая политика энергопотребления Джарвиса.
 *
 * Самый дорогой узел — постоянный SpeechRecognizer с открытым микрофоном;
 * второй — активный Wi‑Fi scan. Политика не выключает функции, а задаёт паузы
 * между сессиями распознавания и экспоненциальную задержку повторных сканов.
 */
object Energy {

    enum class Mode(val id: Int, val title: String, val hint: String) {
        RESPONSIVE(
            0,
            "Максимальная отзывчивость",
            "Почти без пауз: Джарвис слышит быстрее, но микрофон и Wi‑Fi расходуют больше заряда."
        ),
        BALANCED(
            1,
            "Сбалансированный",
            "Рекомендуется. При погашенном экране делает короткие паузы между сессиями, а Wi‑Fi ищет всё реже, если рядом ничего нет."
        ),
        SAVER(
            2,
            "Экономный",
            "Самый низкий расход. С выключенным экраном пауза микрофона до 6 секунд — иногда слово «Джарвис» потребуется повторить."
        );

        companion object {
            val DEFAULT = BALANCED
            fun byId(id: Int): Mode = values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    /**
     * Пауза после пустой сессии распознавания. Во время зарядки у обычных
     * режимов пауз почти нет; системное энергосбережение усиливает экономию.
     */
    fun wakeRestartDelayMs(
        mode: Mode,
        interactive: Boolean,
        charging: Boolean,
        systemPowerSave: Boolean
    ): Long {
        if (charging && mode != Mode.SAVER) return 150L
        return when (mode) {
            Mode.RESPONSIVE -> if (systemPowerSave && !interactive) 750L else 150L
            Mode.BALANCED -> when {
                systemPowerSave && !interactive -> 4_000L
                interactive -> 300L
                else -> 1_500L
            }
            Mode.SAVER -> when {
                charging -> 750L
                systemPowerSave && !interactive -> 8_000L
                interactive -> 1_200L
                else -> 6_000L
            }
        }
    }

    /**
     * Задержка следующего активного Wi‑Fi-скана после того, как открытых сетей
     * не оказалось. [emptyScans] начинается с 1. Ограничение сверху не даёт
     * службе исчезнуть навсегда; включение экрана всё равно запускает проверку.
     */
    fun wifiEmptyRetryMs(mode: Mode, emptyScans: Int, systemPowerSave: Boolean): Long {
        val n = emptyScans.coerceAtLeast(1)
        val minutes = when (mode) {
            Mode.RESPONSIVE -> when (n) {
                1 -> 2
                2 -> 4
                3 -> 8
                else -> 15
            }
            Mode.BALANCED -> when (n) {
                1 -> 5
                2 -> 10
                3 -> 20
                else -> 30
            }
            Mode.SAVER -> when (n) {
                1 -> 10
                2 -> 20
                3 -> 40
                else -> 60
            }
        }
        val adjusted = if (systemPowerSave) {
            maxOf(minutes, when (mode) {
                Mode.RESPONSIVE -> 10
                Mode.BALANCED -> 30
                Mode.SAVER -> 60
            })
        } else minutes
        return adjusted * 60_000L
    }

    /** После найденной сети Android получает время подключиться без новых scan. */
    fun wifiFoundRetryMs(mode: Mode, systemPowerSave: Boolean): Long {
        val minutes = when (mode) {
            Mode.RESPONSIVE -> 5
            Mode.BALANCED -> 15
            Mode.SAVER -> 30
        }
        return maxOf(minutes, if (systemPowerSave) 30 else 0) * 60_000L
    }
}
