package kz.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent

/** Каналы уведомлений и сами уведомления. */
object Notify {

    const val CH_REMINDERS = "jarvis_reminders"
    const val CH_AUTO = "jarvis_auto"
    const val CH_PRIORITY = "jarvis_priority"
    private const val ID_REMINDER_BASE = 4200

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

        val prio = NotificationChannel(
            CH_PRIORITY,
            "Приоритетные контакты",
            NotificationManager.IMPORTANCE_HIGH
        )
        prio.description = "Сообщения от важных контактов: Джарвис спрашивает, что ответить"
        nm.createNotificationChannel(prio)
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


    // ------------------------------------------------------- приоритетные контакты
    //
    // Джарвис НЕ отвечает за владельца сам: показывает сообщение, даёт ответить
    // прямо из уведомления и уходит на отправку только по кнопке «Отправить».

    /** «Мама пишет: «Ты покушал?» — Что ответить Маме?» (или предложенный черновик). */
    fun priorityAsk(c: Context, r: PriorityInbox.Request) {
        val body = PriorityInbox.notifyText(r)
        val n: Notification = Notification.Builder(c, CH_PRIORITY)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("${if (r.isPriority()) "★ " else ""}${r.title} · ${r.appLabel}")
            .setContentText(body.replace('\n', ' '))
            .setStyle(
                Notification.BigTextStyle().bigText(
                    body + "\n\nОтветьте прямо здесь (или голосом) — я оформлю ответ, " +
                        "покажу его вам и отправлю только после вашего «ок»."
                )
            )
            .addAction(replyAction(c, r, "Ответить", 1))
            .addAction(plainAction(c, r, PriorityReceiver.ACTION_CANCEL, "Не отвечать", 3))
            .setContentIntent(openMain(c, 12))
            .setAutoCancel(false)
            .build()
        post(c, r.notifId, n)
    }

    /** «Отправляю Маме: «…» — ок?» + кнопки «Отправить» / «Изменить» / «Отмена». */
    fun priorityConfirm(c: Context, r: PriorityInbox.Request) {
        val body = PriorityInbox.notifyText(r)
        val n: Notification = Notification.Builder(c, CH_PRIORITY)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("${if (r.isPriority()) "★ " else ""}Отправить ${PriorityLogic.dative(r.title)}?")
            .setContentText(body.replace('\n', ' '))
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Было сообщение: ${PriorityLogic.incomingLine(r.title, r.text)}\n\n" +
                        body + "\n\n«Отправить» — уйдёт в ${r.appLabel}. " +
                        "«Изменить» — напишите свой вариант."
                )
            )
            .addAction(plainAction(c, r, PriorityReceiver.ACTION_SEND, "Отправить", 2))
            .addAction(replyAction(c, r, "Изменить", 1))
            .addAction(plainAction(c, r, PriorityReceiver.ACTION_CANCEL, "Отмена", 3))
            .setContentIntent(openMain(c, 12))
            .setAutoCancel(false)
            .build()
        post(c, r.notifId, n)
    }

    /** Итог: что ушло. */
    fun prioritySent(c: Context, r: PriorityInbox.Request) {
        val n: Notification = Notification.Builder(c, CH_PRIORITY)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("✔ Отправил ${PriorityLogic.dative(r.title)}")
            .setContentText(r.draft)
            .setStyle(
                Notification.BigTextStyle().bigText(
                    PriorityLogic.sentLine(r.title, r.draft) + "\n\nМессенджер: ${r.appLabel}."
                )
            )
            .setContentIntent(openMain(c, 12))
            .setAutoCancel(true)
            .build()
        post(c, r.notifId, n)
    }

    /** Отправить не вышло — просим сделать это руками. */
    fun priorityFailed(c: Context, r: PriorityInbox.Request) {
        val n: Notification = Notification.Builder(c, CH_PRIORITY)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("Не смог отправить ${PriorityLogic.dative(r.title)}")
            .setContentText(PriorityLogic.failedLine(r.title))
            .setStyle(Notification.BigTextStyle().bigText(PriorityLogic.failedLine(r.title)))
            .setContentIntent(openMain(c, 12))
            .setAutoCancel(true)
            .build()
        post(c, r.notifId, n)
    }

    /** Мягкое напоминание о неотвеченном сообщении. */
    fun priorityRemind(c: Context, r: PriorityInbox.Request, minutes: Long) {
        val body = PriorityLogic.remindLine(r.title, r.text, minutes)
        val n: Notification = Notification.Builder(c, CH_PRIORITY)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("★ ${r.title} ждёт ответа")
            .setContentText(body.replace('\n', ' '))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .addAction(replyAction(c, r, "Ответить", 1))
            .addAction(plainAction(c, r, PriorityReceiver.ACTION_CANCEL, "Не отвечать", 3))
            .setContentIntent(openMain(c, 12))
            .setAutoCancel(false)
            .build()
        post(c, r.notifId, n)
    }

    fun cancelPriority(c: Context, notifId: Int) {
        try {
            c.getSystemService(NotificationManager::class.java).cancel(notifId)
        } catch (e: Exception) { }
    }

    private fun replyAction(
        c: Context,
        r: PriorityInbox.Request,
        label: String,
        code: Int
    ): Notification.Action = Notification.Action.Builder(
        R.drawable.ic_send, label, priorityPi(c, r, PriorityReceiver.ACTION_ANSWER, code, mutable = true)
    ).addRemoteInput(
        RemoteInput.Builder(PriorityReceiver.KEY_REPLY)
            .setLabel("Что ответить?")
            .build()
    ).build()

    private fun plainAction(
        c: Context,
        r: PriorityInbox.Request,
        action: String,
        label: String,
        code: Int
    ): Notification.Action = Notification.Action.Builder(
        R.drawable.ic_send, label, priorityPi(c, r, action, code, mutable = false)
    ).build()

    private fun priorityPi(
        c: Context,
        r: PriorityInbox.Request,
        action: String,
        code: Int,
        mutable: Boolean
    ): PendingIntent {
        // FLAG_MUTABLE появился только в Android 12; на старых версиях
        // PendingIntent и так изменяемый — флаг просто не передаём
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (!mutable && android.os.Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_IMMUTABLE
            } else if (mutable && android.os.Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_MUTABLE
            } else 0
        return PendingIntent.getBroadcast(
            c, r.id * 10 + code,
            Intent(c, PriorityReceiver::class.java)
                .setAction(action)
                .putExtra(PriorityReceiver.EXTRA_ID, r.id),
            flags
        )
    }

    private fun openMain(c: Context, code: Int): PendingIntent =
        PendingIntent.getActivity(
            c, code,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun post(c: Context, id: Int, n: Notification) {
        try {
            c.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: Exception) { /* нет разрешения на уведомления */ }
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
