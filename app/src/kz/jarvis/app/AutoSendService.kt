package kz.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Сервис специальных возможностей «Джарвис — автоотправка».
 *
 * Нужен, чтобы сообщение в WhatsApp уходило само: Android не даёт обычным
 * приложениям нажимать кнопки в чужих окнах, поэтому мы слушаем окна
 * WhatsApp (только когда сами начали отправку — см. AutoSend.arm) и жмём
 * кнопку «Отправить» за пользователя.
 *
 * Включается один раз: Настройки → Спец. возможности → «Джарвис —
 * автоотправка». Слушает ТОЛЬКО WhatsApp, ничего не делает вне наших задач.
 */
class AutoSendService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!AutoSend.active()) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != AutoSend.targetPkg()) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        try {
            tryClickSend()
        } catch (e: Throwable) {
            // не роняем процесс из-за одной неудачной попытки
        }
    }

    override fun onInterrupt() {}

    /** Ищет кнопку отправки и жмёт её, если текст уже вставлен в поле ввода. */
    private fun tryClickSend() {
        val root = rootInActiveWindow ?: return
        val want = AutoSend.expectedText() ?: return
        if (want.isNotBlank() && !hasComposerText(root, want)) return  // текст ещё не вставлен
        val target = findSendButton(root) ?: return
        if (target.isEnabled && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            AutoSend.markClicked()
        }
    }

    /** Есть ли уже набранный текст в поле ввода (EditText). */
    private fun hasComposerText(root: AccessibilityNodeInfo, want: String): Boolean {
        var found = false
        fun walk(n: AccessibilityNodeInfo) {
            if (found) return
            val cls = n.className?.toString().orEmpty()
            if (cls.endsWith("EditText")) {
                val t = n.text?.toString()?.trim().orEmpty()
                if (t == want || (want.length >= 6 && t.contains(want))) found = true
            }
            for (i in 0 until n.childCount) {
                val ch = try {
                    n.getChild(i)
                } catch (e: Exception) {
                    null
                }
                if (ch != null) {
                    walk(ch)
                    ch.recycle()
                }
            }
        }
        try {
            walk(root)
        } catch (e: Throwable) { }
        return found
    }

    /** Находит кликабельную кнопку отправки (по view-id или подписи). */
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1) точный view-id кнопки отправки WhatsApp / WhatsApp Business
        for (pkg in ChatMedia.WA_PACKAGES.map { it.first }) {
            val byId = try {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
            } catch (e: Exception) {
                null
            }
            if (!byId.isNullOrEmpty()) return byId.first()
        }
        // 2) обход: кликабельная кнопка с подписью «send»/«отправить» или id со «send»
        val names = setOf("send", "отправить", "отправ", "snd", "done", "послать")
        var best: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (best != null) return
            if (n.isClickable) {
                val desc = n.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
                val vid = n.viewIdResourceName?.lowercase().orEmpty()
                val txt = n.text?.toString()?.lowercase()?.trim().orEmpty()
                if (desc in names || txt in names || vid.contains(":id/send") || vid.contains(":id/single_btn")) {
                    best = n
                    return
                }
            }
            for (i in 0 until n.childCount) {
                val ch = try {
                    n.getChild(i)
                } catch (e: Exception) {
                    null
                }
                if (ch != null) {
                    walk(ch)
                    ch.recycle()
                }
            }
        }
        try {
            walk(root)
        } catch (e: Throwable) { }
        return best
    }
}
