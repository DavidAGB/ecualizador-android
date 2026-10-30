package com.davidagb.ecualizador

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** Curva del ecualizador con un control arrastrable por banda. */
class EqView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var onChange: ((Int, Float) -> Unit)? = null
    var onCommit: (() -> Unit)? = null

    private var gains = FloatArray(0)
    private var touched = -1
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private val accent = context.getColor(R.color.accent)
    private val surface = context.getColor(R.color.surface)

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.line); strokeWidth = dp
    }
    private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted); strokeWidth = dp; alpha = 120
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 2.5f * dp
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; alpha = 38 }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = surface; style = Paint.Style.STROKE; strokeWidth = 2.5f * dp
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted); textSize = 10 * sp
        typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    private val dbLabel = Paint(label).apply { textSize = 9 * sp; textAlign = Paint.Align.RIGHT }
    private val value = Paint(label).apply {
        color = context.getColor(R.color.ink); textSize = 12 * sp; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val curve = Path()
    private val fill = Path()
    private var px = FloatArray(0)
    private var py = FloatArray(0)

    private val gutter get() = 28 * dp
    private val top get() = 30 * dp
    private val bottom get() = height - 26 * dp
    private val colW get() = (width - gutter - 4 * dp) / gains.size

    fun setGains(g: FloatArray) {
        if (g.size != gains.size) {
            gains = FloatArray(g.size)
            px = FloatArray(g.size)
            py = FloatArray(g.size)
        }
        g.copyInto(gains)
        invalidate()
    }

    private fun x(i: Int) = gutter + colW * (i + 0.5f)
    private fun y(g: Float) = top + (Eq.maxDb - g) / (2 * Eq.maxDb) * (bottom - top)
    private fun gainAt(y: Float): Float {
        val g = Eq.maxDb - (y - top) / (bottom - top) * 2 * Eq.maxDb
        return ((g * 2).roundToInt() / 2f).coerceIn(-Eq.maxDb, Eq.maxDb)
    }

    override fun onDraw(canvas: Canvas) {
        if (gains.isEmpty()) return
        val right = width.toFloat()
        val m = Eq.maxDb.toInt()
        for (db in intArrayOf(m, m / 2, 0, -m / 2, -m)) {
            val yy = y(db.toFloat())
            canvas.drawLine(gutter, yy, right, yy, if (db == 0) zero else grid)
            canvas.drawText(if (db > 0) "+$db" else "$db", gutter - 6 * dp, yy + 3 * dp, dbLabel)
        }
        for (i in gains.indices) {
            px[i] = x(i)
            py[i] = y(gains[i])
            canvas.drawLine(px[i], top, px[i], bottom, grid)
        }

        // Curva suave (Catmull-Rom) que pasa por todos los controles.
        curve.reset()
        curve.moveTo(gutter, py[0])
        curve.lineTo(px[0], py[0])
        val n = gains.size
        for (i in 0 until n - 1) {
            val i0 = maxOf(i - 1, 0)
            val i3 = minOf(i + 2, n - 1)
            curve.cubicTo(
                px[i] + (px[i + 1] - px[i0]) / 6, py[i] + (py[i + 1] - py[i0]) / 6,
                px[i + 1] - (px[i3] - px[i]) / 6, py[i + 1] - (py[i3] - py[i]) / 6,
                px[i + 1], py[i + 1]
            )
        }
        curve.lineTo(right, py[n - 1])
        val y0 = y(0f)
        fill.set(curve)
        fill.lineTo(right, y0)
        fill.lineTo(gutter, y0)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(curve, curvePaint)

        for (i in 0 until n) {
            val r = if (i == touched) 11 * dp else 8 * dp
            canvas.drawCircle(px[i], py[i], r, knob)
            canvas.drawCircle(px[i], py[i], r, ring)
            canvas.drawText(fmt(gains[i]), px[i], top - 12 * dp, if (i == touched) value else label)
            canvas.drawText(freq(Eq.freqs[i]), px[i], height - 6 * dp, label)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (gains.isEmpty()) return false
                touched = ((e.x - gutter) / colW).toInt().coerceIn(0, gains.size - 1)
                parent?.requestDisallowInterceptTouchEvent(true)
                drag(e.y)
            }
            MotionEvent.ACTION_MOVE -> drag(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touched = -1
                invalidate()
                onCommit?.invoke()
            }
        }
        return true
    }

    private fun drag(y: Float) {
        val i = touched.takeIf { it >= 0 } ?: return
        val g = gainAt(y)
        if (g == gains[i]) return
        if (g % 3f == 0f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        gains[i] = g
        invalidate()
        onChange?.invoke(i, g)
    }

    private fun fmt(g: Float): String = when {
        g == 0f -> "0"
        abs(g % 1f) < 0.01f -> "%+d".format(g.toInt())
        else -> "%+.1f".format(g)
    }

    private fun freq(f: Float): String {
        if (f < 1000) return f.roundToInt().toString()
        val k = f / 1000
        return if (abs(k - k.roundToInt()) < 0.05f || k >= 10) "${k.roundToInt()}k" else "%.1fk".format(k)
    }
}
