package kz.jarvis.app

import java.util.Locale

/**
 * Голоса Джарвиса.
 *
 * Профиль — это тембр: высота тона (pitch), скорость речи (rate) и то, какой
 * системный голос предпочесть (мужской / женский). Android отдаёт разные
 * наборы голосов на разных прошивках, поэтому конкретный голос выбирается
 * оценкой [scoreVoice] по его имени и качеству, а пользователь всегда может
 * выбрать голос руками в настройках.
 *
 * Здесь же разбираются голосовые команды про голос («голос Джарвиса»,
 * «говори ниже», «проверь голос»).
 *
 * Файл не зависит от Android — покрыт scripts/test-logic.sh.
 */
object VoiceProfile {

    enum class Gender { MASCULINE, FEMININE, ANY }

    class Profile(
        val id: String,
        val name: String,
        /** Высота тона: 1.0 — как в системе, ниже — грубее. */
        val pitch: Float,
        /** Скорость речи: 1.0 — как в системе. */
        val rate: Float,
        val gender: Gender,
        val hint: String
    )

    /** Профиль по умолчанию — тот самый «Джарвис». */
    const val DEFAULT_ID = "jarvis"

    val ALL: List<Profile> = listOf(
        Profile(
            id = "jarvis",
            name = "J.A.R.V.I.S. — низкий и спокойный",
            pitch = 0.80f,
            rate = 1.02f,
            gender = Gender.MASCULINE,
            hint = "Тот самый дворецкий: мужской голос, тон ниже обычного, речь ровная и чуть быстрее."
        ),
        Profile(
            id = "stark",
            name = "Броня — глубокий, с металлом",
            pitch = 0.68f,
            rate = 0.94f,
            gender = Gender.MASCULINE,
            hint = "Самый низкий и неторопливый вариант. Включите «эффект брони» — добавится эхо."
        ),
        Profile(
            id = "agent",
            name = "Агент — деловой доклад",
            pitch = 0.90f,
            rate = 1.18f,
            gender = Gender.MASCULINE,
            hint = "Мужской голос, быстрый темп: удобно слушать длинные ответы и пересказ найденного."
        ),
        Profile(
            id = "friday",
            name = "Пятница — женский, мягкий",
            pitch = 1.06f,
            rate = 1.06f,
            gender = Gender.FEMININE,
            hint = "Женский голос: замена Джарвиса, если больше нравится он."
        ),
        Profile(
            id = "classic",
            name = "Системный голос без обработки",
            pitch = 1.00f,
            rate = 1.00f,
            gender = Gender.ANY,
            hint = "Ровно то, что стоит в настройках Android — никаких изменений."
        )
    )

    fun byId(id: String?): Profile = ALL.firstOrNull { it.id == id } ?: ALL[0]

    fun indexOf(id: String?): Int = ALL.indexOfFirst { it.id == id }.coerceAtLeast(0)

    fun names(): List<String> = ALL.map { it.name }

    // --------------------------------------------------------------- границы

    const val PITCH_MIN = 0.5f
    const val PITCH_MAX = 1.6f
    const val RATE_MIN = 0.6f
    const val RATE_MAX = 1.8f
    const val STEP = 0.08f

    fun clampPitch(v: Float): Float = round2(v.coerceIn(PITCH_MIN, PITCH_MAX))
    fun clampRate(v: Float): Float = round2(v.coerceIn(RATE_MIN, RATE_MAX))

    private fun round2(v: Float): Float = Math.round(v * 100f) / 100f

    /** Человеческое описание тембра: «ниже обычного, темп быстрый». */
    fun describe(pitch: Float, rate: Float): String {
        val p = when {
            pitch <= 0.72f -> "очень низкий"
            pitch <= 0.88f -> "низкий"
            pitch < 1.05f -> "обычный"
            pitch < 1.25f -> "высокий"
            else -> "очень высокий"
        }
        val r = when {
            rate <= 0.8f -> "медленный"
            rate < 1.1f -> "спокойный"
            rate < 1.35f -> "быстрый"
            else -> "очень быстрый"
        }
        return "тон $p, темп $r"
    }

    // ---------------------------------------------------- выбор системного голоса

    /** Имена женских голосов Google/Samsung почти всегда содержат эти куски. */
    private val FEMALE_MARKS = listOf(
        "female", "#female", "-f-", "_f_", "woman", "женск",
        "ruf", "rue", "-f0", "female_1", "female_2"
    )

    private val MALE_MARKS = listOf(
        "male", "#male", "-m-", "_m_", "man", "мужск",
        "rum", "rud", "-m0", "male_1", "male_2"
    )

    fun looksFeminine(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        if (n.contains("female")) return true
        return FEMALE_MARKS.any { it != "female" && n.contains(it) } && !n.contains("male_")
    }

    fun looksMasculine(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        if (n.contains("female")) return false
        return MALE_MARKS.any { n.contains(it) }
    }

