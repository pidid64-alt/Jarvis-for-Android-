package kz.jarvis.app

import android.content.Context

object Prefs {
    private const val NAME = "jarvis"
    private const val K_API = "api_key"
    private const val K_MODEL = "model"
    private const val K_TTS = "tts_on"
    private const val K_WAKE = "wake_on"
    private const val K_HELLO = "said_hello"
    private const val K_SNOOZE = "snooze_until"
    private const val K_BOOT_HINT = "boot_hint_shown"

    val MODELS = arrayOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-pro-preview",
        "gemini-2.5-flash",
        "gemini-2.5-pro"
    )
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString(K_API, "")?.trim() ?: ""
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString(K_API, v.trim()).apply()

    fun model(c: Context): String = sp(c).getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun setModel(c: Context, v: String) = sp(c).edit().putString(K_MODEL, v).apply()

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
