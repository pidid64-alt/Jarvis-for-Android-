package kz.jarvis.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Одноразовая озвучка из любого места (BroadcastReceiver, служба).
 * Создаёт TextToSpeech, говорит фразу и сам себя выключает.
 */
object Speaker {

    fun once(ctx: Context, text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) {
            onDone()
            return
        }
        val finished = AtomicBoolean(false)
        fun done(t: TextToSpeech?) {
            if (!finished.compareAndSet(false, true)) return
            SpeechState.end()   // микрофон службы снова может слушать
            try {
                t?.shutdown()
            } catch (e: Exception) { }
            onDone()
        }

        var instance: TextToSpeech? = null
        try {
            instance = TextToSpeech(ctx.applicationContext) { status ->
                val t = instance
                if (status != TextToSpeech.SUCCESS || t == null) {
                    done(t)
                    return@TextToSpeech
                }
                t.language = Locale("ru", "RU")
                // тот же тембр, что и в диалоге: профиль «Джарвис» и его настройки
                try { Voice.apply(ctx.applicationContext, t) } catch (e: Exception) { }
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = SpeechState.begin()

                    override fun onDone(utteranceId: String?) = done(t)

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = done(t)

                    override fun onStop(utteranceId: String?, interrupted: Boolean) = done(t)
                })
                t.speak(text.take(600), TextToSpeech.QUEUE_FLUSH, null, "jarvis_once")
            }
        } catch (e: Exception) {
            done(instance)
            return
        }
        // страховка: если движок TTS завис — освобождаем ресурсы и колбэк.
        // Таймер зависит от длины текста: длинное напоминание звучит дольше
        // 15 секунд, и раньше фраза обрывалась на середине.
        val fuseMs = 15_000L + 110L * text.take(600).length
        Handler(Looper.getMainLooper()).postDelayed({ done(instance) }, fuseMs)
    }
}
