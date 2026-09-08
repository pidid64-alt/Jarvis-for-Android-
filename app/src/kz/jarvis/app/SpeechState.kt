package kz.jarvis.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * «Сейчас Джарвис говорит вслух» — общее состояние для всего процесса.
 *
 * Озвучек в приложении три, и микрофон фоновой службы не должен слушать
 * ни одну из них:
 *  • TtsController окна чата (MainActivity),
 *  • TtsController фоновой службы (WakeWordService),
 *  • одноразовый Speaker (напоминания).
 *
 * Без этого случалось вот что: команда «поищи инфу … и сделай презентацию»
 * открывала готовую презентацию в браузере, окно уходило в фон — и служба
 * снова брала микрофон. Доклад голосом всегда содержал путь к файлу
 * «Загрузки/Jarvis/…», синтезатор произносил его как «…Джарвис…», и служба
 * посреди доклада ловила собственное wake-слово: сигнал, «Слушаю, сэр»,
 * микрофон на «команду» — доклад обрывался на половине текста.
 *
 * [speaking] теперь проверяют и служба, и оба контроллера озвучки.
 */
object SpeechState {

    /** Сколько фраз звучит прямо сейчас (колбэки движка TTS). */
    private val active = AtomicInteger(0)

    /** Момент последнего события озвучки (начало или конец фразы). */
    @Volatile
    private var lastEvent = 0L

    /** Озвучка началась (onStart от движка). */
    fun begin() {
        active.incrementAndGet()
        lastEvent = System.currentTimeMillis()
    }

    /** Озвучка закончилась — фраза договорена, прервана или упала. */
    fun end() {
        lastEvent = System.currentTimeMillis()
        while (true) {
            val v = active.get()
            if (v <= 0 || active.compareAndSet(v, v - 1)) break
        }
    }

    /**
     * Говорит ли сейчас хоть одна озвучка. Короткая пауза после конца фразы
     * ([graceMs]) тоже считается речью: результаты распознавания приезжают
     * с задержкой, и эхо только что договорённой фразы может доехать позже
     * самой фразы.
     */
    fun speaking(graceMs: Long = 900): Boolean {
        val quiet = System.currentTimeMillis() - lastEvent
        if (active.get() > 0) {
            // защита от «залипшего» счётчика: живая фраза не может молчать минутами
            if (quiet > 300_000) {
                active.set(0)
                return false
            }
            return true
        }
        return lastEvent > 0 && quiet < graceMs
    }
}
