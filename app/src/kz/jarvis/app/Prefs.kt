package kz.jarvis.app

import android.content.Context

object Prefs {
    private const val NAME = "jarvis"
    private const val K_API = "api_key"           // легаси: ключ Gemini из версии 2.0
    private const val K_PROVIDER = "provider"
    private const val K_TTS = "tts_on"
    private const val K_WAKE = "wake_on"
    private const val K_NOISE = "noise_suppressor"
    private const val K_HELLO = "said_hello"
    private const val K_SNOOZE = "snooze_until"
    private const val K_BOOT_HINT = "boot_hint_shown"

    // непрерывный диалог
    private const val K_FOLLOW = "follow_up"

    // звонки: всегда выбирать первую SIM, когда их две
    private const val K_CALL_SIM = "call_sim_first"

    // автоответчик в мессенджерах
    private const val K_AUTO = "auto_reply"
    private const val K_AUTO_SCREEN = "auto_reply_screen_off"
    private const val K_AUTO_LOC = "auto_reply_location"
    private const val K_AUTO_VOICE = "auto_reply_voice"
    private const val K_AUTO_RULES = "auto_reply_rules"
    private const val K_AUTO_HINT_DAY = "auto_reply_hint_day"

    // голос
    private const val K_VOICE = "voice_profile"
    private const val K_PITCH = "voice_pitch"
    private const val K_RATE = "voice_rate"
    private const val K_VOICE_NAME = "voice_name"
    private const val K_VOICE_FX = "voice_fx"
    private const val K_EXPRESSION = "voice_expression"

    // исследования и презентации
    private const val K_CLAUDE_APP = "claude_app"
    private const val K_DECK_URI = "deck_uri"
    private const val K_DECK_PATH = "deck_path"
    private const val K_DECK_TOPIC = "deck_topic"

    // изучение темы и наблюдение за экраном
    private const val K_STUDY_TOPIC = "study_pending_topic"
    private const val K_SCREEN_WATCH = "screen_watch"
    private const val K_SCREEN_EVERY = "screen_watch_min"

    // диктофон и своё кодовое слово
    private const val K_REC_WORDS = "rec_words"
    private const val K_REC_ON = "rec_on"
    private const val K_REC_LIMIT = "rec_limit_min"
    private const val K_REC_GAP = "rec_gap_sec"
    private const val K_REC_INDEX = "rec_index"

    // звонки в Discord
    private const val K_DISCORD_LINKS = "discord_links"
    private const val K_DISCORD_TAP = "discord_auto_call"

    // секундомер
    private const val K_SW_RUN = "sw_run"
    private const val K_SW_START = "sw_start"
    private const val K_SW_ACC = "sw_acc"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------- провайдер ИИ

    /** Выбранный провайдер (Gemini, OpenAI, OpenRouter, Ollama…). */
    fun provider(c: Context): Providers.Provider =
        Providers.byId(sp(c).getString(K_PROVIDER, null))

    fun setProvider(c: Context, id: String) = sp(c).edit().putString(K_PROVIDER, id).apply()

    /** Ключ хранится отдельно для каждого провайдера — не затирается при переключении. */
    fun apiKey(c: Context, p: Providers.Provider = provider(c)): String {
        val own = sp(c).getString("key_${p.id}", null)?.trim().orEmpty()
        if (own.isNotEmpty()) return own
        // ключ из версии 2.0 считаем ключом Gemini
        return if (p.id == "gemini") sp(c).getString(K_API, "")?.trim().orEmpty() else ""
    }

    fun setApiKey(c: Context, v: String, p: Providers.Provider = provider(c)) =
        sp(c).edit().putString("key_${p.id}", v.trim()).apply()

    /** Название модели: сохранённое пользователем или первая из списка провайдера. */
    fun model(c: Context, p: Providers.Provider = provider(c)): String {
        val saved = sp(c).getString("model_${p.id}", null)?.trim().orEmpty()
        return saved.ifEmpty { p.defaultModel }
    }

    fun setModel(c: Context, v: String, p: Providers.Provider = provider(c)) =
        sp(c).edit().putString("model_${p.id}", v.trim()).apply()

    /** Адрес сервера: свой (для Ollama, LM Studio, прокси) или адрес провайдера. */
    fun baseUrl(c: Context, p: Providers.Provider = provider(c)): String {
        val saved = sp(c).getString("url_${p.id}", null)?.trim().orEmpty()
        return saved.ifEmpty { p.baseUrl }
    }

    fun setBaseUrl(c: Context, v: String, p: Providers.Provider = provider(c)) =
        sp(c).edit().putString("url_${p.id}", v.trim()).apply()

