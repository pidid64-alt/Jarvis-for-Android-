package kz.jarvis.app

import android.content.Context
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * Шумоподавление микрофона.
 *
 * SpeechRecognizer не отдаёт свою аудиосессию, поэтому системные эффекты
 * вешаются на глобальный входной микс (сессия 0) — тот же механизм, каким
 * прошивка включает «улучшение звука» для всех записей. Вместе с шумоподавлением
 * включаются эхоподавление (чтобы Джарвис не слышал собственный голос из
 * динамика) и автоусиление речи.
 *
 * Прошивки поддерживают разное, поэтому каждый эффект включается отдельно
 * и в try/catch: если устройство эффект не умеет — просто остаёмся без него,
 * приложение не падает. Что реально включилось — видно в настройках и в ответе
 * на голосовую команду «включи шумоподавление».
 *
 * Один объект на весь процесс: служба и окно приложения не конфликтуют.
 */
object AudioFx {

    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null

    /** Пытались ли мы включить эффекты в этом процессе. */
    private var applied = false

    /** Умеет ли прошивка аппаратное шумоподавление. */
    fun supported(): Boolean = try {
        NoiseSuppressor.isAvailable()
    } catch (e: Exception) {
        false
    }

    /** Хоть один эффект реально держится на микрофоне. */
    fun active(): Boolean = applied && (ns != null || aec != null || agc != null)

    /** Что именно включилось — по-русски, для настроек и голосового ответа. */
    fun activeNames(): List<String> {
        val out = mutableListOf<String>()
        if (ns != null) out += "шумоподавление"
        if (aec != null) out += "эхоподавление"
        if (agc != null) out += "автоусиление речи"
        return out
    }

    /**
     * Сверяет настройку «шумоподавление» с текущим состоянием и включает либо
     * выключает эффекты. Идемпотентно: можно дёргать сколько угодно — работа
     * происходит только при смене состояния.
     */
    fun sync(c: Context) {
        val want = Prefs.noiseSuppress(c)
        when {
            want && !applied -> enable()
            !want && applied -> release()
        }
    }

    private fun enable() {
        applied = true
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(0)?.apply { setEnabled(true) }
            }
        } catch (e: Exception) {
            ns = null
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(0)?.apply { setEnabled(true) }
            }
        } catch (e: Exception) {
            aec = null
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(0)?.apply { setEnabled(true) }
            }
        } catch (e: Exception) {
            agc = null
        }
    }

    fun release() {
        drop(ns)
        drop(aec)
        drop(agc)
        ns = null
        aec = null
        agc = null
        applied = false
    }

    private fun drop(fx: AudioEffect?) {
        if (fx == null) return
        try { fx.setEnabled(false) } catch (e: Exception) { }
        try { fx.release() } catch (e: Exception) { }
    }
}
