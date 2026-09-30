package com.obd2dash.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.obd2dash.R
import com.obd2dash.core.PidHistory

/**
 * Rolling line graph of one sensor over a fixed time window. The Y axis
 * rescales to the visible data, so small changes stay readable.
 */
class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var title = ""
    private var unit = ""
    private var times = LongArray(0)
    private var values = FloatArray(0)
    private var from = 0L
    private var to = 1L

    private val density = resources.displayMetrics.density
    private val path = Path()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val gridPaint = Paint().apply {
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.outline)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        typeface = Typeface.DEFAULT_BOLD
        color = ContextCompat.getColor(context, R.color.text_primary)
    }

    fun setData(title: String, unit: String, series: PidHistory.Series?, from: Long, to: Long) {
        this.title = title
        this.unit = unit
        this.times = series?.times ?: LongArray(0)
        this.values = series?.values ?: FloatArray(0)
        this.from = from
        this.to = maxOf(to, from + 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 8f * density
        val right = width - 8f * density
        val top = 26f * density
        val bottom = height - 16f * density

        canvas.drawText(title, left, 16f * density, titlePaint)
        val current = values.lastOrNull()
        if (current != null) {
            val text = "%.1f %s".format(current, unit)
            canvas.drawText(text, right - titlePaint.measureText(text), 16f * density, titlePaint)
        }
        if (values.size < 2 || bottom <= top) {
            canvas.drawText("Collecting data... (tap to close)", left, (top + bottom) / 2f, labelPaint)
            return
        }

        var lo = values.minOrNull() ?: 0f
        var hi = values.maxOrNull() ?: 1f
        if (hi - lo < 0.001f) {
            lo -= 1f
            hi += 1f
        }
        val pad = (hi - lo) * 0.08f
        lo -= pad
        hi += pad

        for (k in 0..3) {
            val y = top + (bottom - top) * k / 3f
            canvas.drawLine(left, y, right, y, gridPaint)
            val label = "%.1f".format(hi - (hi - lo) * k / 3f)
            canvas.drawText(label, left + 2f * density, y - 2f * density, labelPaint)
        }

        path.reset()
        val span = (to - from).toFloat()
        for (i in values.indices) {
            val x = left + (right - left) * ((times[i] - from) / span)
            val y = bottom - (bottom - top) * ((values[i] - lo) / (hi - lo))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        val seconds = (to - from) / 1000
        canvas.drawText("-" + seconds + " s", left, height - 3f * density, labelPaint)
        val nowText = "now (tap to close)"
        canvas.drawText(nowText, right - labelPaint.measureText(nowText), height - 3f * density, labelPaint)
    }
}