    // -------------------------------------------------------------- остальное

    fun ttsOn(c: Context): Boolean = sp(c).getBoolean(K_TTS, true)
    fun setTtsOn(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_TTS, v).apply()

    /** Голосовая активация по умолчанию включена: «Джарвис» должен работать всегда. */
    fun wakeOn(c: Context): Boolean = sp(c).getBoolean(K_WAKE, true)
    fun setWakeOn(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_WAKE, v).apply()

    /**
     * Шумоподавление микрофона (NoiseSuppressor + эхоподавление + автоусиление).
     * По умолчанию включено; реально работает, если прошивка умеет эти эффекты.
     */
    fun noiseSuppress(c: Context): Boolean = sp(c).getBoolean(K_NOISE, true)
    fun setNoiseSuppress(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_NOISE, v).apply()

    fun saidHello(c: Context): Boolean = sp(c).getBoolean(K_HELLO, false)
    fun setSaidHello(c: Context) = sp(c).edit().putBoolean(K_HELLO, true).apply()

    /** До какого момента служба «спит» (команда «спи 10 минут»). */
    fun snoozeUntil(c: Context): Long = sp(c).getLong(K_SNOOZE, 0L)
    fun setSnoozeUntil(c: Context, v: Long) = sp(c).edit().putLong(K_SNOOZE, v).apply()

    fun bootHintShown(c: Context): Boolean = sp(c).getBoolean(K_BOOT_HINT, false)
    fun setBootHintShown(c: Context) = sp(c).edit().putBoolean(K_BOOT_HINT, true).apply()

    // --------------------------------------------------------- непрерывный диалог

    /**
     * После ответа Джарвис продолжает слушать следующую реплику без нового
     * «Джарвис» и возвращается к ожиданию слова только после тишины.
     */
    fun followUp(c: Context): Boolean = sp(c).getBoolean(K_FOLLOW, true)
    fun setFollowUp(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_FOLLOW, v).apply()

    // ------------------------------------------------------------- звонки

    /** Две SIM: при звонке всегда выбирать первую (SIM 1), без системного вопроса. */
    fun callSimFirst(c: Context): Boolean = sp(c).getBoolean(K_CALL_SIM, true)
    fun setCallSimFirst(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_CALL_SIM, v).apply()

    // ---------------------------------------------------------- автоответчик

    /** Автоответчик в мессенджерах: по умолчанию выключен — включается явно. */
    fun autoReply(c: Context): Boolean = sp(c).getBoolean(K_AUTO, false)
    fun setAutoReply(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_AUTO, v).apply()

