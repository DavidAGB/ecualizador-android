package com.davidagb.ecualizador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Pila horizontal pequeña. level = -1 la dibuja vacía. */
class BatteryView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var level = -1
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted); style = Paint.Style.STROKE; strokeWidth = 1.5f * dp
    }
    private val nub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.muted) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.getColor(R.color.accent)
    private val danger = context.getColor(R.color.danger)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bodyW = w - 3 * dp
        val s = outline.strokeWidth / 2
        r.set(s, s, bodyW - s, h - s)
        canvas.drawRoundRect(r, 3 * dp, 3 * dp, outline)
        r.set(bodyW, h * 0.3f, w, h * 0.7f)
        canvas.drawRoundRect(r, dp, dp, nub)
        if (level < 0) return
        val inset = 3 * dp
        fill.color = if (level <= 20) danger else accent
        r.set(inset, inset, inset + (bodyW - 2 * inset) * level / 100f, h - inset)
        canvas.drawRoundRect(r, 1.5f * dp, 1.5f * dp, fill)
    }
}
