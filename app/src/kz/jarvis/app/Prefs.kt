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

    // исследования и презентации
    private const val K_CLAUDE_APP = "claude_app"
    private const val K_DECK_URI = "deck_uri"
    private const val K_DECK_PATH = "deck_path"
    private const val K_DECK_TOPIC = "deck_topic"

    // изучение темы и наблюдение за экраном
    private const val K_STUDY_TOPIC = "study_pending_topic"
    private const val K_SCREEN_WATCH = "screen_watch"

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
}