    /** Отвечать, только когда экран выключен (владелец не читает чат сам). */
    fun autoReplyScreenOff(c: Context): Boolean = sp(c).getBoolean(K_AUTO_SCREEN, true)
    fun setAutoReplyScreenOff(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(K_AUTO_SCREEN, v).apply()

    /** Подставлять геолокацию владельца, когда спрашивают «где ты». */
    fun autoReplyLoc(c: Context): Boolean = sp(c).getBoolean(K_AUTO_LOC, true)
    fun setAutoReplyLoc(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(K_AUTO_LOC, v).apply()

    /** Озвучивать вслух, что Джарвис ответил (и о чём спрашивали). */
    fun autoReplyVoice(c: Context): Boolean = sp(c).getBoolean(K_AUTO_VOICE, false)
    fun setAutoReplyVoice(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(K_AUTO_VOICE, v).apply()

    /** Личные правила владельца для автоответов («если зовут гулять — я занят»). */
    fun autoReplyRules(c: Context): String = sp(c).getString(K_AUTO_RULES, "").orEmpty()
    fun setAutoReplyRules(c: Context, v: String) =
        sp(c).edit().putString(K_AUTO_RULES, v.trim()).apply()

    /** Добавляет ещё одно правило автоответа (каждое с новой строки). */
    fun addAutoReplyRule(c: Context, rule: String) {
        val r = rule.trim().trimEnd('.', ',', '!', '?', ' ')
        if (r.isBlank()) return
        val all = (autoReplyRules(c) + "\n" + r).lines().filter { it.isNotBlank() }
            .distinct().joinToString("\n")
        setAutoReplyRules(c, all)
    }

    /**
     * Подсказки автоответчика («почему пропустил») показываем не чаще раза
     * в день — иначе каждое сообщение дёргало бы владельца.
     */
    fun autoHintPosted(c: Context): Boolean {
        val day = System.currentTimeMillis() / 86_400_000L
        return sp(c).getLong(K_AUTO_HINT_DAY, 0L) == day
    }

    fun markAutoHintPosted(c: Context) {
        val day = System.currentTimeMillis() / 86_400_000L
        sp(c).edit().putLong(K_AUTO_HINT_DAY, day).apply()
    }

    // ------------------------------------------------------------------ голос

    /** Профиль голоса: «джарвис», «броня», «пятница»… (см. VoiceProfile). */
    fun voiceId(c: Context): String =
        sp(c).getString(K_VOICE, VoiceProfile.DEFAULT_ID) ?: VoiceProfile.DEFAULT_ID

    fun voiceProfile(c: Context): VoiceProfile.Profile = VoiceProfile.byId(voiceId(c))

    /** Смена профиля сбрасывает ручную подстройку тона и скорости. */
    fun setVoiceId(c: Context, id: String) = sp(c).edit()
        .putString(K_VOICE, id)
        .remove(K_PITCH)
        .remove(K_RATE)
        .apply()

    /** Высота тона: подстроенная вручную или из профиля. */
    fun voicePitch(c: Context): Float =
        sp(c).getFloat(K_PITCH, voiceProfile(c).pitch)

    fun setVoicePitch(c: Context, v: Float) =
        sp(c).edit().putFloat(K_PITCH, VoiceProfile.clampPitch(v)).apply()

    fun voiceRate(c: Context): Float =
        sp(c).getFloat(K_RATE, voiceProfile(c).rate)

    fun setVoiceRate(c: Context, v: Float) =
        sp(c).edit().putFloat(K_RATE, VoiceProfile.clampRate(v)).apply()

    /** Сброс ручной подстройки к значениям профиля. */
    fun resetVoiceTone(c: Context) = sp(c).edit().remove(K_PITCH).remove(K_RATE).apply()

    /** Имя системного голоса, выбранного руками («авто» — пусто). */
    fun voiceName(c: Context): String = sp(c).getString(K_VOICE_NAME, "").orEmpty()
    fun setVoiceName(c: Context, v: String) = sp(c).edit().putString(K_VOICE_NAME, v).apply()

    /** Эффект «брони»: лёгкое эхо поверх речи. */
    fun voiceFx(c: Context): Boolean = sp(c).getBoolean(K_VOICE_FX, false)
    fun setVoiceFx(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_VOICE_FX, v).apply()

    /**
     * Сила эмоций в речи (см. [Emotion.Level]): выключены / слегка /
     * выразительно. По умолчанию — выразительно: ровное чтение одной
     * интонацией звучало как автоответчик.
     */
    fun expression(c: Context): Emotion.Level =
        Emotion.Level.byId(sp(c).getInt(K_EXPRESSION, Emotion.Level.DEFAULT.id))

    fun setExpression(c: Context, level: Emotion.Level) =
        sp(c).edit().putInt(K_EXPRESSION, level.id).apply()

    // ------------------------------------------------- исследования и презентации

    /** Отправлять найденное в приложение Claude, а не в API. */
    fun claudeApp(c: Context): Boolean = sp(c).getBoolean(K_CLAUDE_APP, false)
    fun setClaudeApp(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_CLAUDE_APP, v).apply()

    fun lastDeckUri(c: Context): String = sp(c).getString(K_DECK_URI, "").orEmpty()
    fun lastDeckPath(c: Context): String = sp(c).getString(K_DECK_PATH, "").orEmpty()
    fun lastDeckTopic(c: Context): String = sp(c).getString(K_DECK_TOPIC, "").orEmpty()

    /** Тема, для которой пользователь ещё не выбрал «рассказать» или «видео». */
    fun pendingStudyTopic(c: Context): String = sp(c).getString(K_STUDY_TOPIC, "").orEmpty()
    fun setPendingStudyTopic(c: Context, topic: String) = sp(c).edit().putString(K_STUDY_TOPIC, topic).apply()
    fun clearPendingStudyTopic(c: Context) = sp(c).edit().remove(K_STUDY_TOPIC).apply()

    /** Проактивный просмотр экрана через сервис специальных возможностей. */
    fun screenWatch(c: Context): Boolean = sp(c).getBoolean(K_SCREEN_WATCH, false)
    fun setScreenWatch(c: Context, value: Boolean) = sp(c).edit().putBoolean(K_SCREEN_WATCH, value).apply()

    fun setLastDeck(c: Context, uri: String, path: String, topic: String) = sp(c).edit()
        .putString(K_DECK_URI, uri)
        .putString(K_DECK_PATH, path)
        .putString(K_DECK_TOPIC, topic)
        .apply()

    /** Как часто читать экран, минуты (см. [ScreenWatch]). */
    fun screenWatchMin(c: Context): Int =
        ScreenWatch.snap(sp(c).getInt(K_SCREEN_EVERY, ScreenWatch.DEFAULT_MIN))

    fun setScreenWatchMin(c: Context, minutes: Int) =
        sp(c).edit().putInt(K_SCREEN_EVERY, ScreenWatch.snap(minutes)).apply()

    // --------------------------------------------------------- диктофон

    /** Свои кодовые фразы: услышав их, Джарвис включает запись. */
    fun recWords(c: Context): List<String> =
        CodeWords.parseList(sp(c).getString(K_REC_WORDS, "").orEmpty())

    fun setRecWords(c: Context, words: List<String>) = sp(c).edit()
        .putString(K_REC_WORDS, words.map { CodeWords.normalize(it) }.filter { it.isNotEmpty() }
            .distinct().joinToString("\n"))
        .apply()

    /** Ждать ли кодовое слово (выключается, не стирая сами слова). */
    fun recOn(c: Context): Boolean = sp(c).getBoolean(K_REC_ON, true)
    fun setRecOn(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_REC_ON, v).apply()

    /** Ограничение длины одной записи, минуты. */
    fun recLimitMin(c: Context): Int = sp(c).getInt(K_REC_LIMIT, 10).coerceIn(1, 600)
    fun setRecLimitMin(c: Context, v: Int) =
        sp(c).edit().putInt(K_REC_LIMIT, v.coerceIn(1, 600)).apply()

    /**
     * Как часто во время записи открывать микрофон распознавателю, чтобы
     * услышать «стоп запись» (секунды). 0 — не слушать, запись без пауз.
     */
    fun recGapSec(c: Context): Int = sp(c).getInt(K_REC_GAP, 60).coerceIn(0, 600)
    fun setRecGapSec(c: Context, v: Int) = sp(c).edit().putInt(K_REC_GAP, v.coerceIn(0, 600)).apply()

    /** Одна сохранённая запись. */
    data class RecEntry(val name: String, val where: String, val seconds: Long, val uri: String)

    /** Список записей: «имя|куда сохранили|секунды|uri» по строке на запись. */
    fun recordings(c: Context): List<RecEntry> =
        sp(c).getString(K_REC_INDEX, "").orEmpty().lines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 4 || p[0].isBlank()) return@mapNotNull null
            RecEntry(p[0], p[1], p[2].toLongOrNull() ?: 0L, p[3])
        }

    fun setRecordings(c: Context, list: List<RecEntry>) = sp(c).edit()
        .putString(K_REC_INDEX, list.takeLast(30).joinToString("\n") {
            "${it.name}|${it.where}|${it.seconds}|${it.uri}"
        })
        .apply()

    fun addRecording(c: Context, name: String, where: String, seconds: Long, uri: String) =
        setRecordings(c, recordings(c) + RecEntry(name, where, seconds, uri))

    // ------------------------------------------------------------- Discord

    /** Сохранённые ссылки Discord: «имя|ссылка» (см. [Discord.parseAliases]). */
    fun discordLinks(c: Context): List<Discord.Alias> =
        Discord.parseAliases(sp(c).getString(K_DISCORD_LINKS, "").orEmpty())

    fun setDiscordLinks(c: Context, list: List<Discord.Alias>) =
        sp(c).edit().putString(K_DISCORD_LINKS, Discord.serialize(list)).apply()

    fun addDiscordLink(c: Context, alias: Discord.Alias) =
        setDiscordLinks(c, Discord.upsert(discordLinks(c), alias))

    fun removeDiscordLink(c: Context, name: String) =
        setDiscordLinks(c, Discord.remove(discordLinks(c), name))

    /** Самому нажимать кнопку звонка в Discord (нужен сервис спец. возможностей). */
    fun discordAutoCall(c: Context): Boolean = sp(c).getBoolean(K_DISCORD_TAP, true)
    fun setDiscordAutoCall(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(K_DISCORD_TAP, v).apply()

    // -------------------------------------------------------------- секундомер

    fun swRunning(c: Context): Boolean = sp(c).getBoolean(K_SW_RUN, false)
    fun swStartedAt(c: Context): Long = sp(c).getLong(K_SW_START, 0L)
    fun swAccrued(c: Context): Long = sp(c).getLong(K_SW_ACC, 0L)

    fun setStopwatch(c: Context, running: Boolean, startedAt: Long, accrued: Long) = sp(c).edit()
        .putBoolean(K_SW_RUN, running)
        .putLong(K_SW_START, startedAt)
        .putLong(K_SW_ACC, accrued)
        .apply()
}
