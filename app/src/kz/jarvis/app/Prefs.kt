package kz.jarvis.app

import android.content.Context

object Prefs {
    private const val NAME = "jarvis"
    private const val K_API = "api_key"
    private const val K_MODEL = "model"
    private const val K_TTS = "tts_on"
    private const val K_WAKE = "wake_on"
    private const val K_HELLO = "said_hello"

    val MODELS = arrayOf(
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash",
        "gemini-2.5-pro"
    )
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString(K_API, "")?.trim() ?: ""
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString(K_API, v.trim()).apply()

    fun model(c: Context): String = sp(c).getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun setModel(c: Context, v: String) = sp(c).edit().putString(K_MODEL, v).apply()

    fun ttsOn(c: Context): Boolean = sp(c).getBoolean(K_TTS, true)
    fun setTtsOn(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_TTS, v).apply()

    fun wakeOn(c: Context): Boolean = sp(c).getBoolean(K_WAKE, false)
    fun setWakeOn(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_WAKE, v).apply()

    fun saidHello(c: Context): Boolean = sp(c).getBoolean(K_HELLO, false)
    fun setSaidHello(c: Context) = sp(c).edit().putBoolean(K_HELLO, true).apply()
}
