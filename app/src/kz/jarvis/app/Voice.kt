package kz.jarvis.app

import android.content.Context
import android.media.AudioAttributes
import android.media.audiofx.PresetReverb
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Голос Джарвиса поверх системного TextToSpeech.
 *
 * Что делает:
 *  • ставит высоту тона и скорость речи из выбранного профиля [VoiceProfile];
 *  • выбирает подходящий системный голос (мужской/женский, офлайн, повыше
 *    качеством) — или тот, который пользователь выбрал руками в настройках;
 *  • по желанию добавляет «эффект брони» — лёгкое эхо (PresetReverb).
 *
 * [revision] растёт при любом изменении настроек: [TtsController] видит это
 * и применяет новый тембр перед следующей фразой — без перезапуска службы.
 */
object Voice {

    @Volatile
    var revision: Int = 0
        private set

    private var reverb: PresetReverb? = null

    /** Сообщить всем озвучкам, что настройки голоса изменились. */
    fun changed() {
        revision++
    }

    private fun bump() = changed()

    // ------------------------------------------------------------- применение

    /** Настраивает готовый TextToSpeech под текущий профиль. */
    fun apply(c: Context, tts: TextToSpeech) {
        val p = Prefs.voiceProfile(c)
        try {
            tts.setPitch(VoiceProfile.clampPitch(Prefs.voicePitch(c)))
            tts.setSpeechRate(VoiceProfile.clampRate(Prefs.voiceRate(c)))
        } catch (e: Exception) { }

        try {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (e: Exception) { }

        try {
            val wanted = Prefs.voiceName(c)
            val all = tts.voices?.toList().orEmpty()
            val chosen = all.firstOrNull { it.name == wanted }
                ?: all.filter { it.locale?.language == "ru" }
                    .maxByOrNull {
                        VoiceProfile.scoreVoice(
                            it.name.orEmpty(),
                            it.locale?.toString().orEmpty(),
                            safeQuality(it),
                            safeNetwork(it),
                            p
                        )
                    }
            if (chosen != null) tts.voice = chosen else tts.language = Locale("ru", "RU")
        } catch (e: Exception) {
            try { tts.language = Locale("ru", "RU") } catch (e2: Exception) { }
        }

        syncFx(c)
    }

    private fun safeQuality(v: android.speech.tts.Voice): Int = try {
        v.quality
    } catch (e: Exception) {
        300
    }

    private fun safeNetwork(v: android.speech.tts.Voice): Boolean = try {
        v.isNetworkConnectionRequired
    } catch (e: Exception) {
        false
    }

    /** Список системных голосов для настроек: (имя, подпись). */
    fun list(tts: TextToSpeech?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("" to "Авто — Джарвис выберет сам")
        val voices = try {
            tts?.voices?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val p = VoiceProfile.byId(VoiceProfile.DEFAULT_ID)
        voices.filter { it.locale?.language == "ru" }
            .sortedByDescending {
                VoiceProfile.scoreVoice(
                    it.name.orEmpty(),
                    it.locale?.toString().orEmpty(),
                    safeQuality(it),
                    safeNetwork(it),
                    p
                )
            }
            .forEach { v ->
                val name = v.name.orEmpty()
                val gender = when {
                    VoiceProfile.looksMasculine(name) -> "мужской"
                    VoiceProfile.looksFeminine(name) -> "женский"
                    else -> "нейтральный"
                }
                val net = if (safeNetwork(v)) ", нужен интернет" else ", офлайн"
                out.add(name to "$name ($gender$net)")
            }
        return out
    }

    // -------------------------------------------------------------- эффект «брони»

    /** Включает или снимает эхо в зависимости от настройки. */
    fun syncFx(c: Context) {
        if (Prefs.voiceFx(c)) enableFx() else releaseFx()
    }

    private fun enableFx() {
        if (reverb != null) return
        reverb = try {
            PresetReverb(1, 0).apply {
                preset = PresetReverb.PRESET_LARGEHALL
                enabled = true
            }
        } catch (e: Exception) {
            null
        }
    }

    fun releaseFx() {
        val r = reverb ?: return
        try { r.enabled = false } catch (e: Exception) { }
        try { r.release() } catch (e: Exception) { }
        reverb = null
    }

    fun fxWorks(): Boolean = reverb != null

    // ------------------------------------------------------ команды и описания

    /** Выполняет голосовую команду про голос и возвращает ответ Джарвиса. */
    fun run(c: Context, cmd: VoiceProfile.Cmd): String = when (cmd) {
        is VoiceProfile.Cmd.Set -> {
            Prefs.setVoiceId(c, cmd.id)
            Prefs.setVoiceName(c, "")   // профиль сам подберёт голос
            bump()
            val p = VoiceProfile.byId(cmd.id)
            "Голос переключён: ${p.name}. ${VoiceProfile.sampleText()}"
        }
        is VoiceProfile.Cmd.Pitch -> {
            val v = VoiceProfile.clampPitch(Prefs.voicePitch(c) + cmd.delta)
            Prefs.setVoicePitch(c, v)
            bump()
            if (cmd.delta < 0) "Опускаю тон, сэр. Так лучше?" else "Поднимаю тон, сэр. Так лучше?"
        }
        is VoiceProfile.Cmd.Rate -> {
            val v = VoiceProfile.clampRate(Prefs.voiceRate(c) + cmd.delta)
            Prefs.setVoiceRate(c, v)
            bump()
            if (cmd.delta < 0) "Говорю медленнее, сэр." else "Говорю быстрее, сэр."
        }
        VoiceProfile.Cmd.Reset -> {
            Prefs.resetVoiceTone(c)
            bump()
            "Вернул тембр профиля «${Prefs.voiceProfile(c).name}», сэр."
        }
        VoiceProfile.Cmd.Test -> VoiceProfile.sampleText()
        VoiceProfile.Cmd.Info -> describe(c)
        VoiceProfile.Cmd.Settings -> "Открываю настройки голоса, сэр."
    }

    fun describe(c: Context): String {
        val p = Prefs.voiceProfile(c)
        val tone = VoiceProfile.describe(Prefs.voicePitch(c), Prefs.voiceRate(c))
        val manual = Prefs.voiceName(c)
        val voice = if (manual.isEmpty()) "голос подобран автоматически" else "выбран голос $manual"
        val fx = if (Prefs.voiceFx(c)) ", эффект брони включён" else ""
        val others = VoiceProfile.ALL.filter { it.id != p.id }.joinToString(", ") {
            it.name.substringBefore(" —")
        }
        return "Сейчас включён профиль «${p.name}»: $tone, $voice$fx. " +
            "Есть ещё: $others. Скажите, например, «голос брони» или «говори ниже»."
    }
}
