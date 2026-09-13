package kz.jarvis.app

import java.util.Locale

/**
 * Эмоциональная озвучка Джарвиса.
 *
 * Системный TextToSpeech говорит ровно: он не умеет «радоваться» сам.
 * Поэтому эмоцию задаём мы — чуть меняем высоту тона, скорость речи
 * и паузу после фразы. Источник:
 *  1. явная метка модели в начале ответа: «[радость] Системы в норме»;
 *  2. эвристика по тексту («к сожалению», «ура», «не смог»…);
 *  3. иначе — спокойный дворецкий.
 *
 * Метка не произносится и не показывается в чате: [visible] её снимает.
 * Множители нарочно небольшие: Джарвис остаётся низким и собранным,
 * даже когда «рад» или «грустит».
 *
 * Файл не зависит от Android — покрыт scripts/test-logic.sh.
 */
object Emotion {

    enum class Kind {
        CALM, HAPPY, AMUSED, CONCERNED, SAD, EXCITED, URGENT, PROUD, APOLOGY
    }

    class Style(
        val kind: Kind,
        val id: String,
        val name: String,
        val hint: String,
        /** Множитель к высоте тона профиля. */
        val pitchMul: Float,
        /** Множитель к скорости речи профиля. */
        val rateMul: Float,
        /** Пауза после этой реплики, если дальше ещё есть куски. */
        val pauseMs: Int
    )

    data class Utterance(
        val spoken: String,
        val kind: Kind,
        val pitchMul: Float,
        val rateMul: Float,
        val pauseMs: Int
    )

    val STYLES: Map<Kind, Style> = linkedMapOf(
        Kind.CALM to Style(
            Kind.CALM, "calm", "спокойно",
            "Ровный дворецкий: тон и темп как в профиле.",
            1.00f, 1.00f, 90
        ),
        Kind.HAPPY to Style(
            Kind.HAPPY, "happy", "радость",
            "Чуть выше и живее — хорошие новости.",
            1.07f, 1.06f, 70
        ),
        Kind.AMUSED to Style(
            Kind.AMUSED, "amused", "ирония",
            "Лёгкая усмешка: тон чуть выше, пауза после фразы.",
            1.05f, 1.03f, 110
        ),
        Kind.CONCERNED to Style(
            Kind.CONCERNED, "concerned", "тревога",
            "Тише и медленнее — есть нюанс, на который стоит взглянуть.",
            0.95f, 0.93f, 140
        ),
        Kind.SAD to Style(
            Kind.SAD, "sad", "грусть",
            "Ниже и неторопливее — сочувствие, плохие новости.",
            0.91f, 0.88f, 180
        ),
        Kind.EXCITED to Style(
            Kind.EXCITED, "excited", "восторг",
            "Ярче и быстрее, но всё ещё Джарвис, а не диктор рекламы.",
            1.10f, 1.12f, 55
        ),
        Kind.URGENT to Style(
            Kind.URGENT, "urgent", "срочно",
            "Собранно и быстрее: нужно действовать сейчас.",
            1.03f, 1.14f, 40
        ),
        Kind.PROUD to Style(
            Kind.PROUD, "proud", "гордость",
            "Чуть ниже и ровнее — доклад о выполненной работе.",
            0.96f, 0.97f, 120
        ),
        Kind.APOLOGY to Style(
            Kind.APOLOGY, "apology", "извинение",
            "Ниже и медленнее: не вышло, прошу прощения.",
            0.94f, 0.90f, 160
        )
    )

    fun style(kind: Kind): Style = STYLES[kind] ?: STYLES.getValue(Kind.CALM)

    fun byId(id: String?): Style =
        STYLES.values.firstOrNull { it.id == id } ?: STYLES.getValue(Kind.CALM)

    // -------------------------------------------------------------- метки

    /**
     * Метка модели: `[радость]`, `[эмоция: грусть]`, `[emotion: happy]`.
     * Ищем и латинские, и русские скобки — модели иногда путают.
     */
    private val TAG = Regex(
        "[\\[【]\\s*(?:эмоция|emotion)?\\s*[:=]?\\s*([^\\]\\】\\n]{1,40})\\s*[\\]】]",
        RegexOption.IGNORE_CASE
    )

