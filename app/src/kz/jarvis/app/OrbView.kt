package kz.jarvis.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Светящаяся «сфера-реактор»: пульсирует и меняет цвет в зависимости от состояния.
 */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var state: State = State.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private var phase = 0f

    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
        start()
    }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun palette(s: State): IntArray = when (s) {
        State.IDLE -> intArrayOf(0xFF33D6FF.toInt(), 0xFF1178A0.toInt())
        State.LISTENING -> intArrayOf(0xFF9BEBFF.toInt(), 0xFF1FA8D6.toInt())
        State.THINKING -> intArrayOf(0xFFFFC94D.toInt(), 0xFFB97A00.toInt())
        State.SPEAKING -> intArrayOf(0xFF7FFFD4.toInt(), 0xFF0E9E7E.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val colors = palette(state)
        val pulse = if (state == State.IDLE) 0.5f + 0.5f * (0.5f + 0.5f * kotlin.math.sin(phase * 2f * Math.PI).toFloat()) else 1f

        // внешние расходящиеся кольца
        for (i in 0 until 3) {
            val frac = (phase + i / 3f) % 1f
            val r = maxR * (0.42f + 0.55f * frac)
            val alpha = ((1f - frac) * (if (state == State.IDLE) 60 else 130)).toInt().coerceIn(0, 255)
            ring.color = colors[0]
            ring.alpha = alpha
            ring.strokeWidth = (2.2f - frac * 1.4f) * resources.displayMetrics.density
            canvas.drawCircle(cx, cy, r, ring)
        }

        // ядро с радиальным градиентом
        val coreR = maxR * (0.36f + 0.06f * pulse * (if (state == State.IDLE) 0.5f else 1f))
        core.shader = RadialGradient(
            cx, cy, coreR,
            colors[0], colors[1],
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR, core)

        // блик-центр
        core.shader = RadialGradient(
            cx, cy, coreR * 0.45f,
            Color.WHITE, colors[0],
            Shader.TileMode.CLAMP
        )
        core.alpha = if (state == State.IDLE) 160 else 235
        canvas.drawCircle(cx, cy, coreR * 0.45f, core)
        core.alpha = 255
        core.shader = null
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
