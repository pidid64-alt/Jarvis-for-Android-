package kz.jarvis.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * Мост в Claude: Джарвис «заходит в Клод» и вставляет туда то, что нашёл.
 *
 * Порядок такой:
 *  1. текст всегда кладётся в буфер обмена — его можно вставить куда угодно;
 *  2. если установлено приложение Claude — открываем его через «Поделиться»,
 *     текст уже в поле ввода;
 *  3. иначе открываем claude.ai в браузере с подставленным запросом.
 *
 * Работает и из фоновой службы: Intent помечается NEW_TASK.
 */
object ClaudeBridge {

    /** Известные идентификаторы официального приложения Claude. */
    private val PACKAGES = listOf(
        "com.anthropic.claude",
        "com.anthropic.claude.android",
        "ai.anthropic.claude"
    )

    /** Сколько символов влезает в ссылку claude.ai/new?q=… без обрезки браузером. */
    private const val URL_LIMIT = 5_000

    fun installedPackage(c: Context): String? {
        val pm = c.packageManager
        for (p in PACKAGES) {
            try {
                pm.getLaunchIntentForPackage(p) ?: continue
                return p
            } catch (e: Exception) { }
        }
        return null
    }

    fun installed(c: Context): Boolean = installedPackage(c) != null

    /** Кладёт текст в буфер обмена — «Джарвис уже всё скопировал». */
    fun copy(c: Context, text: String) {
        try {
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Джарвис: материал для Клода", text))
        } catch (e: Exception) { }
    }

    /**
     * Открывает Клод с готовым текстом.
     * @return что рассказать пользователю голосом
     */
    fun send(c: Context, text: String): String {
        copy(c, text)
        val pkg = installedPackage(c)
        if (pkg != null) {
            val share = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .setPackage(pkg)
                .putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(c, share)) {
                return "Открыл Клод и передал ему всё, что нашёл, сэр. Текст заодно в буфере обмена."
            }
            val launch = try {
                c.packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } catch (e: Exception) {
                null
            }
            if (launch != null && start(c, launch)) {
                return "Клод открыт, сэр. Материал в буфере обмена — вставьте его в чат долгим нажатием."
            }
        }
        return openWeb(c, text)
    }

    /** Запасной путь: сайт claude.ai с подставленным запросом. */
    fun openWeb(c: Context, text: String): String {
        val short = text.take(URL_LIMIT)
        val url = if (text.length <= URL_LIMIT) {
            "https://claude.ai/new?q=" + URLEncoder.encode(short, "UTF-8")
        } else {
            "https://claude.ai/new"
        }
        val browser = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(c, browser)) {
            if (text.length <= URL_LIMIT) {
                "Приложения Клода нет, сэр — открыл claude.ai и вписал материал в запрос."
            } else {
                "Открыл claude.ai, сэр. Материал длинный, поэтому он в буфере обмена — вставьте его в чат."
            }
        } else {
            "Не смог открыть Клод, сэр. Материал скопирован в буфер обмена — вставьте его вручную."
        }
    }

    private fun start(c: Context, i: Intent): Boolean = try {
        c.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }
}
