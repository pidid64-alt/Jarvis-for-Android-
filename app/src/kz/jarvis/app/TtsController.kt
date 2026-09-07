package kz.jarvis.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsController(context: Context, private val onState: (Int) -> Unit) :
    TextToSpeech.OnInitListener {

    companion object {
        const val STATE_IDLE = 0
        const val STATE_SPEAKING = 1
    }

    private var tts: TextToSpeech? = null
    var ready = false
        private set
    var enabled = true

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS &&
                (tts?.setLanguage(Locale("ru", "RU"))?.let {
                    it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                } == true)
        if (!ready) tts?.language = Locale.US
    }

    fun speak(text: String, onDone: () -> Unit = {}) {
        val t = text.take(900)
        if (!ready || !enabled || t.isBlank()) {
            onDone()
            return
        }
        val id = "jarvis_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onState(STATE_SPEAKING)
            }

            override fun onDone(utteranceId: String?) {
                onState(STATE_IDLE)
                onDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onState(STATE_IDLE)
                onDone()
            }
        })
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        tts?.stop()
        onState(STATE_IDLE)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
