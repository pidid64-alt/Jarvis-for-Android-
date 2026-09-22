package kz.jarvis.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StrictMode
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Диктофон Джарвиса: пишет звук с микрофона в WAV и сохраняет его в
 * «Музыка/Jarvis» (Android 8–9 — в папку Jarvis на общей памяти).
 *
 * По умолчанию запись отдаётся СИСТЕМНОМУ диктофону телефона ([systemIntent]) —
 * см. Prefs.recSystem и команды «записывай системным/встроенным диктофоном».
 * Собственный рекордер остаётся вариантом «встроенный» и работает, когда
 * системного диктофона на телефоне нет:
 *  • запись стартует по КОДОВОМУ СЛОВУ владельца (см. [CodeWords]) — даже
 *    когда телефон в кармане и приложение закрыто;
 *  • между кусками записи Джарвис на пару секунд открывает микрофон для
 *    распознавателя: так запись можно остановить голосом («стоп запись»),
 *    а не только кнопкой в уведомлении;
 *  • всё склеивается в один файл — паузы на проверку голоса не рвут запись.
 *
 * Работает внутри уже поднятой фоновой службы [WakeWordService] (у неё тип
 * microphone), поэтому отдельный foreground-сервис не нужен: Android 14 всё
 * равно не дал бы поднять его из фона.
 */
object Recorder {

    private const val SAMPLE_RATE = 16000
    private const val BYTES_PER_SEC = SAMPLE_RATE * 2   // 16 бит, моно

    /** Диктофоны-заместители (когда стандартный интент никто не принял). */
    private val SYSTEM_PACKAGES = listOf(
        "com.google.android.apps.recorder",
        "com.android.soundrecorder",
        "com.sec.android.app.voicerecorder",
        "com.miui.voicerecorder",
        "org.lineageos.recorder",
        "com.simplemobiletools.recorder",
        "com.oneplus.recorder",
        "net.oneplus.recorder",
        "com.sonyericsson.soundrecorder",
        "com.htc.soundrecorder"
    )

