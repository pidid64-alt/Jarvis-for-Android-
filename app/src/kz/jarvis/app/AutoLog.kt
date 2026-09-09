package kz.jarvis.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал автоответчика: последние шаги службы — в памяти и в файле
 * filesDir/autoreply.log. Нужен, чтобы «не работает» превращалось в
 * конкретную строчку: подключилась ли служба, увидела ли сообщение,
 * что решила модель, ушёл ли ответ.
 *
 * Кольцо в памяти хранит последние [KEEP] строк; файл дописывается и
 * обрезается, чтобы не разрастаться.
 */
object AutoLog {

    private const val KEEP = 150
    private const val FILE = "autoreply.log"
    private const val MAX_FILE = 200_000L

    private val buf = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(c: Context?, line: String) {
        val ts = fmt.format(Date())
        synchronized(buf) {
            buf.add("[$ts] $line")
            while (buf.size > KEEP) buf.removeAt(0)
        }
        if (c != null) {
            try {
                appendFile(c, "[$ts] $line\n")
            } catch (e: Exception) {
                // журнал — не самоцель, молча терпим
            }
        }
    }

    /** Переносит прежние строки из файла в память (вызывается при открытии). */
    fun attach(c: Context) {
        synchronized(buf) {
            if (buf.isNotEmpty()) return
            try {
                val f = File(c.filesDir, FILE)
                if (f.exists()) f.readLines().takeLast(KEEP).forEach { buf.add(it) }
            } catch (e: Exception) { }
        }
    }

    /** Последние строки журнала (пусто — «журнал пуст»). */
    fun tail(n: Int = 80): String = synchronized(buf) {
        buf.takeLast(n).joinToString("\n").ifEmpty { "(журнал пуст)" }
    }

    private fun appendFile(c: Context, s: String) {
        val f = File(c.filesDir, FILE)
        if (f.exists() && f.length() > MAX_FILE) {
            val keep = f.readLines().takeLast(400).joinToString("\n") + "\n"
            f.writeText(keep)
        }
        f.appendText(s)
    }
}
