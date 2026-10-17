package kz.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * После перезагрузки (и после обновления приложения) пытаемся вернуть
 * фоновое прослушивание.
 *
 * На Android 14+ микрофон-службу запрещено поднимать из фона, поэтому
 * параллельно показываем уведомление «нажмите, чтобы включить» — по нажатию
 * откроется окно, а оно уже легально запустит службу.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        if (!relevant) return

        // Напоминания и таймеры AlarmManager забывает после перезагрузки.
        try { ReminderScheduler.restore(context) } catch (e: Exception) { }

        // Wi‑Fi-службе не нужен микрофон, поэтому Android разрешает вернуть её
        // после загрузки отдельно от голосовой активации. При LOCKED_BOOT
        // обычные SharedPreferences ещё могут быть недоступны — тогда повторит
        // BOOT_COMPLETED после разблокировки.
        try {
            if (Prefs.openWifiOn(context)) OpenWifiService.start(context)
        } catch (_: Exception) { }

        if (!Prefs.wakeOn(context)) return

        if (Build.VERSION.SDK_INT >= 34) {
            // Microphone FGS — while-in-use: Android 14–16 категорически
            // запрещает создавать его из BOOT_COMPLETED. Только уведомление,
            // запуск произойдёт после открытия видимой Activity.
            Notify.needRestart(context)
        } else {
            WakeWordService.start(context)
            Notify.needRestart(context) // если прошивка всё же запретила старт
        }
    }
}
