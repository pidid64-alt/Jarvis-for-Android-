package kz.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Срабатывает по будильнику напоминания или таймера: показывает уведомление
 * и озвучивает текст — приложение при этом может быть закрыто.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(ReminderScheduler.EXTRA_KIND)
            ?: ReminderScheduler.KIND_REMINDER
        val raw = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT)
        val text = raw?.trim().orEmpty().ifBlank {
            if (kind == ReminderScheduler.KIND_TIMER) "Время вышло" else "Напоминание"
        }
        val at = intent.getLongExtra(ReminderScheduler.EXTRA_AT, 0L)
        val timer = kind == ReminderScheduler.KIND_TIMER
        val spoken = if (timer) "Таймер, сэр. Время вышло." else "Напоминание, сэр. $text"
        val shown = if (timer) "Таймер. Время вышло." else text

        val pending = goAsync()
        try {
            ReminderScheduler.remove(context, at)
            Notify.reminder(context, shown)
        } catch (e: Exception) { /* уведомление — не критично */ }

        Speaker.once(context, spoken) {
            try {
                pending.finish()
            } catch (e: Exception) { }
        }
    }
}