    /** Имя эмоции → вид. Сначала более специфичные ключи. */
    private val ALIASES: List<Pair<Regex, Kind>> = listOf(
        Regex("извин|прости|виноват|apology|sorry") to Kind.APOLOGY,
        Regex("срочн|немедленн|критич|алерт|alert|urgent|опасно") to Kind.URGENT,
        Regex("восторг|воодушев|excited|thrill|вау|ура|потрясающ|фантастич") to Kind.EXCITED,
        Regex("груст|печал|увы|жаль\\b|sadness|sad") to Kind.SAD,
        Regex("тревог|беспоко|осторож|concern|worr|caution") to Kind.CONCERNED,
        Regex("ирони|усмеш|сарказ|шутл|забавн|amused|wry|sarcas") to Kind.AMUSED,
        Regex("горд|уверенн|доклад|proud|confident") to Kind.PROUD,
        Regex("радост|весел|happy|joy|glad|cheerful") to Kind.HAPPY,
        Regex("спокойн|нейтрал|обычн|calm|neutral|default") to Kind.CALM
    )

    /** Разбирает «радость», «с иронией», «emotion: sad». null — не узнали. */
    fun parseKind(raw: String): Kind? {
        var s = raw.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        if (s.isEmpty()) return null
        s = s.replace(Regex("^(?:с|со|the|a)\\s+"), "")
        s = s.replace(Regex("\\s+(?:тоном|голосом|эмоцией|эмоциею|emotion)$"), "")
        s = s.trim().trim('.', '!', '?', ',', ':', '-', '—', ' ')
        if (s.isEmpty()) return null
        for ((re, kind) in ALIASES) {
            if (re.containsMatchIn(s)) return kind
        }
        return STYLES.values.firstOrNull { it.id == s || it.name == s }?.kind
    }