    /**
     * Интент системного диктофона телефона («Запись голоса», Google Диктофон…);
     * null — на этом телефоне своего рекордера нет, пишем встроенным [start].
     */
    fun systemIntent(c: Context): Intent? {
        val standard = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        val resolved = try {
            standard.resolveActivity(c.packageManager)
        } catch (e: Exception) { null }
        if (resolved != null) return standard.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (pkg in SYSTEM_PACKAGES) {
            try {
                c.packageManager.getLaunchIntentForPackage(pkg)?.let {
                    return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (e: Exception) { }
        }
        return null
    }

    /** Готовая запись. */
    data class Saved(
        val ok: Boolean,
        val name: String = "",
        val where: String = "",
        val uri: Uri? = null,
        val seconds: Long = 0,
        val error: String = ""
    )

    @Volatile
    var recording: Boolean = false
        private set

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var beforePauseMs = 0L

    @Volatile
    private var segmentStartedAt = 0L

    @Volatile
    private var limitMs = 0L

    @Volatile
    private var onLimit: (() -> Unit)? = null

    private var thread: Thread? = null
    private var audio: AudioRecord? = null
    private var stream: FileOutputStream? = null
    private var cacheDir: File? = null
    private val segments = ArrayList<File>()

    /** Сколько секунд уже написано (с учётом пауз на распознавание). */
    fun elapsedSeconds(): Long {
        if (!recording) return 0
        val cur = if (segmentStartedAt > 0) System.currentTimeMillis() - segmentStartedAt else 0
        return ((beforePauseMs + cur) / 1000L).coerceAtLeast(0)
    }

    /**
     * Начинает запись.
     * @param limitMinutes ограничение длины: по нему запись остановится сама
     * @param onLimit      что сделать, когда время вышло
     */
    @Synchronized
    fun start(c: Context, limitMinutes: Int, onTimeUp: () -> Unit = {}): String? {
        if (recording) return null
        val dir = File(c.cacheDir, "rec").apply { mkdirs() }
        cacheDir = dir
        segments.clear()
        limitMs = limitMinutes.coerceIn(1, 600) * 60_000L
        onLimit = onTimeUp
        beforePauseMs = 0
        val err = startSegment()
        if (err != null) {
            cleanup()
            return err
        }
        recording = true
        startedAt = System.currentTimeMillis()
        return null
    }

    /**
     * Освободить микрофон, чтобы распознаватель послушал «стоп запись».
     * Кусок закрывается и остаётся в списке — потом склеится с остальными.
     */
    @Synchronized
    fun pauseForListening() {
        if (!recording) return
        beforePauseMs += if (segmentStartedAt > 0) System.currentTimeMillis() - segmentStartedAt else 0
        stopSegment()
    }

    /** Снова пишем — после того как пауза на распознавание закончилась. */
    @Synchronized
    fun resume(c: Context): String? {
        if (!recording) return "Запись уже остановлена, сэр."
        return startSegment()
    }

    /** Остановить и сохранить. Возвращает результат сохранения. */
    @Synchronized
    fun stop(c: Context): Saved {
        if (!recording) return Saved(false, error = "Запись не шла, сэр.")
        beforePauseMs += if (segmentStartedAt > 0) System.currentTimeMillis() - segmentStartedAt else 0
        recording = false
        stopSegment()
        val seconds = (beforePauseMs / 1000L)
        val saved = save(c, seconds)
        cleanup()
        return saved
    }

    // ------------------------------------------------------------ захват звука

    private fun startSegment(): String? {
        val minBuf = try {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (e: Exception) { -1 }
        if (minBuf <= 0) return "Телефон не дал записывать звук, сэр."
        val buf = minBuf * 2
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf
            )
        } catch (e: Exception) {
            return "Не смог включить микрофон: ${e.message?.take(60) ?: "ошибка"}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (e: Exception) { }
            return "Микрофон занят другим приложением, сэр."
        }
        val dir = cacheDir ?: return "Нет места для записи, сэр."
        val file = File(dir, "seg-${System.currentTimeMillis()}.pcm")
        val out = try {
            FileOutputStream(file)
        } catch (e: Exception) {
            try { rec.release() } catch (e2: Exception) { }
            return "Не смог создать файл записи: ${e.message?.take(60) ?: "ошибка"}"
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            try { rec.release() } catch (e2: Exception) { }
            try { out.close() } catch (e2: Exception) { }
            return "Не смог начать запись: ${e.message?.take(60) ?: "ошибка"}"
        }
        audio = rec
        stream = out
        segments.add(file)
        segmentStartedAt = System.currentTimeMillis()

        val data = ByteArray(minBuf)
        thread = Thread {
            var hitLimit = false
            while (recording && audio === rec) {
                val n = try {
                    rec.read(data, 0, data.size)
                } catch (e: Exception) { -1 }
                if (n < 0) break
                if (n > 0) {
                    try {
                        out.write(data, 0, n)
                    } catch (e: Exception) {
                        break
                    }
                }
                // время вышло: поток просто заканчивает кусок, а сохранением
                // занимается колбэк (он вызовет stop() и запишет файл)
                if (limitMs > 0 && elapsedSeconds() * 1000L >= limitMs) {
                    hitLimit = true
                    break
                }
            }
            if (hitLimit) {
                try {
                    onLimit?.invoke()
                } catch (e: Exception) { }
            }
        }.apply { isDaemon = true; name = "jarvis-rec" }
        thread?.start()
        return null
    }

    private fun stopSegment() {
        val rec = audio
        audio = null
        segmentStartedAt = 0
        try { rec?.stop() } catch (e: Exception) { }
        try { rec?.release() } catch (e: Exception) { }
        try { stream?.flush(); stream?.close() } catch (e: Exception) { }
        stream = null
        try { thread?.join(400) } catch (e: Exception) { }
        thread = null
    }

    private fun cleanup() {
        beforePauseMs = 0
        limitMs = 0
        onLimit = null
        val files = segments.toList()
        segments.clear()
        files.forEach { try { it.delete() } catch (e: Exception) { } }
    }

    // ------------------------------------------------------------- сохранение

    private fun save(c: Context, seconds: Long): Saved {
        val files = segments.toList().filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) return Saved(false, error = "Звук не записался, сэр.")
        val name = "Jarvis " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date()) + ".wav"
        val tmp = File(c.cacheDir, name)
        return try {
            writeWav(tmp, files)
            val saved = publish(c, name, tmp)
            try { tmp.delete() } catch (e: Exception) { }
            if (saved.ok) Prefs.addRecording(c, saved.name, saved.where, seconds, saved.uri?.toString().orEmpty())
            saved
        } catch (e: Exception) {
            Saved(false, error = e.message?.take(100) ?: "ошибка сохранения")
        }
    }

    /** Склеивает куски PCM и добавляет WAV-заголовок. */
    private fun writeWav(out: File, files: List<File>) {
        var total = 0L
        files.forEach { total += it.length() }
        FileOutputStream(out).use { fo ->
            fo.write(wavHeader(total))
            val buf = ByteArray(8192)
            for (f in files) {
                FileInputStream(f).use { fi ->
                    while (true) {
                        val n = fi.read(buf)
                        if (n <= 0) break
                        fo.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun wavHeader(dataSize: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        fun str(s: String) = s.toByteArray(Charsets.US_ASCII)
        b.put(str("RIFF"))
        b.putInt((36 + dataSize).toInt())
        b.put(str("WAVE"))
        b.put(str("fmt "))
        b.putInt(16)
        b.putShort(1)                       // PCM
        b.putShort(1)                       // моно
        b.putInt(SAMPLE_RATE)
        b.putInt(BYTES_PER_SEC)
        b.putShort(2)                       // байт в кадре
        b.putShort(16)                      // бит
        b.put(str("data"))
        b.putInt(dataSize.toInt())
        return b.array()
    }

    private fun publish(c: Context, name: String, tmp: File): Saved =
        if (Build.VERSION.SDK_INT >= 29) publishMediaStore(c, name, tmp)
        else publishLegacy(c, name, tmp)

    private fun publishMediaStore(c: Context, name: String, tmp: File): Saved {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Jarvis")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = c.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return Saved(false, error = "система не дала место в «Музыке»")
        c.contentResolver.openOutputStream(uri)?.use { out ->
            tmp.inputStream().use { it.copyTo(out) }
        } ?: return Saved(false, error = "не смог записать файл")
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        c.contentResolver.update(uri, values, null, null)
        return Saved(true, name, "Музыка/Jarvis/$name", uri)
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(c: Context, name: String, tmp: File): Saved {
        val music = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Jarvis"
        )
        val dir = if (music.exists() || music.mkdirs()) music
        else File(c.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Jarvis").apply { mkdirs() }
        val file = File(dir, name)
        tmp.copyTo(file, overwrite = true)
        // чтобы запись было видно в плеере без перезагрузки телефона
        try {
            android.media.MediaScannerConnection.scanFile(
                c, arrayOf(file.absolutePath), arrayOf("audio/wav"), null
            )
        } catch (e: Exception) { }
        return Saved(true, name, file.absolutePath, Uri.fromFile(file))
    }

    // --------------------------------------------------------------- прослушать

    /** Интент «открыть запись» (плеер телефона). */
    fun openIntent(c: Context, uri: Uri): Intent {
        if (uri.scheme == "file" && Build.VERSION.SDK_INT >= 24) {
            // FileProvider в проекте нет (нет сторонних библиотек) — как и в DeckFile
            try {
                StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            } catch (e: Exception) { }
        }
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "audio/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun playLast(c: Context): Pair<String, Intent?> {
        val last = Prefs.recordings(c).lastOrNull()
            ?: return Pair("Записей пока нет, сэр. Скажите «включи диктофон».", null)
        val uri = try {
            Uri.parse(last.uri)
        } catch (e: Exception) { null }
        if (uri == null) return Pair("Запись «${last.name}» сохранена в ${last.where}, но открыть её нечем.", null)
        return Pair("Включаю запись «${last.name}» (${RecCmd.durationLabel(last.seconds)}).", openIntent(c, uri))
    }

    fun list(c: Context): String {
        val all = Prefs.recordings(c)
        if (all.isEmpty()) return "Записей пока нет, сэр."
        val tail = all.takeLast(5).reversed().joinToString("; ") {
            "«${it.name}» — ${RecCmd.durationLabel(it.seconds)}"
        }
        val more = if (all.size > 5) " Всего записей: ${all.size}." else ""
        return "Последние записи, сэр: $tail.$more"
    }

    fun deleteLast(c: Context): String {
        val all = Prefs.recordings(c)
        val last = all.lastOrNull() ?: return "Удалять нечего, сэр: записей нет."
        try {
            val uri = Uri.parse(last.uri)
            if (uri.scheme == "content") c.contentResolver.delete(uri, null, null)
            else File(uri.path ?: last.where).delete()
        } catch (e: Exception) { }
        Prefs.setRecordings(c, all.dropLast(1))
        return "Удалил запись «${last.name}», сэр."
    }
}
