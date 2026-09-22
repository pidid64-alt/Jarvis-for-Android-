package kz.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Каналы уведомлений и сами уведомления. */
object Notify {

    const val CH_REMINDERS = "jarvis_reminders"
    const val CH_AUTO = "jarvis_auto"
    const val CH_REC = "jarvis_recorder"
    private const val ID_REMINDER_BASE = 4200
    private const val ID_REC = 79

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)

        val wake = NotificationChannel(
            WakeWordService.CH_ID,
            "Голосовая активация",
            NotificationManager.IMPORTANCE_LOW
        )
        wake.description = "Фоновая служба ожидания команды «Джарвис»"
        wake.setShowBadge(false)
        nm.createNotificationChannel(wake)

        val rem = NotificationChannel(
            CH_REMINDERS,
            "Напоминания",
            NotificationManager.IMPORTANCE_HIGH
        )
        rem.description = "Голосовые напоминания, поставленные командой «напомни …»"
        nm.createNotificationChannel(rem)

        val auto = NotificationChannel(
            CH_AUTO,
            "Автоответчик",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        auto.description = "Что Джарвис ответил в мессенджерах за вас"
        nm.createNotificationChannel(auto)

        val rec = NotificationChannel(
            CH_REC,
            "Диктофон",
            NotificationManager.IMPORTANCE_LOW
        )
        rec.description = "Идёт запись звука и готовые записи Джарвиса"
        rec.setShowBadge(false)
        nm.createNotificationChannel(rec)
    }

    // ------------------------------------------------------------------ диктофон

    /** Идёт запись: видно, сколько уже написано, и есть кнопка «Остановить». */
    fun recording(c: Context, seconds: Long) {
        val stop = PendingIntent.getService(
            c, 20,
            Intent(c, WakeWordService::class.java).setAction(WakeWordService.ACTION_REC_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(c, CH_REC)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("Джарвис пишет звук")
            .setContentText("Идёт запись: " + RecCmd.durationLabel(seconds))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Остановить", stop)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java).notify(ID_REC, n)
        } catch (e: Exception) { }
    }

    /** Запись готова: нажатие открывает её в плеере. */
    fun recordingSaved(c: Context, saved: Recorder.Saved) {
        if (!saved.ok) {
            recordingNote(c, "Запись не сохранилась", saved.error)
            return
        }
        val uri = saved.uri
        val open = if (uri != null) PendingIntent.getActivity(
            c, 21, Recorder.openIntent(c, uri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) else null
        val n: Notification = Notification.Builder(c, CH_REC)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("Запись готова · " + RecCmd.durationLabel(saved.seconds))
            .setContentText(saved.where)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java).notify(ID_REC, n)
        } catch (e: Exception) { }
    }

    private fun recordingNote(c: Context, title: String, text: String) {
        val n: Notification = Notification.Builder(c, CH_REC)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java).notify(ID_REC, n)
        } catch (e: Exception) { }
    }

    fun reminder(c: Context, text: String) {
        val open = PendingIntent.getActivity(
            c, 1,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(c, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("Джарвис напоминает")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java)
                .notify(ID_REMINDER_BASE + (System.currentTimeMillis() % 1000).toInt(), n)
        } catch (e: Exception) { /* нет разрешения на уведомления */ }
    }

    /** Готовая презентация: нажатие открывает файл. */
    fun deck(c: Context, title: String, saved: DeckFile.Saved) {
        val uri = saved.uri ?: return
        val open = PendingIntent.getActivity(
            c, 5,
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "text/html")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(c, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("Презентация готова: $title")
            .setContentText(saved.where)
            .setStyle(Notification.BigTextStyle().bigText("Сохранил в ${saved.where}. Нажмите, чтобы открыть."))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java).notify(78, n)
        } catch (e: Exception) { }
    }

    /** Автоответчик: Джарвис ответил в чате. */
    fun autoReplySent(c: Context, appLabel: String, chatTitle: String, reply: String) {
        val open = PendingIntent.getActivity(
            c, 8,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(c, CH_AUTO)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("$appLabel · ответил $chatTitle")
            .setContentText(reply)
            .setStyle(Notification.BigTextStyle().bigText(
                "Я ответил за вас в чате «$chatTitle»:\n$reply\n\n" +
                    "Выключить автоответ: скажите «выключи автоответ» или в настройках."
            ))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java)
                .notify(ID_REMINDER_BASE + 100, n)
        } catch (e: Exception) { }
    }

    /** Автоответчик: ответить не вышло (нет действия «Ответить» в уведомлении). */
    fun autoReplyNoAction(c: Context, chatTitle: String) {
        autoReplyNote(
            c, "Не смог ответить «$chatTitle»",
            "Мессенджер не прислал действие «Ответить» для этого сообщения — ответьте сами, сэр."
        )
    }

    /** Автоответчик: модель недоступна (нет ключа ИИ). */
    fun autoReplyNoModel(c: Context, chatTitle: String) {
        autoReplyNote(
            c, "Автоответ не сработал («$chatTitle»)",
            "Для автоответов нужен настроенный провайдер ИИ: Настройки → Провайдер ИИ."
        )
    }

    /** Автоответчик: ошибка сети/модели. */
    fun autoReplyError(c: Context, chatTitle: String, msg: String?) {
        autoReplyNote(
            c, "Автоответ не удался («$chatTitle»)",
            "Ошибка: ${msg?.take(120) ?: "неизвестно"}"
        )
    }

    private fun autoReplyNote(c: Context, title: String, text: String) {
        val open = PendingIntent.getActivity(
            c, 9,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(c, CH_AUTO)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java).notify(521, n)
        } catch (e: Exception) { }
    }

    /** Подсказка, почему автоответчик пропустил сообщение (не чаще раза в день). */
    fun autoReplyHint(c: Context, text: String) {
        val open = PendingIntent.getActivity(
            c, 10,
            Intent(c, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(c, CH_AUTO)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("Джарвис · автоответчик")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text + "\n\nНажмите — откроются настройки."))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java).notify(522, n)
        } catch (e: Exception) { }
    }

    /** Подсказка после перезагрузки: микрофон-службу нельзя поднять из фона. */
    fun needRestart(c: Context) {
        val open = PendingIntent.getActivity(
            c, 2,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_RESTART_WAKE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(c, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("Джарвис не слушает")
            .setContentText("Нажмите, чтобы снова включить ожидание слова «Джарвис»")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java).notify(77, n)
        } catch (e: Exception) { }
    }
}