    /** Текст для чата и сферы: метки сняты, пробелы схлопнуты. */
    fun visible(raw: String): String {
        val t = TAG.replace(raw, " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n+ *"), "\n")
            .trim()
        return t.ifBlank { raw.trim() }
    }

    /**
     * Реплика → куски озвучки. Обычно один кусок; для «проверь эмоции»
     * модель (или демо) может поставить несколько меток подряд.
     */
    fun chunks(raw: String, enabled: Boolean): List<Utterance> {
        val s = raw.trim()
        if (s.isEmpty()) return emptyList()
        val matches = TAG.findAll(s).toList()
        if (matches.isEmpty()) {
            val kind = if (enabled) detect(s) else Kind.CALM
            return listOf(utter(s, kind))
        }
        val out = ArrayList<Utterance>()
        val firstAt = matches.first().range.first
        if (firstAt > 0) {
            val pre = s.substring(0, firstAt).trim()
            if (pre.isNotEmpty()) {
                val kind = if (enabled) detect(pre) else Kind.CALM
                out.add(utter(pre, kind))
            }
        }
        for (i in matches.indices) {
            val m = matches[i]
            val tagged = parseKind(m.groupValues[1])
            val start = m.range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else s.length
            val body = s.substring(start, end)
                .trim()
                .trimStart(',', '.', '—', '-', ':', ';', ' ')
            if (body.isEmpty()) continue
            val kind = when {
                !enabled -> Kind.CALM
                tagged != null -> tagged
                else -> detect(body)
            }
            out.add(utter(body, kind))
        }
        return if (out.isEmpty()) listOf(utter(visible(s), if (enabled) detect(s) else Kind.CALM))
        else out
    }

    private fun utter(text: String, kind: Kind): Utterance {
        val st = style(kind)
        return Utterance(text.trim(), kind, st.pitchMul, st.rateMul, st.pauseMs)
    }

    /** Тон/темп после применения эмоции к профилю (с границами VoiceProfile). */
    fun applyTone(basePitch: Float, baseRate: Float, kind: Kind): Pair<Float, Float> {
        val st = style(kind)
        return VoiceProfile.clampPitch(basePitch * st.pitchMul) to
            VoiceProfile.clampRate(baseRate * st.rateMul)
    }

    // ----------------------------------------------------------- эвристика

    /**
     * Угадывает эмоцию по тексту, когда метки нет.
     * Порог 2: одно «сэр» или один «!» Джарвиса не перекрашивают.
     */
    fun detect(text: String): Kind {
        val s = text.lowercase(Locale.ROOT).replace('ё', 'е')
        if (s.isBlank()) return Kind.CALM
        val score = HashMap<Kind, Int>()
        fun add(k: Kind, n: Int) {
            if (n == 0) return
            score[k] = (score[k] ?: 0) + n
        }

        val bangs = s.count { it == '!' }
        when {
            bangs >= 2 -> add(Kind.EXCITED, 2)
            bangs == 1 -> add(Kind.HAPPY, 1)
        }
        if (s.contains("...")) add(Kind.SAD, 1)

        fun hit(kind: Kind, weight: Int, vararg words: String) {
            for (w in words) if (s.contains(w)) add(kind, weight)
        }

        hit(
            Kind.APOLOGY, 3,
            "извини", "прости", "прошу прощения", "не смог", "не удалось",
            "не получилось", "виноват", "ошибк"
        )
        hit(
            Kind.URGENT, 3,
            "срочно", "немедленно", "опасно", "критическ", "тревога", "горит"
        )
        hit(
            Kind.EXCITED, 3,
            "невероятн", "потрясающ", "фантастич", "вау", "ура", "сенсац"
        )
        hit(
            Kind.SAD, 3,
            "к сожалению", "увы", "жаль", "печальн", "грустн", "не вышло"
        )
        hit(
            Kind.CONCERNED, 2,
            "осторожно", "внимание", "есть нюанс", "не уверен", "странно",
            "подозрительн", "риск", "проблем"
        )
        hit(
            Kind.AMUSED, 2,
            "между нами", "забавно", "смешно", "шутк", "анекдот", "признаюсь"
        )
        hit(
            Kind.HAPPY, 2,
            "отличн", "прекрасн", "замечательн", "великолепн", "рад сообщить",
            "хорошие новости", "здорово", "супер"
        )
        hit(
            Kind.PROUD, 2,
            "системы в норме", "задача выполнена", "всё по плану",
            "как приказывали", "докладываю", "готов к работе"
        )

        val best = score.maxByOrNull { it.value } ?: return Kind.CALM
        return if (best.value >= 2) best.key else Kind.CALM
    }

    // ---------------------------------------------------- голосовые команды

    sealed class Cmd {
        class Set(val on: Boolean) : Cmd()
        object Status : Cmd()
        object Demo : Cmd()
        class Force(val kind: Kind) : Cmd()
    }

    /** Разбирает фразу про эмоции. null — команда не про них. */
    fun parse(raw: String): Cmd? {
        val s = raw.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        if (s.isEmpty()) return null

        val about = Regex(
            "эмоци|эмоциональн|с чувством|монотонн|без эмоц|говори ровно"
        ).containsMatchIn(s)

        if (about && Regex("проверь|тест|покажи|как звуч|послушай|пример|демо")
                .containsMatchIn(s)
        ) return Cmd.Demo

        if (about && Regex("включен|выключен|статус|состояние|работает|как сейчас|активен")
                .containsMatchIn(s)
        ) return Cmd.Status

        if (about) {
            val on = Regex(
                "включ|вруб|актив|говори с эмоц|с эмоциями|эмоционально|с чувством"
            ).containsMatchIn(s)
            val off = Regex(
                "выключ|отключ|выруб|без эмоц|монотон|говори ровно|убери эмоц|без чувства"
            ).containsMatchIn(s)
            return when {
                on && !off -> Cmd.Set(true)
                off && !on -> Cmd.Set(false)
                else -> Cmd.Status
            }
        }

        // «говори радостно», «скажи с иронией» — разовый пример этой эмоции.
        // «говори ниже/быстрее» сюда не попадает: parseKind их не узнает.
        Regex("^(?:говори|скажи|озвучь)(?:\\s+это)?\\s+(?:с\\s+|со\\s+)?(.+)$")
            .find(s)?.let { m ->
                val kind = parseKind(m.groupValues[1]) ?: return@let
                if (kind != Kind.CALM) return Cmd.Force(kind)
            }
        return null
    }

    fun sample(kind: Kind): String = when (kind) {
        Kind.CALM -> "Системы в норме, сэр. Готов к работе."
        Kind.HAPPY -> "Отличные новости, сэр! Всё прошло лучше, чем я рассчитывал."
        Kind.AMUSED -> "Между нами, сэр, это было почти элегантно."
        Kind.CONCERNED -> "Сэр, тут есть один нюанс, на который стоит обратить внимание."
        Kind.SAD -> "К сожалению, сэр, на этот раз не вышло."
        Kind.EXCITED -> "Невероятно, сэр! Это действительно впечатляет."
        Kind.URGENT -> "Сэр, это срочно. Нужно действовать сейчас."
        Kind.PROUD -> "Задача выполнена, сэр. Могу доложить: всё по плану."
        Kind.APOLOGY -> "Прошу прощения, сэр. Не смог выполнить это так, как хотелось бы."
    }

    /** Скрипт «проверь эмоции»: несколько меток, озвучка сама разложит по тонам. */
    fun demoText(): String = STYLES.keys.joinToString("\n") { kind ->
        "[${style(kind).name}] ${sample(kind)}"
    }

    fun describe(on: Boolean): String = if (on) {
        "Эмоциональная озвучка включена, сэр: тон и темп подстраиваются под ответ. " +
            "Радость, ирония, тревога, грусть, восторг, срочность, гордость, извинение. " +
            "Скажите «проверь эмоции», чтобы послушать, или «выключи эмоции», чтобы говорить ровно."
    } else {
        "Эмоциональная озвучка выключена, сэр. Говорю ровно, как в профиле голоса. " +
            "Скажите «включи эмоции», чтобы тон снова жил вместе с ответом."
    }
}
