package kz.jarvis.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StrictMode
import android.provider.MediaStore
import java.io.File

/**
 * Сохранение готовой презентации в «Загрузки/Jarvis» и её открытие.
 *
 * Android 10+ — через MediaStore (разрешения не нужны вовсе).
 * Android 8-9 — обычным файлом в общую папку «Download/Jarvis»; если система
 * не дала — в личную папку приложения (файл всё равно останется на телефоне).
 *
 * Сохраняются два файла:
 *  • .html — самодостаточная презентация, листается прямо в браузере телефона
 *            и печатается в PDF;
 *  • .md   — тот же материал текстом: вставляется в Google Слайды, Canva, Notion.
 */
object DeckFile {

    private const val DIR = "Jarvis"

    class Saved(
        val ok: Boolean,
        val name: String = "",
        val where: String = "",
        val uri: Uri? = null,
        val error: String = ""
    )

    /** Пишет html и markdown; возвращает результат по html-файлу. */
    fun save(c: Context, base: String, html: String, markdown: String): Saved {
        val htmlSaved = write(c, "$base.html", "text/html", html)
        write(c, "$base.md", "text/markdown", markdown)
        if (htmlSaved.ok) {
            Prefs.setLastDeck(c, htmlSaved.uri?.toString().orEmpty(), htmlSaved.where, base)
        }
        return htmlSaved
    }

    private fun write(c: Context, name: String, mime: String, text: String): Saved = try {
        if (Build.VERSION.SDK_INT >= 29) writeMediaStore(c, name, mime, text)
        else writeLegacy(c, name, text)
    } catch (e: Exception) {
        Saved(false, error = e.message?.take(120) ?: "ошибка записи")
    }

    private fun writeMediaStore(c: Context, name: String, mime: String, text: String): Saved {
        val resolver = c.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Saved(false, error = "система не дала место в «Загрузках»")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Saved(true, name, "Загрузки/$DIR/$name", uri)
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(c: Context, name: String, text: String): Saved {
        val downloads = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR
        )
        val dir = if (downloads.exists() || downloads.mkdirs()) downloads
        else File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DIR).apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(text, Charsets.UTF_8)
        return Saved(true, name, file.absolutePath, Uri.fromFile(file))
    }

    // ------------------------------------------------------------- открытие

    /** Открывает конкретный файл презентации в браузере/просмотрщике. */
    fun open(c: Context, saved: Saved): Boolean {
        val uri = saved.uri ?: return false
        return open(c, uri)
    }

    fun open(c: Context, uri: Uri): Boolean {
        if (uri.scheme == "file" && Build.VERSION.SDK_INT >= 24) {
            // без FileProvider (в проекте нет сторонних библиотек) система ругается
            // на file:// — снимаем именно эту проверку StrictMode
            try {
                StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            } catch (e: Exception) { }
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "text/html")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            c.startActivity(view)
            true
        } catch (e: Exception) {
            try {
                c.startActivity(
                    Intent.createChooser(view, "Открыть презентацию")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /** «Открой презентацию» — последняя сделанная. */
    fun openLast(c: Context): String {
        val uriText = Prefs.lastDeckUri(c)
        val path = Prefs.lastDeckPath(c)
        val topic = Prefs.lastDeckTopic(c)
        if (uriText.isEmpty() && path.isEmpty()) {
            return "Презентаций пока нет, сэр. Скажите: «поищи инфу про …и сделай презентацию»."
        }
        val uri = try {
            Uri.parse(uriText)
        } catch (e: Exception) {
            null
        }
        val opened = uri != null && open(c, uri)
        return if (opened) "Открываю презентацию «$topic», сэр."
        else "Файл лежит в $path, сэр, но открыть его нечем — поставьте любой браузер."
    }
}
