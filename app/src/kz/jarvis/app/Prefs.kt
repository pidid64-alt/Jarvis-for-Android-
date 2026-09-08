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

    fun setLastDeck(c: Context, uri: String, path: String, topic: String) = sp(c).edit()
        .putString(K_DECK_URI, uri)
        .putString(K_DECK_PATH, path)
        .putString(K_DECK_TOPIC, topic)
        .apply()
}
