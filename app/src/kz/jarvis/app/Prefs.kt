package kz.jarvis.app

import android.content.Context

object Prefs {
    private const val NAME = "jarvis"
    private const val K_API = "api_key"           // легаси: ключ Gemini из версии 2.0
    private const val K_PROVIDER = "provider"
    private const val K_TTS = "tts_on"
    private const val K_WAKE = "wake_on"
    private const val K_HELLO = "said_hello"
    private const val K_SNOOZE = "snooze_until"
    private const val K_BOOT_HINT = "boot_hint_shown"

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

    fun saidHello(c: Context): Boolean = sp(c).getBoolean(K_HELLO, false)
    fun setSaidHello(c: Context) = sp(c).edit().putBoolean(K_HELLO, true).apply()

    /** До какого момента служба «спит» (команда «спи 10 минут»). */
    fun snoozeUntil(c: Context): Long = sp(c).getLong(K_SNOOZE, 0L)
    fun setSnoozeUntil(c: Context, v: Long) = sp(c).edit().putLong(K_SNOOZE, v).apply()

    fun bootHintShown(c: Context): Boolean = sp(c).getBoolean(K_BOOT_HINT, false)
    fun setBootHintShown(c: Context) = sp(c).edit().putBoolean(K_BOOT_HINT, true).apply()
}
