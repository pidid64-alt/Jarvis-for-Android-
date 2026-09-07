package kz.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Срабатывает по будильнику напоминания: показывает уведомление и
 * озвучивает текст — приложение при этом может быть закрыто.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT)
        if (text.isNullOrBlank()) return
        val at = intent.getLongExtra(ReminderScheduler.EXTRA_AT, 0L)

        val pending = goAsync()
        try {
            ReminderScheduler.remove(context, at)
            Notify.reminder(context, text)
        } catch (e: Exception) { /* уведомление — не критично */ }

        Speaker.once(context, "Напоминание, сэр. $text") {
            try {
                pending.finish()
            } catch (e: Exception) { }
        }
    }
}
