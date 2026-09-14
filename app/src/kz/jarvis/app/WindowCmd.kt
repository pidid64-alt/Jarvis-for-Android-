package kz.jarvis.app

import java.util.Locale

/**
 * Разбор команд про окна: «закрой это», «назад», «сверни», «недавние».
 * Само нажатие — в [Hands] (сервис спец. возможностей). Здесь только фраза.
 *
 * Файл не зависит от Android — покрыт scripts/test-logic.sh.
 */
object WindowCmd {

    enum class Kind {
        /** Системная кнопка «Назад». */
        BACK,
        /** Закрыть то, что на экране: тоже «Назад». */
        CLOSE,
        /** Свернуть текущее приложение на рабочий стол. */
        HOME,
        /** Список недавних приложений. */
        RECENTS
    }

    fun parse(raw: String): Kind? {
        val s = raw.trim().lowercase(Locale.ROOT).replace('ё', 'е')
            .trim('.', '!', '?', ',', ' ')
        if (s.isEmpty()) return null

        // чужие разделы: заметки, списки, фонарик, сам Джарвис, перемотка
        if (Regex(
                "заметк|списк|покупок|фонарик|фонарь|напоминани|будильник|таймер|" +
                    "джарвис|истори|перемот|мотни"
            ).containsMatchIn(s)
        ) return null
        if (Regex("что такое|расскажи|объясни|почему").containsMatchIn(s)) return null

        if (Regex("^(назад|шаг назад|вернись|верни назад|на шаг назад)$").matches(s)) {
            return Kind.BACK
        }

        if (Regex("^(недавние|недавние приложения)$").matches(s) ||
            (Regex("недавн").containsMatchIn(s) &&
                Regex("покажи|открой|список приложений|меню приложений").containsMatchIn(s))
        ) {
            return Kind.RECENTS
        }
        if (Regex("переключи(?:сь)?(?: между)? приложения").containsMatchIn(s)) {
            return Kind.RECENTS
        }

        if (Regex(
                "^(сверни|сверни это|сверни окно|сверни приложение|сверни программу|" +
                    "закрой приложение|закрой программу|закрой текущее приложение)$"
            ).matches(s)
        ) return Kind.HOME

        if (Regex(
                "^(закрой это|закрой окно|закрой вкладку|закрой экран|закрой|" +
                    "убери это|убери окно|закрой текущее окно|закрой это окно)$"
            ).matches(s)
        ) return Kind.CLOSE

        // «закрой ютуб», «сверни телеграм» — свернуть текущее, не искать пакет
        Regex("^(?:закрой|сверни)\\s+(.+)$").find(s)?.let { m ->
            val t = m.groupValues[1].trim()
            if (t.length in 2..40 && !Regex("звук|музык|песн|трек").containsMatchIn(t)) {
                return Kind.HOME
            }
        }
        return null
    }

    fun reply(kind: Kind): String = when (kind) {
        Kind.BACK, Kind.CLOSE -> "Закрываю, сэр."
        Kind.HOME -> "Сворачиваю, сэр."
        Kind.RECENTS -> "Открываю недавние, сэр."
    }
}
