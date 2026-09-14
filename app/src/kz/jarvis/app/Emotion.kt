package kz.jarvis.app

import java.util.Locale

/**
 * Эмоции в речи Джарвиса — то, что делает голос живым.
 *
 * Проблема, которую решает файл: системный синтезатор читает фразу ОДНИМ
 * тоном и одним темпом от начала до конца. Даже с идеальным тембром это звучит
 * как автоответчик: «Принято сэр ищу материал потом доложу».
 *
 * Здесь текст разбирается так, как это делает живая речь:
 *  • фраза режется на короткие куски — по точкам, запятым, тире и многоточиям,
 *    между кусками появляются паузы;
 *  • у КАЖДОГО куска свой тон (pitch), темп (rate) и громкость: восклицание
 *    звучит выше и быстрее, многоточие — тише и медленнее, вопрос — с подъёмом
 *    в конце, важное слово («сэр», «внимание», «ошибка») выделяется;
 *  • настроение ответа определяется по самому тексту ([detect]) и накладывается
 *    на куски своим «оттенком» ([tint]) — радость, тревога, ирония, досада;
 *  • внутри длинной фразы есть интонационный контур: начало чуть выше, конец
 *    чуть ниже — речь перестаёт быть «плоской».
 *
 * [plan] возвращает готовый список кусков, который умеет произносить
 * `SpeechChain` (по одному высказыванию на кусок, со своей паузой между ними).
 * При уровне «выключено» возвращается ровно один кусок — то есть прежнее
 * поведение «одна фраза одним тоном».
 *
 * Файл не зависит от Android — покрыт scripts/test-logic.sh.
 */
object Emotion {

    // ------------------------------------------------------------- настроение

    /** Настроение ответа: определяет «оттенок» голоса. */
    enum class Mood(val title: String, val hint: String) {
        CALM("спокойствие", "ровный рабочий тон — так звучат доклады и отчёты"),
        WARM("тепло", "мягче и чуть ниже: приветствия, благодарности, забота"),
        JOY("радость", "выше и живее: «готово», «отлично», удачные новости"),
        EXCITED("восторг", "самый высокий и быстрый: несколько восклицаний подряд"),
        PROUD("гордость", "чуть выше и неторопливо: «как я и говорил»"),
        CURIOUS("интерес", "вопрос с подъёмом в конце"),
        IRONIC("ирония", "неторопливо и с подъёмом: «разумеется, сэр»"),
        CONCERN("тревога", "ниже и тише: предупреждения о проблемах"),
        WARNING("предупреждение", "низко, веско и с паузой: то, что важно не пропустить"),
        STERN("строгость", "жёстко и без лишних нот: приказы и запреты"),
        SAD("грусть", "медленно и тише: «к сожалению», «увы»"),
        ERROR("досада", "ниже и медленнее: «не получилось», «ошибка»")
    }

    /** Насколько сильно Джарвис играет интонацией. */
    enum class Level(val id: Int, val title: String, val factor: Float, val hint: String) {
        OFF(
            0, "Выключены", 0f,
            "Одна ровная интонация на всю фразу — как диктофон."
        ),
        LIGHT(
            1, "Слегка", 0.6f,
            "Тон и темп меняются мягко, паузы короткие. Аккуратно для тех, кому важна разборчивость."
        ),
        FULL(
            2, "Выразительно", 1f,
            "Слышно настроение: радость, тревога, ирония, вопрос. У каждой фразы свой тон, темп и пауза."
        );

