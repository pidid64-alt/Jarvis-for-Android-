package kz.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        if (!Prefs.wakeOn(context)) return

        WakeWordService.start(context)   // сработает, если система разрешит
        Notify.needRestart(context)      // а если нет — позовём пользователя
    }
}
