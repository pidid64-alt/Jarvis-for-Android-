package kz.jarvis.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Озвучка ответов. Тембр берётся из [Voice]: профиль «Джарвис» (низкий тон),
 * «Броня», «Агент», «Пятница» или системный голос без обработки.
 *
 * Поверх профиля [Emotion] чуть двигает высоту тона и скорость — радость,
 * тревога, ирония и остальные. Метка `[радость]` в тексте модели снимается
 * и не произносится.
 *
 * Если настройки голоса поменялись (голосовой командой или в настройках),
 * [Voice.revision] увеличивается — и следующая фраза звучит уже по-новому,
 * перезапускать службу не нужно.
 */
class TtsController(
    context: Context,
    private val onState: (Int) -> Unit,
    /** Вызывается, когда движок поднялся: список голосов уже доступен. */
    private val onReady: () -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        const val STATE_IDLE = 0
        const val STATE_SPEAKING = 1
    }

    private val ctx = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var appliedRevision = -1

    /**
     * Поколение озвучки: новый [speak]/[stop] отменяет цепочку эмоций
     * (несколько кусков «проверь эмоции»), чтобы колбэки не наслаивались.
     */
    private var speakGen = 0
    /** Какое поколение уже закрыли — движок иногда шлёт и onDone, и onStop. */
    private var finishedGen = -1

    var ready = false
        private set
    var enabled = true

    init {
        tts = TextToSpeech(ctx, this)
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS &&
                (tts?.setLanguage(Locale("ru", "RU"))?.let {
                    it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                } == true)
        if (!ready) tts?.language = Locale.US
        refresh()
        try { onReady() } catch (e: Exception) { }
    }

    /** Применяет текущий профиль голоса (можно звать сколько угодно). */
    fun refresh() {
        val t = tts ?: return
        try {
            Voice.apply(ctx, t)
            appliedRevision = Voice.revision
        } catch (e: Exception) { }
    }

    /** Список системных голосов для экрана настроек. */
    fun systemVoices(): List<Pair<String, String>> = Voice.list(tts)

    fun speak(text: String, onDone: () -> Unit = {}) {
        val raw = text.take(2000)
        if (!ready || !enabled || raw.isBlank()) {
            onDone()
            return
        }
        if (appliedRevision != Voice.revision) refresh()
        val pieces = Emotion.chunks(raw, Prefs.emotions(ctx))
            .map { it.copy(spoken = it.spoken.take(1200)) }
            .filter { it.spoken.isNotBlank() }
        if (pieces.isEmpty()) {
            onDone()
            return
        }
        val gen = ++speakGen
        speakPiece(pieces, 0, gen, onDone)
    }

    private fun speakPiece(
        pieces: List<Emotion.Utterance>,
        index: Int,
        gen: Int,
        onDone: () -> Unit
    ) {
        if (gen != speakGen) return
        if (index >= pieces.size) {
            finish(gen, onDone)
            return
        }
        val u = pieces[index]
        applyEmotion(u)
        val last = index == pieces.size - 1
        val id = "jarvis_${gen}_$index"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (index == 0) {
                    SpeechState.begin()
                    onState(STATE_SPEAKING)
                }
            }

            override fun onDone(utteranceId: String?) {
                if (gen != speakGen) return
                if (last) {
                    finish(gen, onDone)
                } else {
                    ui.postDelayed({
                        if (gen == speakGen) speakPiece(pieces, index + 1, gen, onDone)
                    }, u.pauseMs.toLong())
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (gen != speakGen) return
                finish(gen, onDone)
            }

            /**
             * Фразу прервали (QUEUE_FLUSH, «стоп», shutdown). Без этого
             * колбэка состояние «говорю» залипало навсегда: сфера в окне
             * не возвращалась в «слушаю», а служба не отпускала микрофон.
             */
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (gen != speakGen) return
                finish(gen, onDone)
            }
        })
        tts?.speak(u.spoken, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun applyEmotion(u: Emotion.Utterance) {
        val tone = Emotion.applyTone(Prefs.voicePitch(ctx), Prefs.voiceRate(ctx), u.kind)
        try {
            tts?.setPitch(tone.first)
            tts?.setSpeechRate(tone.second)
        } catch (e: Exception) { }
    }

    private fun restoreTone() {
        try {
            tts?.setPitch(VoiceProfile.clampPitch(Prefs.voicePitch(ctx)))
            tts?.setSpeechRate(VoiceProfile.clampRate(Prefs.voiceRate(ctx)))
        } catch (e: Exception) { }
    }

    private fun finish(gen: Int, onDone: () -> Unit) {
        if (finishedGen == gen) return
        finishedGen = gen
        restoreTone()
        SpeechState.end()
        onState(STATE_IDLE)
        onDone()
    }

    fun stop() {
        speakGen++
        restoreTone()
        SpeechState.end()
        tts?.stop()
        onState(STATE_IDLE)
    }

    fun shutdown() {
        speakGen++
        restoreTone()
        SpeechState.end()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
