package kz.jarvis.app

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Кнопки в уведомлении о сообщении от приоритетного контакта:
 *
 *  • «Ответить» — владелец диктует или печатает ответ прямо в уведомлении,
 *    Джарвис оформляет его и присылает подтверждение «Отправляю … — ок?»;
 *  • «Отправить» — явное подтверждение (без него ни одно сообщение не уходит);
 *  • «Не отвечать» / «Отмена» — закрыть запрос.
 *
 * Всё, что связано с моделью и отправкой, делаем в фоновом потоке.
 */
class PriorityReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ANSWER = "kz.jarvis.app.PRIORITY_ANSWER"
        const val ACTION_SEND = "kz.jarvis.app.PRIORITY_SEND"
        const val ACTION_CANCEL = "kz.jarvis.app.PRIORITY_CANCEL"
        const val EXTRA_ID = "priority_request_id"
        const val KEY_REPLY = "jarvis_priority_reply"
    }

    override fun onReceive(ctx: Context, intent: Intent?) {
        val id = intent?.getIntExtra(EXTRA_ID, -1) ?: -1
        if (id < 0) return
        val async = goAsync()
        Thread {
            val step: PriorityInbox.Step? = try {
                when (intent?.action) {
                    ACTION_ANSWER -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(KEY_REPLY)?.toString().orEmpty()
                        if (text.isBlank()) null else PriorityInbox.answer(ctx.applicationContext, id, text)
                    }
                    ACTION_SEND -> PriorityInbox.sendById(ctx.applicationContext, id)
                    ACTION_CANCEL -> PriorityInbox.cancelById(ctx.applicationContext, id)
                    else -> null
                }
            } catch (t: Throwable) {
                AutoLog.add(ctx.applicationContext, "Кнопка уведомления: ${t.message?.take(80) ?: "сбой"}")
                null
            }
            if (step != null && step.say.isNotBlank() && Prefs.priorityVoice(ctx)) {
                try {
                    Speaker.once(ctx.applicationContext, step.say)
                } catch (e: Exception) { }
            }
            async?.finish()
        }.start()
    }
}
