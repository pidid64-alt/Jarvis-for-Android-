package kz.jarvis.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Автодотправка сообщений в WhatsApp: состояние «ждём нажатия кнопки
 * „Отправить“» и проверка, что пользователь включил наш сервис специальных
 * возможностей (без него Android не даёт приложению жать кнопки в других
 * приложениях — текст наберётся, но отправить нужно будет вручную).
 *
 * Поток: [arm] ставим «пишем в такой-то чат такой-то текст», открываем
 * WhatsApp с уже набранным текстом, [AutoSendService] ловит окно чата и жмёт
 * «Отправить», [waitResult] (в фоновом потоке) дожидается клика.
 */
object AutoSend {

    /** Выдал ли пользователь нашему сервису доступ «спец. возможности». */
    fun enabled(c: Context): Boolean = try {
        val cn = ComponentName(c, AutoSendService::class.java)
        val raw = Settings.Secure.getString(c.contentResolver, "enabled_accessibility_services")
            ?: return false
        raw.split(':').any { it == cn.flattenToString() || it == cn.flattenToShortString() }
    } catch (e: Exception) {
        false
    }

    /** Открыть системный список спец. возможностей (там включается наш сервис). */
    fun openSettings(c: Context): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // --- «одноразовая» задача: сейчас открываем WhatsApp и надо нажать отправку ---

    @Volatile
    private var pkg: String? = null

    @Volatile
    private var text: String? = null

    @Volatile
    private var deadline = 0L

    @Volatile
    private var clicked = false

    /** Начать ожидание клика по кнопке «Отправить» в [targetPkg]. */
    fun arm(targetPkg: String, expectedText: String, timeoutMs: Long = 30_000) {
        pkg = targetPkg
        text = expectedText
        deadline = System.currentTimeMillis() + timeoutMs
        clicked = false
    }

    fun active(): Boolean = pkg != null && System.currentTimeMillis() < deadline

    /** Активный пакет, в котором ждём кнопку отправки. */
    fun targetPkg(): String? = pkg

    fun expectedText(): String? = text

    /** Пометить, что кнопка нажата (вызывается из сервиса). */
    fun markClicked() {
        clicked = true
        pkg = null
        text = null
    }

    /** Отменить (по таймауту). */
    fun clear() {
        pkg = null
        text = null
        deadline = 0L
    }

    /** Ждать клик до [timeoutMs]; true — сообщение ушло. */
    fun waitResult(timeoutMs: Long = 30_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (clicked) return true
            if (!active()) return clicked
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                return clicked
            }
        }
        clear()
        return clicked
    }
}
