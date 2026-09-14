package kz.jarvis.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Озвучка ответов. Тембр берётся из [Voice]: профиль «Джарвис» (низкий тон),
 * «Броня», «Агент», «Пятница» или системный голос без обработки. Интонация —
 * из [Emotion]: фраза режется на куски, у каждого свой тон, темп, громкость и
 * пауза, поэтому слышно радость, тревогу, вопрос или иронию, а не ровное чтение.
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
    private var chain: SpeechChain? = null
    private var appliedRevision = -1

    /** Колбэк владельца текущей фразы: позовём, когда она договорена. */
    private var pendingDone: (() -> Unit)? = null

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
        tts?.let { engine ->
            chain = SpeechChain(engine).apply {
                onStart = {
                    SpeechState.begin()
                    onState(STATE_SPEAKING)
                }
                onFinish = {
                    // держим состояние «говорю» до конца ВСЕЙ фразы, вместе с
                    // паузами между кусками: иначе микрофон службы успел бы
                    // услышать собственный голос Джарвиса
                    SpeechState.end()
                    onState(STATE_IDLE)
                    dialogFinished()
                }
            }
        }
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

    /**
     * Озвучивает ответ: интонация собирается из текста, либо берётся [mood]
     * (команды «скажи радостно: …»).
     */
    fun speak(text: String, mood: Emotion.Mood? = null, onDone: () -> Unit = {}) {
        val t = text.take(1200)
        if (!ready || !enabled || t.isBlank()) {
            onDone()
            return
        }
        if (appliedRevision != Voice.revision) refresh()
        val plan = Emotion.plan(
            t,
            Prefs.voicePitch(ctx),
            Prefs.voiceRate(ctx),
            Prefs.expression(ctx),
            mood
        )
        val c = chain
        if (c == null || plan.isEmpty()) {
            onDone()
            return
        }
        // speak() завершает прежнюю фразу — её владелец получит свой onDone,
        // поэтому свой колбэк ставим ПОСЛЕ запуска новой фразы
        c.speak(plan)
        pendingDone = onDone
    }

    /** Прервать озвучку (если что-то звучало — владелец получит колбэк). */
    fun stop() {
        chain?.stop()
        onState(STATE_IDLE)
    }

    fun shutdown() {
        chain?.stop()
        chain = null
        try { tts?.stop() } catch (e: Exception) { }
        try { tts?.shutdown() } catch (e: Exception) { }
        tts = null
        ready = false
        pendingDone = null
    }

    private fun dialogFinished() {
        val d = pendingDone
        pendingDone = null
        d?.invoke()
    }
}