        companion object {
            val DEFAULT = FULL
            fun byId(id: Int): Level = values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    /** Оттенок голоса: насколько поднять тон, ускорить речь и как менять громкость. */
    class Tint(val pitch: Float, val rate: Float, val volume: Float)

    /**
     * Оттенок каждого настроения. Значения — «сколько добавить к профилю
     * голоса»; при уровне «слегка» они умножаются на [Level.factor].
     */
    fun tint(m: Mood): Tint = when (m) {
        Mood.CALM -> Tint(0f, 0f, 1.00f)
        Mood.WARM -> Tint(+0.05f, -0.05f, 1.00f)
        Mood.JOY -> Tint(+0.11f, +0.07f, 1.00f)
        Mood.EXCITED -> Tint(+0.17f, +0.13f, 1.00f)
        Mood.PROUD -> Tint(+0.06f, -0.07f, 1.00f)
        Mood.CURIOUS -> Tint(+0.08f, +0.01f, 1.00f)
        Mood.IRONIC -> Tint(+0.07f, -0.06f, 0.99f)
        Mood.CONCERN -> Tint(-0.05f, -0.05f, 0.93f)
        Mood.WARNING -> Tint(-0.04f, -0.09f, 1.00f)
        Mood.STERN -> Tint(-0.06f, -0.04f, 1.00f)
        Mood.SAD -> Tint(-0.07f, -0.11f, 0.90f)
        Mood.ERROR -> Tint(-0.08f, -0.08f, 0.96f)
    }

    // ------------------------------------------------------------------ кусок

    /**
     * Кусок речи: что сказать и КАК именно.
     *
     * @param pitch         абсолютная высота тона (уже сложена с профилем голоса)
     * @param rate          абсолютная скорость речи
     * @param volume        громкость куска (0.35…1.0)
     * @param pauseAfterMs  пауза перед следующим куском
     */
    class Chunk(
        val text: String,
        val pitch: Float,
        val rate: Float,
        val volume: Float,
        val pauseAfterMs: Int
    )

    // -------------------------------------------------------------- разбор фраз

    private const val PAUSE_COMMA = 90
    private const val PAUSE_DASH = 150
    private const val PAUSE_SENTENCE = 220
    private const val PAUSE_ELLIPSIS = 300
    private const val PAUSE_LONG = 90      // вынужденная пауза внутри очень длинной фразы

    private class Piece(val text: String, val pause: Int, val sentenceEnd: Boolean)

    /** Сколько «весит» общее настроение ответа по сравнению с настроением куска. */
    private const val BG = 0.40f

    /**
     * Чистит текст для произнесения: убирает Markdown, эмодзи и «мусорные»
     * знаки, которые синтезатор читает вслух («звёздочка», «решётка»).
     */
    fun cleanForSpeech(raw: String): String {
        var t = raw
        // ссылки [текст](адрес) → текст
        t = t.replace(Regex("\\[([^\\]]{1,80})]\\([^)]{0,300}\\)"), "$1")
        // Markdown-акценты и списки
        t = t.replace(Regex("(?m)^\\s*[*•·]\\s+"), " ")
        t = t.replace(Regex("(?m)^\\s*#+\\s*"), " ")
        t = t.replace(Regex("(?m)^\\s*>\\s*"), " ")
        t = t.replace("**", " ").replace("__", " ").replace("```", " ")
        t = t.replace(Regex("[*#`~|^<>\u00A0]"), " ")
        // эмодзи и прочие символы-картинки
        t = t.replace(Regex("[\\p{So}\\p{Sk}\\p{Cs}\\p{Cn}\\p{Co}]+"), " ")
        // перенос строки после слова — это конец предложения (пауза),
        // перенос после знака — просто пробел
        t = t.replace(Regex("([\\p{L}\\p{N}])\\s*\\n+\\s*"), "$1. ")
        t = t.replace(Regex("\\s*\\n+\\s*"), " ")
        // «..» из склеенных предложений — один знак
        t = t.replace(Regex("([.!?…])\\s*[.!?…]\\s*(?=[\\p{Lu}\\p{Ll}])"), "$1 ")
        t = t.replace(Regex("\\s{2,}"), " ")
        // пробел, оставшийся от разметки, перед знаком не читаем
        t = t.replace(Regex("\\s+([,.;:!?…])"), "$1")
        return t.trim().trimStart('.', ',', ';', ':', '-').trim()
    }

    /** Режет фразу на куски: точки/восклицания — конец предложения, запятые — вдох. */
    private fun split(text: String): List<Piece> {
        val out = ArrayList<Piece>()
        val sb = StringBuilder()

        fun flush(pause: Int, sentenceEnd: Boolean) {
            // запятую и «;» оставляем: по ним движок сам делает интонацию
            // продолжения, а вот тире и двоеточие на конце звучат странно
            val s = sb.toString().trimEnd(' ', ':', '—', '-').trim()
            sb.setLength(0)
            if (s.isNotEmpty()) out.add(Piece(s, pause, sentenceEnd))
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val before = if (i > 0) text[i - 1] else ' '
            val after = if (i + 1 < text.length) text[i + 1] else ' '

            when {
                ch == '.' || ch == '!' || ch == '?' || ch == '…' -> {
                    sb.append(ch)
                    // «!!!», «?!», «...» — один знак конца предложения
                    var j = i + 1
                    while (j < text.length && (text[j] == '.' || text[j] == '!' || text[j] == '?' || text[j] == '…')) {
                        sb.append(text[j]); j++
                    }
                    // «2.5» и «31.12» — это не конец предложения
                    val decimal = before.isDigit() && (j < text.length && text[j].isDigit())
                    if (decimal) { i = j; continue }
                    val ellipsis = sb.endsWith("…") || sb.endsWith("...")
                    flush(if (ellipsis) PAUSE_ELLIPSIS else PAUSE_SENTENCE, true)
                    i = j
                    continue
                }

                ch == ',' || ch == ';' -> {
                    // «1,5» — десятичная дробь, не вдох
                    val decimal = before.isDigit() && after.isDigit()
                    if (decimal) sb.append(ch) else flush(PAUSE_COMMA, false)
                }

                ch == '—' || ch == '-' -> {
                    val dash = before == ' ' && after == ' '
                    if (dash) flush(PAUSE_DASH, false) else sb.append(ch)
                }

                ch == '\n' -> flush(PAUSE_SENTENCE, true)

                else -> sb.append(ch)
            }
            i++
        }
        flush(0, true)
        return out
    }

    /** Делит слишком длинные куски по словам: короткие высказывания звучат живее. */
    private fun wrap(pieces: List<Piece>): List<Piece> {
        val out = ArrayList<Piece>()
        for (p in pieces) {
            if (p.text.length <= 110) { out.add(p); continue }
            val words = p.text.split(' ')
            val sb = StringBuilder()
            for (w in words) {
                if (sb.isNotEmpty() && sb.length + w.length + 1 > 100) {
                    out.add(Piece(sb.toString().trim(), PAUSE_LONG, false))
                    sb.setLength(0)
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(w)
            }
            if (sb.isNotEmpty()) {
                // последний кусок длинной фразы сохраняет её «настоящую» паузу
                out.add(Piece(sb.toString().trim(), p.pause, p.sentenceEnd))
            }
        }
        return out
    }

    // -------------------------------------------------------- определение эмоции

    /** Слова-маркеры: сначала самые «громкие» состояния. */
    private val MARKERS: List<Pair<Mood, List<String>>> = listOf(
        Mood.ERROR to listOf(
            "ошибка", "не получилось", "не удалось", "не смог", "не вышло", "сбой",
            "провал", "не сработало", "не поддерживается", "не хватает прав", "нет доступа"
        ),
        Mood.WARNING to listOf(
            "внимание", "предупрежда", "осторожно", "опасно", "срочно", "важно не",
            "не забудь", "заряд", "риск", "угроза"
        ),
        Mood.CONCERN to listOf(
            "тревог", "беспоко", "боюсь", "к сожалению", "проблем", "подозрительн",
            "не отвечает", "непонятн"
        ),
        Mood.SAD to listOf("жаль", "печаль", "сожале", "увы", "грустно", "не повезло"),
        Mood.EXCITED to listOf("ура", "невероятно", "потрясающе", "восторг", "блестящ"),
        Mood.JOY to listOf(
            "отлично", "прекрасно", "замечательно", "здорово", "супер", "класс",
            "поздравля", "рад ", "радост", "получилось", "готово", "сделано",
            "выполнено", "успешно", "нашел", "нашёл"
        ),
        Mood.IRONIC to listOf(
            "как же", "разумеется", "конечно же", "если позволите", "хех", "кхм",
            "шутк", "иронич", "сарказ", "какая неожиданность"
        ),
        Mood.PROUD to listOf("как я и говорил", "я же говорил", "как и ожидалось", "моя работа"),
        Mood.WARM to listOf(
            "пожалуйста", "доброе утро", "добрый день", "добрый вечер", "спокойной ночи",
            "приятно", "спасибо", "всегда рад", "береги"
        )
    )

    /**
     * Настроение текста. Порядок проверок — от «громкого» к тихому: если в ответе
     * и ошибка, и благодарность, голос всё равно звучит настороженно.
     */
    fun detect(text: String): Mood {
        val t = text.lowercase(Locale.ROOT).replace('ё', 'е')
        if (t.isBlank()) return Mood.CALM

        val bangs = t.count { it == '!' }
        val questions = t.count { it == '?' }
        val ellipsis = t.contains("…") || t.contains("...")

        for ((mood, words) in MARKERS) {
            if (words.any { t.contains(it) }) {
                // два и более восклицания «с радостными» словами — уже восторг
                if (mood == Mood.JOY && bangs >= 2) return Mood.EXCITED
                return mood
            }
        }
        if (bangs >= 2) return Mood.EXCITED
        if (ellipsis) return Mood.CONCERN
        if (questions > 0) return Mood.CURIOUS
        return Mood.CALM
    }

    /** Слова, которые голос выделяет: смысловой центр фразы. */
    private val POWER_WORDS = listOf(
        "сэр", "готово", "внимание", "ошибка", "предупреждаю", "сожалею", "отлично",
        "опасно", "срочно", "важно", "немедленно", "спасибо", "выполнено", "запрещено"
    )

    private fun hasCapsWord(t: String): Boolean =
        Regex("[А-ЯЁA-Z]{3,}").containsMatchIn(t)

    private fun hasPowerWord(t: String): Boolean {
        val low = t.lowercase(Locale.ROOT).replace('ё', 'е')
        return POWER_WORDS.any { w ->
            val re = Regex("(^|[^а-яa-z])" + Regex.escape(w) + "([^а-яa-z]|$)")
            re.containsMatchIn(low)
        }
    }

    /** Мелкие поправки внутри куска: восклицание, вопрос, многоточие, важное слово. */
    private class Local(var pitch: Float, var rate: Float, var volume: Float)

    private fun local(piece: String): Local {
        val t = piece.trim()
        val l = Local(0f, 0f, 0f)   // volume — смещение от обычной громкости
        when {
            t.count { it == '!' } >= 2 -> { l.pitch += 0.09f; l.rate += 0.05f }
            t.contains('!') -> { l.pitch += 0.05f; l.rate += 0.03f }
        }
        if (t.trimEnd().endsWith("?")) { l.pitch += 0.05f; l.rate -= 0.02f }
        if (t.endsWith("…") || t.endsWith("...")) { l.rate -= 0.09f; l.volume -= 0.09f }
        if (hasCapsWord(t)) { l.pitch += 0.05f; l.rate -= 0.05f }
        if (hasPowerWord(t)) { l.pitch += 0.04f; l.rate -= 0.02f; l.volume += 0.02f }
        return l
    }

    /**
     * Настроение всего ответа — преобладающее среди фраз. Так длинный ответ,
     * где один раз упомянуто «внимание», не становится целиком тревожным:
     * фон остаётся тем, чего в ответе больше.
     */
    private fun globalMood(text: String, pieces: List<Piece>): Mood {
        val counts = LinkedHashMap<Mood, Int>()
        for (p in pieces) {
            val m = detect(p.text)
            if (m != Mood.CALM) counts[m] = (counts[m] ?: 0) + 1
        }
        val top = counts.entries.maxByOrNull { it.value }
        return top?.key ?: detect(text)
    }

    // ------------------------------------------------------------------- план

    /**
     * Готовый план озвучки: что говорить, каким тоном, с какими паузами.
     *
     * @param basePitch тон из настроек (профиль «Джарвис» + ползунок)
     * @param baseRate  скорость речи из настроек
     * @param level     сила эмоций ([Level.OFF] — один кусок, как раньше)
     * @param mood      принудительное настроение («скажи радостно: …»), иначе
     *                  определяется по тексту
     */
    fun plan(
        raw: String,
        basePitch: Float,
        baseRate: Float,
        level: Level = Level.DEFAULT,
        mood: Mood? = null
    ): List<Chunk> {
        val text = cleanForSpeech(raw)
        if (text.isBlank()) return emptyList()

        val pitch0 = VoiceProfile.clampPitch(basePitch)
        val rate0 = VoiceProfile.clampRate(baseRate)

        if (level == Level.OFF) {
            return listOf(Chunk(text, pitch0, rate0, 1f, 0))
        }

        val pieces = wrap(split(text))
        if (pieces.isEmpty()) return listOf(Chunk(text, pitch0, rate0, 1f, 0))

        val k = level.factor
        val global = mood ?: globalMood(text, pieces)
        val base = tint(global)

        // интонационный контур: внутри предложения начало выше, конец ниже
        val contour = FloatArray(pieces.size)
        var start = 0
        for (i in pieces.indices) {
            if (pieces[i].sentenceEnd) {
                val n = i - start + 1
                for (j in start..i) {
                    val frac = if (n <= 1) 0f else (j - start).toFloat() / (n - 1)
                    contour[j] = 0.03f * (1f - 2f * frac)
                }
                start = i + 1
            }
        }

        val out = ArrayList<Chunk>(pieces.size)
        for ((i, p) in pieces.withIndex()) {
            val l = local(p.text)
            // спокойный кусок — это «без своего настроения»: он наследует
            // настроение всего ответа (иначе середина радостной фразы гасла бы)
            val own = if (i == 0) global else detect(p.text)
            val calm = mood == null && own == Mood.CALM && i > 0
            val pm = if (calm) global else (mood ?: own)
            val local0 = tint(pm)
            // фон ответа задаёт общее настроение, а своё настроение куска
            // звучит заметнее него — так слышно, где радость, а где тревога.
            // Спокойный кусок берёт фон вполсилы: он и должен звучать ровнее.
            val lw = when {
                mood != null -> 0.60f
                calm -> 0.30f
                pm == global -> 0.60f
                else -> 0.85f
            }
            // лёгкий разброс, чтобы одинаковые фразы не читались «под копирку»
            val jitter = (Math.floorMod(p.text.hashCode(), 7) - 3) / 250f   // ±0.012

            var pitch = pitch0 + k * (BG * base.pitch + lw * local0.pitch + l.pitch + contour[i] + jitter)
            var rate = rate0 + k * (BG * base.rate + lw * local0.rate + l.rate)
            var vol = 1f - (1f - base.volume) * BG - (1f - local0.volume) * lw + l.volume
            vol = 1f + (vol - 1f) * k

            // чуть больше вдоха перед «важным» и после вопроса
            var pause = p.pause
            if (hasPowerWord(p.text)) pause += 60
            if (pm == Mood.WARNING || pm == Mood.STERN) pause += 70
            pause = Math.round(pause * (0.55f + 0.45f * k))

            pitch = VoiceProfile.clampPitch(pitch)
            rate = VoiceProfile.clampRate(rate)

            out.add(
                Chunk(
                    text = p.text,
                    pitch = pitch,
                    rate = rate,
                    volume = vol.coerceIn(0.35f, 1f),
                    pauseAfterMs = pause
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------- голосом про эмоции

    sealed class Cmd {
        /** Включить/выключить уровень эмоций. */
        data class Set(val level: Level) : Cmd()
        /** «скажи радостно: …» — произнести фразу выбранной интонацией. */
        data class Say(val mood: Mood, val text: String) : Cmd()
        /** Как сейчас с интонацией. */
        object Info : Cmd()
        /** Показать разные интонации одной фразой. */
        object Test : Cmd()
    }

    /** Слова-интонации для «скажи <как>: <фраза>». */
    private val MOOD_WORDS: List<Pair<String, Mood>> = listOf(
        "радостно" to Mood.JOY,
        "с радостью" to Mood.JOY,
        "весело" to Mood.JOY,
        "восторженно" to Mood.EXCITED,
        "восхищенно" to Mood.EXCITED,
        "грустно" to Mood.SAD,
        "печально" to Mood.SAD,
        "с грустью" to Mood.SAD,
        "уныло" to Mood.SAD,
        "тревожно" to Mood.CONCERN,
        "обеспокоенно" to Mood.CONCERN,
        "взволнованно" to Mood.CONCERN,
        "строго" to Mood.STERN,
        "серьезно" to Mood.STERN,
        "сурово" to Mood.STERN,
        "иронично" to Mood.IRONIC,
        "с иронией" to Mood.IRONIC,
        "саркастично" to Mood.IRONIC,
        "нежно" to Mood.WARM,
        "ласково" to Mood.WARM,
        "тепло" to Mood.WARM,
        "мягко" to Mood.WARM,
        "спокойно" to Mood.CALM,
        "ровно" to Mood.CALM,
        "с интересом" to Mood.CURIOUS,
        "заинтересованно" to Mood.CURIOUS,
        "гордо" to Mood.PROUD,
        "предупреждающе" to Mood.WARNING,
        "грозно" to Mood.WARNING
    )

    /** Настроение по слову из команды («радостно», «с иронией»). */
    fun moodByWord(word: String): Mood? {
        val w = word.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        return MOOD_WORDS.firstOrNull { it.first == w }?.second
    }

    private fun parseSay(s: String): Cmd.Say? {
        val m = Regex("^(?:скажи|произнеси|проговори|повтори|прочитай)\\s+(?:мне\\s+)?(?:это\\s+)?(.+)$")
            .find(s) ?: return null
        var rest = m.groupValues[1].trim()
        rest = rest.removePrefix("пожалуйста ").trim()
        val word = MOOD_WORDS.firstOrNull { rest.startsWith(it.first) } ?: return null
        var body = rest.substring(word.first.length)
        body = body.trimStart(' ', ':', ',', ';', '—', '-', '.', '!', '?').trim()
        return Cmd.Say(word.second, body)
    }

    /**
     * Разбирает фразу про эмоции. null — команда не про эмоции.
     * Проверять её надо РАНЬШЕ команд про голос: «проверь эмоции» и
     * «проверь голос» — разные команды.
     */
    fun parse(raw: String): Cmd? {
        val s = raw.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        if (s.isEmpty()) return null

        parseSay(s)?.let { return it }

        if (Regex(
                "выключи эмоции|убери эмоции|без эмоций|эмоции не нужны|эмоции выключены?|" +
                    "говори ровно|говори монотонно|говори как робот|читай ровно|не выражай эмоции"
            ).containsMatchIn(s)
        ) return Cmd.Set(Level.OFF)

        if (Regex(
                "(?:по)?меньше эмоций|эмоции послабее|эмоции потише|слегка эмоциональн[а-я]*|" +
                    "умеренн[а-я]* эмоц|не переигрывай|сдержанн[а-я]* интонац|" +
                    "поменьше интонаций|интонации послабее"
            ).containsMatchIn(s)
        ) return Cmd.Set(Level.LIGHT)

        if (Regex(
                "включи эмоции|добавь эмоций|больше эмоций|эмоции посильнее|говори живее|" +
                    "говори эмоциональн[а-я]*|говори выразительн[а-я]*|говори с эмоциями|" +
                    "говори с интонацией|включи интонацию|оживи голос"
            ).containsMatchIn(s)
        ) return Cmd.Set(Level.FULL)

        if (Regex(
                "какие эмоции|эмоции включены|как (?:у тебя )?с эмоциями|что с интонацией|" +
                    "уровень эмоций|расскажи про эмоции|эмоции работают|какая интонация"
            ).containsMatchIn(s)
        ) return Cmd.Info

        if (Regex(
                "проверь эмоции|тест эмоций|покажи эмоции|продемонстрируй эмоции|" +
                    "скажи эмоционально|покажи интонации"
            ).containsMatchIn(s)
        ) return Cmd.Test

        return null
    }

    // -------------------------------------------------------------- текст и ответы

    /** Фраза-демонстрация: спокойствие, радость, вопрос, предупреждение и ирония. */
    fun demoText(level: Level = Level.DEFAULT): String {
        if (level == Level.OFF) return VoiceProfile.sampleText()
        return "Сэр, показываю интонации. Спокойно: системы в норме. " +
            "Радость: готово, всё получилось! Вопрос: что прикажете дальше? " +
            "Предупреждение: внимание, заряд почти на нуле. " +
            "И ирония: разумеется, сэр, как скажете…"
    }

    /** Короткая приветственная фраза о состоянии эмоций («как с интонацией?»). */
    fun describe(level: Level): String {
        val state = when (level) {
            Level.OFF -> "Эмоции выключены: говорю ровно, одной интонацией"
            Level.LIGHT -> "Эмоции включены слегка: тон и темп меняются мягко, паузы короткие"
            Level.FULL -> "Эмоции включены полностью: у каждой фразы свой тон, темп и пауза"
        }
        return "$state. Скажите «покажи эмоции» — продемонстрирую, " +
            "«скажи радостно: …» — произнесу вашу фразу нужной интонацией, " +
            "«выключи эмоции» — верну ровную речь."
    }
}
