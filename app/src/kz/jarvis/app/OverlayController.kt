package kz.jarvis.app

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Плавающая сфера поверх других приложений: видно, что Джарвис слушает,
 * даже когда само приложение закрыто.
 *
 * Требует разрешение «Показ поверх других окон» (Settings.canDrawOverlays).
 * Заодно это разрешение снимает с Android запрет на запуск Activity из фона,
 * поэтому команды вида «открой ютуб» работают при закрытом приложении.
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var text: TextView? = null
    private val hide = Runnable { hide() }

    val available: Boolean
        get() = try {
            Settings.canDrawOverlays(ctx)
        } catch (e: Exception) {
            false
        }

    fun show(message: String) {
        ui.post {
            ensureView()
            text?.text = message
            ui.removeCallbacks(hide)
        }
    }

    fun update(message: String) {
        ui.post {
            if (view == null) {
                show(message)
            } else {
                text?.text = message
            }
        }
    }

    fun hide() {
        ui.post {
            ui.removeCallbacks(hide)
            try {
                view?.let { wm.removeView(it) }
            } catch (e: Exception) { }
            view = null
            text = null
        }
    }

    /** Автоскрытие через паузу (чтобы сфера не висела вечно). */
    fun autoHide(delayMs: Long) {
        ui.removeCallbacks(hide)
        ui.postDelayed(hide, delayMs)
    }

    private fun ensureView() {
        if (view != null) return
        if (!available) return
        val v = try {
            LayoutInflater.from(ctx).inflate(R.layout.overlay_orb, null)
        } catch (e: Exception) {
            return
        }
        text = v.findViewById(R.id.ovText)
        v.findViewById<View>(R.id.ovOrb).setOnClickListener {
            try {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) { }
            hide()
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (ctx.resources.displayMetrics.density * 96).toInt()
        }
        try {
            wm.addView(v, lp)
            view = v
        } catch (e: Exception) {
            view = null
        }
    }
}
