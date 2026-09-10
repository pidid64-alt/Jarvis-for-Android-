package kz.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import android.os.PowerManager

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
    private var lastScreenReview = 0L
    private var lastScreenText = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        try {
            if (AutoSend.active() && pkg == AutoSend.targetPkg()) tryClickSend()
            if (Prefs.screenWatch(this) && pkg != packageName) maybeDiscussScreen()
        } catch (e: Throwable) {
            // не роняем процесс из-за одного странного окна или ответа сети
        }
    }

    override fun onInterrupt() {}

    /** Опциональный режим: не снимает скриншоты, а читает только доступный текст UI. */
    private fun maybeDiscussScreen() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScreenReview < 5 * 60_000L) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (!pm.isInteractive || !Llm.ready(this)) return
        val root = rootInActiveWindow ?: return
        val text = collectVisibleText(root).trim().replace(Regex("\\s+"), " ").take(1800)
        if (text.length < 40 || text == lastScreenText) return
        lastScreenReview = now
        lastScreenText = text
        Thread {
            val answer = try {
                Llm.complete(this, "Ты наблюдаешь экран владельца. Если видишь понятную тему, которую уместно обсудить, ответь одной короткой фразой по-русски. Если повода нет, верни ровно NO.", "Текст экрана: $text", maxTokens = 80)
            } catch (_: Throwable) { "NO" }
            val clean = answer.trim()
            if (clean.isNotEmpty() && !clean.equals("NO", true) && clean.length < 220) {
                try { Speaker.once(applicationContext, clean) } catch (_: Throwable) { }
            }
        }.start()
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo): String {
        val out = StringBuilder()
        fun walk(n: AccessibilityNodeInfo) {
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append(' ') }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append(' ') }
            for (i in 0 until n.childCount) {
                try { n.getChild(i)?.let { ch -> walk(ch); ch.recycle() } } catch (_: Throwable) { }
                if (out.length > 2200) return
            }
        }
        walk(node)
        return out.toString()
    }

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
