package kz.jarvis.app

import java.util.Locale

/**
 * Диктофон Джарвиса: разбор голосовых команд и текст ответов.
 *
 * Сама запись живёт в [Recorder] (Android-часть), а разбор фраз — чистая
 * логика, поэтому покрыт scripts/test-logic.sh на JVM.
 *
 * Главное: кодовое слово владелец выбирает сам — ничего не зашито
 * (см. [CodeWords]). «Кодовое слово: пиши меня» — и Джарвис начинает писать
 * звук, как только услышит эту фразу, даже когда приложение закрыто.
 */
object RecCmd {

    enum class Action {
        START, STOP, STATUS, LIST, PLAY_LAST, DELETE_LAST,
        SET_WORD, CLEAR_WORD, LIMIT, ENABLE, DISABLE,
        SOURCE_SYSTEM, SOURCE_BUILTIN
    }

    /**
     * @param action  что сделать
     * @param word    новое кодовое слово (для [Action.SET_WORD])
     * @param minutes ограничение длины записи (для [Action.LIMIT])
     */
    data class Cmd(val action: Action, val word: String? = null, val minutes: Int = 0)

    /** Фраза вообще про диктофон/запись звука. */
    private val ABOUT = Regex(
        "диктофон|запиши|запис[а-я]*(?: звука|голоса|аудио)?|записыва[а-я]*|пиши меня|пиши звук|пиши голос|" +
            "кодов[а-я]* (?:слово|слову|словом|слове|фразу|фразе|фразой)|голосов(?:ая|ой) заметк"
    )

    /** Слова, с которыми «запись» значит не звук: заметка, список, напоминание. */
    private val NOT_SOUND = Regex(
        "заметк|списк|покупк|напоминан|номер|логин|парол|телефон|экран|видео|" +
            "правил|звонк|историю|вызов|музык|песн|трек|" +
            "вацап|васап|ватсап|вотсап|воцап|whatsapp|телеграм|дискорд|discord|смс|мессенджер"
    )

    /** «не пиши маме» — это правило автоответа, а не диктофон. */
    private val RULE_LIKE = Regex("^не\\s|\\bникогда\\b")

    fun isAbout(s: String): Boolean {
        if (NOT_SOUND.containsMatchIn(s) || RULE_LIKE.containsMatchIn(s)) return false
        return ABOUT.containsMatchIn(s)
    }

