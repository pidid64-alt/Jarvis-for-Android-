package kz.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * «Руки» Джарвиса: системные жесты через сервис спец. возможностей.
 * Без него Android не даёт закрывать чужие окна — отсюда и была тишина
 * на «закрой это».
 */
object Hands {

    @Volatile
    private var bound: AccessibilityService? = null

    fun bind(s: AccessibilityService) {
        bound = s
    }

    fun unbind(s: AccessibilityService) {
        if (bound === s) bound = null
    }

    /** Сервис жив и может жать «Назад» / «Домой». */
    fun connected(): Boolean = bound != null

    /** Пользователь включил наш сервис в системном списке. */
    fun enabled(c: Context): Boolean = try {
        AutoSend.enabled(c)
    } catch (_: Throwable) {
        false
    }

    fun act(kind: WindowCmd.Kind): Boolean = when (kind) {
        WindowCmd.Kind.BACK, WindowCmd.Kind.CLOSE ->
            global(AccessibilityService.GLOBAL_ACTION_BACK)
        WindowCmd.Kind.HOME ->
            global(AccessibilityService.GLOBAL_ACTION_HOME)
        WindowCmd.Kind.RECENTS ->
            global(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    private fun global(code: Int): Boolean {
        val s = bound ?: return false
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                s.performGlobalAction(code)
            } else {
                var ok = false
                val done = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    ok = try {
                        s.performGlobalAction(code)
                    } catch (_: Throwable) {
                        false
                    }
                    done.countDown()
                }
                done.await(800, TimeUnit.MILLISECONDS)
                ok
            }
        } catch (_: Throwable) {
            false
        }
    }
}
