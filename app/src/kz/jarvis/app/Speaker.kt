package kz.jarvis.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Одноразовая озвучка из любого места (BroadcastReceiver, служба).
 * Создаёт TextToSpeech, говорит фразу и сам себя выключает.
 *
 * Фраза произносится по кускам плана [Emotion.plan] — с интонацией, как и в
 * диалоге: напоминание звучит спокойно, предупреждение о зарядке — настороженно,
 * удачное завершение — бодрее.
 */
object Speaker {

    fun once(ctx: Context, text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) {
            onDone()
            return
        }
        val finished = AtomicBoolean(false)
        val began = AtomicBoolean(false)
        val phrase = text.take(600)

        var instance: TextToSpeech? = null
        var chain: SpeechChain? = null

        fun done() {
            if (!finished.compareAndSet(false, true)) return
            try { chain?.stop() } catch (e: Exception) { }
            if (began.get()) SpeechState.end()   // микрофон службы снова может слушать
            try { instance?.shutdown() } catch (e: Exception) { }
            onDone()
        }

        var pauses = 0

        try {
            instance = TextToSpeech(ctx.applicationContext) { status ->
                val t = instance
                if (status != TextToSpeech.SUCCESS || t == null) {
                    done()
                    return@TextToSpeech
                }
                t.language = Locale("ru", "RU")
                // тот же тембр, что и в диалоге: профиль «Джарвис» и его настройки
                try { Voice.apply(ctx.applicationContext, t) } catch (e: Exception) { }
                val plan = Emotion.plan(
                    phrase,
                    Prefs.voicePitch(ctx),
                    Prefs.voiceRate(ctx),
                    Prefs.expression(ctx)
                )
                pauses = plan.map { it.pauseAfterMs }.sum()
                chain = SpeechChain(t).apply {
                    onStart = {
                        began.set(true)
                        SpeechState.begin()
                    }
                    onFinish = {
                        if (began.get()) SpeechState.end()
                        done()
                    }
                }
                chain?.speak(plan)
            }
        } catch (e: Exception) {
            done()
            return
        }

        // страховка: если движок TTS завис — освобождаем ресурсы и колбэк.
        // Таймер зависит от длины текста и числа пауз между кусками: длинное
        // напоминание звучит дольше 15 секунд, и раньше фраза обрывалась.
        val fuseMs = 15_000L + 110L * phrase.length + pauses
        Handler(Looper.getMainLooper()).postDelayed({ done() }, fuseMs)
    }
}