    /** Разбирает команду; null — фраза не про диктофон. */
    fun parse(raw: String): Cmd? {
        val s = raw.lowercase(Locale.ROOT).replace('ё', 'е').trim()
        if (s.isEmpty()) return null

        // ---- кодовое слово: задать, спросить, убрать ----
        if (Regex("кодов[а-я]*\\s+(?:слово|слову|словом|слове|фразу|фразе|фразой)").containsMatchIn(s) ||
            Regex("(?:слово|фраза)\\s+(?:для записи|записи|диктофона)").containsMatchIn(s)
        ) {
            if (Regex("убери|сбрось|удали|очисти|сними|отмени|забудь").containsMatchIn(s)) {
                return Cmd(Action.CLEAR_WORD)
            }
            if (Regex("включ|актив|разреши|позволь").containsMatchIn(s) && newWord(s) == null) {
                return Cmd(Action.ENABLE)
            }
            if (Regex("выключ|отключ|запрети|не слуша(?:й|ть)").containsMatchIn(s) && newWord(s) == null) {
                return Cmd(Action.DISABLE)
            }
            newWord(s)?.let { return Cmd(Action.SET_WORD, it) }
            return Cmd(Action.STATUS)
        }

        if (!isAbout(s)) return null

        // ---- ограничение длины: «запись максимум 5 минут» ----
        limit(s)?.let { return Cmd(Action.LIMIT, minutes = it) }

        // ---- стоп ----
        if (Regex("стоп|останов|прекрати|выключ|закончи|заверши|конец|хватит|перестань|отбой|сохрани")
                .containsMatchIn(s)
        ) {
            if (Regex("запис|диктофон|пишешь|писать").containsMatchIn(s)) return Cmd(Action.STOP)
        }

        // ---- вопрос о состоянии ----
        if (Regex("идет запись|идёт запись|ты пишешь|пишешь меня|запись идет|запись идёт|" +
                "сколько (?:длится|идет|идёт) запись|диктофон (?:включен|работает)|статус записи|" +
                "записываешь|что с записью").containsMatchIn(s)
        ) {
            return Cmd(Action.STATUS)
        }

        // ---- список, прослушать, удалить ----
        if (Regex("удали|сотри|снеси").containsMatchIn(s) && Regex("запис").containsMatchIn(s)) {
            return Cmd(Action.DELETE_LAST)
        }
        if (Regex("включи|прослушай|послушай|проиграй|покажи|открой").containsMatchIn(s) &&
            Regex("последн|свеж|крайн").containsMatchIn(s) && Regex("запис").containsMatchIn(s)
        ) {
            return Cmd(Action.PLAY_LAST)
        }
        if (Regex("какие записи|список записей|покажи записи|мои записи|что записал|сколько записей")
                .containsMatchIn(s)
        ) {
            return Cmd(Action.LIST)
        }

        // ---- какой диктофон пишет: системный или встроенный ----
        val srcVerb = "(?:записывай|переключи(?:сь)?|используй|поставь|сделай|давай)"
        if (Regex("$srcVerb\\s+(?:мне\\s+)?(?:на\\s+)?(?:через\\s+)?системн").containsMatchIn(s)) {
            return Cmd(Action.SOURCE_SYSTEM)
        }
        if (Regex("$srcVerb\\s+(?:мне\\s+)?(?:на\\s+)?(?:через\\s+)?" +
                "(?:свой|встроенн[а-я]*|внутренн[а-я]*)").containsMatchIn(s)
        ) {
            return Cmd(Action.SOURCE_BUILTIN)
        }

        // ---- старт ----
        if (Regex("включ|начн|старт|запусти|пиши|записыва|сними звук|пиши меня|диктофон")
                .containsMatchIn(s)
        ) {
            return Cmd(Action.START)
        }
        if (Regex("^(?:диктофон|запись)$").matches(s)) return Cmd(Action.START)
        return Cmd(Action.STATUS)
    }

    /** Новое кодовое слово из фразы: «кодовое слово пиши меня», «кодовое слово: запись». */
    fun newWord(s0: String): String? {
        val s = s0.lowercase(Locale.ROOT).replace('ё', 'е')
        val m = Regex(
            "кодов[а-я]*\\s+(?:слово|слову|словом|слове|фразу|фразе|фразой)\\s*" +
                "(?:для\\s+записи|диктофона|записи)?\\s*" +
                "(?:будет|сделай|поставь|смени|измени|назначь|запомни)?\\s*[:—\\-]?\\s*(.+)$"
        ).find(s) ?: Regex(
            "(?:слово|фраза)\\s+(?:для\\s+записи|записи|диктофона)\\s*[:—\\-]?\\s*(.+)$"
        ).find(s) ?: return null
        var w = m.groupValues[1].trim()
        w = Regex("^\\s*(?:это|теперь|равно|\\-)\\s+").replace(w, "")
        w = w.trim(' ', '.', ',', '!', '?', ':', '"', '«', '»')
        // «назови кодовое слово» — это вопрос, а не новое слово
        if (w.isEmpty() || Regex("^(какое|какая|сейчас|уже|есть|нет)$").matches(w)) return null
        return w.takeIf { CodeWords.normalize(it).length >= 3 }
    }

