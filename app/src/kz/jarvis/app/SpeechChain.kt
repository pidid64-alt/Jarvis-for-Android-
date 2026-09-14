package kz.jarvis.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Произносит фразу по кускам плана [Emotion.plan]: у каждого высказывания свой
 * тон, темп и громкость, между высказываниями — пауза. Именно это превращает
 * ровное «чтение автоответчика» в живую интонацию.
 *
 * Почему не SSML: `TextToSpeech.speak()` на Android разметку не понимает —
 * движки читают теги как обычный текст. Зато работают `setPitch`,
 * `setSpeechRate` и параметр `KEY_PARAM_VOLUME`, а действуют они на СЛЕДУЮЩУЮ
 * фразу. Поэтому куски произносятся по очереди: пауза встаёт между
 * высказываниями, а не внутри них.
 *
 * Каждый кусок получает свой идентификатор высказывания, поэтому события
 * прерванной (заменённой) фразы не путаются с новой — иначе «хвост» старой
 * озвучки мог бы завершить новую раньше времени, а микрофон службы открылся бы
 * посреди речи.
 */
class SpeechChain(private val tts: TextToSpeech) {

    /** Начала звучать первая фраза. */
    var onStart: () -> Unit = {}

    /** Фраза договорена, прервана или заменена новой. Вызывается ровно один раз. */
    var onFinish: () -> Unit = {}

    private val ui = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var generation = 0
    private var plan: List<Emotion.Chunk> = emptyList()
    private var index = 0
    private var alive = false

    private val listener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            if (!mine(utteranceId)) return
            if (idIndex(utteranceId) == 0) fireStart()
        }

        override fun onDone(utteranceId: String?) {
            if (!mine(utteranceId)) return
            advance()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (!mine(utteranceId)) return
            finish()
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            if (!mine(utteranceId)) return
            finish()
        }
    }

    /** Произносит план целиком, заменяя то, что звучит сейчас. */
    fun speak(chunks: List<Emotion.Chunk>) {
        if (chunks.isEmpty()) return
        // прежнюю фразу завершаем ДО новой: её владелец должен получить onFinish
        val replacing = synchronized(lock) { alive }
        if (replacing) finish()

        val gen = synchronized(lock) {
            generation++
            plan = chunks
            index = 0
            alive = true
            generation
        }
        speakCurrent(gen)
    }

    /** Обрывает озвучку (если она была) — владелец получает onFinish. */
    fun stop() {
        val was = synchronized(lock) {
            val w = alive
            alive = false
            plan = emptyList()
            index = 0
            generation++          // колбэки прерванной фразы уже не наши
            w
        }
        ui.removeCallbacksAndMessages(null)
        try { tts.stop() } catch (e: Exception) { }
        if (was) fireFinish()
    }

    fun speakingNow(): Boolean = synchronized(lock) { alive }

    // ---------------------------------------------------------------- внутреннее

    private fun speakCurrent(gen: Int) {
        val chunk = synchronized(lock) {
            if (!alive || gen != generation) null else plan.getOrNull(index)
        } ?: return

        val id = "j${gen}_$index"
        try {
            tts.setOnUtteranceProgressListener(listener)
            tts.setPitch(chunk.pitch)
            tts.setSpeechRate(chunk.rate)
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, chunk.volume)
            }
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(chunk.text, mode, params, id)
        } catch (e: Exception) {
            finish()
        }
    }

    /** Кусок договорён: пауза и следующий — или конец фразы. */
    private fun advance() {
        var pause = 0
        var gen = 0
        var finished = false
        synchronized(lock) {
            if (!alive || index >= plan.size) return
            if (index >= plan.size - 1) {
                finished = true
            } else {
                pause = plan[index].pauseAfterMs
                index++
                gen = generation
            }
        }
        if (finished) {
            finish()
        } else if (pause <= 0) {
            speakCurrent(gen)
        } else {
            ui.postDelayed({ speakCurrent(gen) }, pause.toLong())
        }
    }

    private fun finish() {
        var was = false
        synchronized(lock) {
            if (!alive) return
            alive = false
            was = true
            plan = emptyList()
            index = 0
            generation++
        }
        ui.removeCallbacksAndMessages(null)
        if (was) fireFinish()
    }

    private fun mine(utteranceId: String?): Boolean {
        val id = utteranceId ?: return false
        return synchronized(lock) { id.startsWith("j${generation}_") }
    }

    private fun idIndex(utteranceId: String?): Int =
        utteranceId?.substringAfterLast('_')?.toIntOrNull() ?: -1

    private fun fireStart() {
        try { onStart() } catch (e: Exception) { }
    }

    private fun fireFinish() {
        try { onFinish() } catch (e: Exception) { }
    }
}
