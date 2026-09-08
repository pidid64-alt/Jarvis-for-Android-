package kz.jarvis.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Озвучка ответов. Тембр берётся из [Voice]: профиль «Джарвис» (низкий тон),
 * «Броня», «Агент», «Пятница» или системный голос без обработки.
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
    private var tts: TextToSpeech? = null
    private var appliedRevision = -1

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
        val t = text.take(1200)
        if (!ready || !enabled || t.isBlank()) {
            onDone()
            return
        }
        if (appliedRevision != Voice.revision) refresh()
        val id = "jarvis_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                SpeechState.begin()
                onState(STATE_SPEAKING)
            }

            override fun onDone(utteranceId: String?) {
                SpeechState.end()
                onState(STATE_IDLE)
                onDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                SpeechState.end()
                onState(STATE_IDLE)
                onDone()
            }

            /**
             * Фразу прервали (QUEUE_FLUSH, «стоп», shutdown). Без этого
             * колбэка состояние «говорю» залипало навсегда: сфера в окне
             * не возвращалась в «слушаю», а служба не отпускала микрофон.
             */
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                SpeechState.end()
                onState(STATE_IDLE)
                onDone()
            }
        })
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        SpeechState.end()
        tts?.stop()
        onState(STATE_IDLE)
    }

    fun shutdown() {
        SpeechState.end()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