    /** «запись максимум 5 минут», «ограничь запись десятью минутами». */
    fun limit(s: String): Int? {
        if (!Regex("максимум|максимальн|огранич|не дольше|не больше|длиной|длительность|на\\s+\\d+\\s+минут")
                .containsMatchIn(s)
        ) return null
        if (!Regex("запис|диктофон").containsMatchIn(s)) return null
        val m = Regex("(\\d+)\\s*(?:минут|минута|минуты|мин)").find(s)
            ?: Regex("(одн|дв|тр|пят|деся|пятнадц|двадцат|тридцат)[а-я]*\\s*(?:минут|минуты)").find(s)
        val n = m?.let {
            it.groupValues[1].toIntOrNull() ?: when {
                it.groupValues[1].startsWith("одн") -> 1
                it.groupValues[1].startsWith("дв") -> 2
                it.groupValues[1].startsWith("тр") -> 3
                it.groupValues[1].startsWith("пят") && !it.groupValues[1].startsWith("пятнад") -> 5
                it.groupValues[1].startsWith("пятнад") -> 15
                it.groupValues[1].startsWith("деся") -> 10
                it.groupValues[1].startsWith("двадцат") -> 20
                it.groupValues[1].startsWith("тридцат") -> 30
                else -> null
            }
        }
        if (n != null) return n.coerceIn(1, 180)
        // «запись максимум час» (с цифрой и без)
        Regex("(\\d+)\\s*час").find(s)?.let { h ->
            val hours = h.groupValues[1].toIntOrNull() ?: return@let
            return (hours * 60).coerceIn(1, 600)
        }
        if (Regex("полчаса|половина часа").containsMatchIn(s)) return 30
        if (Regex("(?:^|\\s)час(?:\\s|$)").containsMatchIn(s)) return 60
        return null
    }

    // ------------------------------------------------------------------ ответы

    fun limitLabel(minutes: Int): String = when {
        minutes >= 60 && minutes % 60 == 0 -> {
            val h = minutes / 60
            "$h ${TextTools.plural(h.toLong(), "час", "часа", "часов")}"
        }
        else -> "$minutes ${TextTools.plural(minutes.toLong(), "минуту", "минуты", "минут")}"
    }

    /** Длительность записи в человеческом виде: «1 мин 05 с». */
    fun durationLabel(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val m = s / 60
        val rest = s % 60
        if (m <= 0) return "$rest с"
        val restText = if (rest < 10) "0$rest" else "$rest"
        return "$m мин $restText с"
    }

    /**
     * Ответ на «что с диктофоном»: кодовое слово, ограничение, идёт ли запись.
     */
    fun status(
        words: List<String>, enabled: Boolean, limitMin: Int,
        recording: Boolean, elapsedSec: Long, system: Boolean = false
    ): String {
        val rec = if (recording) " Сейчас идёт запись — ${durationLabel(elapsedSec)}." else ""
        return buildString {
            append("Диктофон: ")
            append(if (recording) "пишу звук." else if (enabled) "готов к записи." else "выключен.")
            append(if (system) " Записывает системный диктофон телефона." else " Пишет встроенный диктофон Джарвиса.")
            append(rec)
            if (words.isNotEmpty()) {
                append(" Кодовое слово: «").append(words.joinToString("», «")).append("» — ")
                append(if (enabled) "скажете его, и я начну писать звук." else "но запись по слову выключена.")
            } else {
                append(" Кодового слова нет: задайте его фразой «кодовое слово: пиши меня».")
            }
            append(" Одна запись — не длиннее ").append(limitLabel(limitMin)).append(".")
        }
    }

    fun help(): String = """
        Диктофон, сэр:
        • «включи диктофон», «начни запись», «пиши меня» — пишу звук с микрофона
        • «стоп запись», «останови запись», «выключи диктофон» — сохранить
        • «кодовое слово: пиши меня» — своя фраза, услышав которую я сам начну
          запись (даже когда приложение закрыто); слово выбираете вы сами
        • «какое кодовое слово», «убери кодовое слово» — спросить и сбросить
        • «запись максимум 5 минут» — ограничение длины одной записи
        • «покажи записи», «включи последнюю запись», «удали последнюю запись»
        • «записывай системным диктофоном» / «записывай встроенным» — открывать
          диктофон телефона или писать самому Джарвису (по умолчанию — системный)
        Записи сохраняются в «Музыка/Jarvis» (на Android 8–9 — в папку Jarvis),
        во время записи в уведомлении есть кнопка «Остановить».
    """.trimIndent()
}