    /**
     * Оценка системного голоса под профиль: чем больше — тем лучше.
     * Отрицательная оценка значит «этот голос не подходит вовсе».
     *
     * @param name       имя голоса (Voice.getName())
     * @param localeTag  язык голоса, например ru-RU
     * @param quality    Voice.getQuality() (100 — VERY_LOW … 500 — VERY_HIGH)
     * @param network    голосу нужен интернет (Voice.isNetworkConnectionRequired)
     */
    fun scoreVoice(
        name: String,
        localeTag: String,
        quality: Int,
        network: Boolean,
        p: Profile
    ): Int {
        val tag = localeTag.lowercase(Locale.ROOT).replace('_', '-')
        if (!tag.startsWith("ru")) return -1000
        var score = 0
        // качество: 100…500 → 0…40 очков
        score += ((quality.coerceIn(100, 500) - 100) / 10)
        // офлайн-голос всегда предпочтительнее: он не пропадёт без сети
        if (!network) score += 30
        when (p.gender) {
            Gender.MASCULINE -> {
                if (looksMasculine(name)) score += 60
                if (looksFeminine(name)) score -= 45
            }
            Gender.FEMININE -> {
                if (looksFeminine(name)) score += 60
                if (looksMasculine(name)) score -= 45
            }
            Gender.ANY -> Unit
        }
        return score
    }

    /** Выбирает лучшее имя голоса из списка (name, localeTag, quality, network). */
    fun bestVoice(
        voices: List<VoiceInfo>,
        p: Profile
    ): String? = voices
        .map { it to scoreVoice(it.name, it.locale, it.quality, it.network, p) }
        .filter { it.second > -1000 }
        .maxByOrNull { it.second }
        ?.first?.name

    /** Описание системного голоса без зависимости от Android-классов. */
    class VoiceInfo(
        val name: String,
        val locale: String,
        val quality: Int,
        val network: Boolean
    )

    // ------------------------------------------------------- голосовые команды

    sealed class Cmd {
        /** Включить профиль. */
        class Set(val id: String) : Cmd()
        /** Подкрутить тон/скорость: delta прибавляется к текущему значению. */
        class Pitch(val delta: Float) : Cmd()
        class Rate(val delta: Float) : Cmd()
        /** Вернуть настройки профиля. */
        object Reset : Cmd()
        /** Произнести пробную фразу. */
        object Test : Cmd()
        /** Рассказать, какой голос сейчас. */
        object Info : Cmd()
        /** Открыть настройки голоса. */
        object Settings : Cmd()
    }

    /** Разбирает фразу про голос. null — команда не про голос. */
    fun parse(raw: String): Cmd? {
        val s = raw.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        if (s.isEmpty()) return null

        // --- выбор профиля ---
        if (Regex("(?:включи |поставь |смени на |сделай )?голос(?:ом)? джарвиса|говори как джарвис|" +
                "верни голос джарвиса|фирменный голос").containsMatchIn(s)
        ) return Cmd.Set("jarvis")

        if (Regex("голос брони|железный голос|металлическ[а-я]* голос|" +
                "(?:сделай |включи )?самый низкий голос|голос как в броне").containsMatchIn(s)
        ) return Cmd.Set("stark")

        if (Regex("деловой голос|голос агента|голос диктора|быстрый голос|голос для доклада")
                .containsMatchIn(s)
        ) return Cmd.Set("agent")

        if (Regex("женск[а-я]* голос|голос пятницы|голос фрайдей|голос девушки").containsMatchIn(s))
            return Cmd.Set("friday")

        if (Regex("обычн[а-я]* голос|системн[а-я]* голос|стандартн[а-я]* голос|голос по умолчанию|" +
                "выключи обработку голоса").containsMatchIn(s)
        ) return Cmd.Set("classic")

        if (Regex("мужск[а-я]* голос").containsMatchIn(s)) return Cmd.Set("jarvis")

        // --- подстройка ---
        if (Regex("говори (?:по)?ниже|голос ниже|ниже тон|сделай голос ниже|погрубее")
                .containsMatchIn(s)
        ) return Cmd.Pitch(-STEP)
        if (Regex("говори (?:по)?выше|голос выше|выше тон|сделай голос выше|потоньше")
                .containsMatchIn(s)
        ) return Cmd.Pitch(+STEP)
        if (Regex("говори (?:по)?быстрее|быстрее говори|ускорь речь|темп быстрее")
                .containsMatchIn(s)
        ) return Cmd.Rate(+STEP)
        if (Regex("говори (?:по)?медленнее|медленнее говори|замедли речь|темп медленнее")
                .containsMatchIn(s)
        ) return Cmd.Rate(-STEP)
        if (Regex("верни (?:обычную )?скорость|сбрось голос|сброс голоса|голос как был")
                .containsMatchIn(s)
        ) return Cmd.Reset

        // --- проверка и справка ---
        if (Regex("проверь голос|тест голоса|как ты звучишь|скажи что нибудь голосом|" +
                "прове[рь]* как звучишь").containsMatchIn(s)
        ) return Cmd.Test
        if (Regex("как[а-я]* (?:у тебя )?(?:сейчас )?голос|какой голос(?: сейчас)?|" +
                "что за голос|какие есть голоса|список голосов").containsMatchIn(s)
        ) return Cmd.Info
        if (Regex("настройки голоса|смени голос|поменяй голос|выбери голос|другой голос")
                .containsMatchIn(s)
        ) return Cmd.Settings

        return null
    }

    /** Фраза для проверки тембра. */
    fun sampleText(): String =
        "Системы в норме, сэр. Реактор стабилен, все датчики отвечают. " +
            "Готов искать информацию и собирать презентации по вашей команде."
}
