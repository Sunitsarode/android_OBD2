package com.obd2dash.ui.view

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.obd2dash.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Analogue arc gauge with a digital readout in the middle.
 *
 * Values are eased toward their target across frames rather than snapping, so a
 * 10 Hz data feed still looks continuous on screen.
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var label: String = ""
        set(v) { field = v; invalidate() }

    var unit: String = ""
        set(v) { field = v; invalidate() }

    var minValue: Float = 0f
    var maxValue: Float = 100f

    /** Values at or above this are drawn in the warning colour. Negative disables it. */
    var redline: Float = -1f

    var decimals: Int = 0

    private var target: Float = 0f
    private var shown: Float = 0f
    private var hasValue: Boolean = false

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // Tabular figures keep changing digits from shifting sideways.
        fontFeatureSettings = "tnum"
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val oval = RectF()

    private var colorTrack = Color.parseColor("#22FFFFFF")
    private var colorAccent = Color.parseColor("#22D3EE")
    private var colorWarn = Color.parseColor("#F87171")
    private var colorText = Color.WHITE
    private var colorMuted = Color.parseColor("#99FFFFFF")

    init {
        val a: TypedArray = context.theme.obtainStyledAttributes(attrs, R.styleable.GaugeView, 0, 0)
        try {
            label = a.getString(R.styleable.GaugeView_gaugeLabel) ?: ""
            unit = a.getString(R.styleable.GaugeView_gaugeUnit) ?: ""
            minValue = a.getFloat(R.styleable.GaugeView_gaugeMin, 0f)
            maxValue = a.getFloat(R.styleable.GaugeView_gaugeMax, 100f)
            redline = a.getFloat(R.styleable.GaugeView_gaugeRedline, -1f)
            decimals = a.getInt(R.styleable.GaugeView_gaugeDecimals, 0)
        } finally {
            a.recycle()
        }
        colorAccent = themeColor(R.attr.gaugeAccent, colorAccent)
        colorWarn = themeColor(R.attr.gaugeWarn, colorWarn)
        colorTrack = themeColor(R.attr.gaugeTrack, colorTrack)
        colorText = ContextCompat.getColor(context, R.color.text_primary)
        colorMuted = ContextCompat.getColor(context, R.color.text_secondary)
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val typed = TypedValue()
        if (!context.theme.resolveAttribute(attr, typed, true)) return fallback
        return if (typed.resourceId != 0) ContextCompat.getColor(context, typed.resourceId)
        else typed.data
    }

    /** Sets the needle target. Passing null blanks the readout. */
    fun setValue(value: Float?) {
        if (value == null) {
            hasValue = false
            target = minValue
        } else {
            hasValue = true
            target = value.coerceIn(minValue, maxValue)
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        val stroke = size * 0.075f
        val radius = size / 2f - stroke
        val cx = width / 2f
        val cy = height / 2f

        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        arcPaint.strokeWidth = stroke

        arcPaint.color = colorTrack
        canvas.drawArc(oval, START_ANGLE, SWEEP, false, arcPaint)

        // A faint red band marks the redline zone even when the needle is below it.
        if (redline > minValue && redline < maxValue) {
            val start = (redline - minValue) / (maxValue - minValue)
            arcPaint.color = colorWarn
            arcPaint.alpha = REDLINE_ZONE_ALPHA
            canvas.drawArc(oval, START_ANGLE + SWEEP * start, SWEEP * (1f - start), false, arcPaint)
        }

        val fraction =
            if (maxValue > minValue) ((shown - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
            else 0f
        val overRedline = redline >= 0f && shown >= redline
        val active = if (overRedline) colorWarn else colorAccent

        if (hasValue && fraction > 0f) {
            arcPaint.color = active
            canvas.drawArc(oval, START_ANGLE, SWEEP * fraction, false, arcPaint)
        }

        drawTicks(canvas, cx, cy, radius, stroke)
        drawNeedle(canvas, cx, cy, radius - stroke * 1.4f, fraction, active)
        drawReadout(canvas, cx, cy, size, active)

        advanceAnimation()
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float, stroke: Float) {
        tickPaint.color = colorMuted
        tickPaint.strokeWidth = stroke * 0.12f
        textPaint.color = colorMuted
        textPaint.textSize = radius * 0.13f
        textPaint.typeface = Typeface.DEFAULT

        for (i in 0..TICK_COUNT) {
            val t = i / TICK_COUNT.toFloat()
            val angle = Math.toRadians((START_ANGLE + SWEEP * t).toDouble())
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            val outerR = radius - stroke
            val innerR = outerR - radius * 0.06f
            canvas.drawLine(
                cx + cosA * innerR, cy + sinA * innerR,
                cx + cosA * outerR, cy + sinA * outerR,
                tickPaint
            )
            val labelR = innerR - radius * 0.12f
            val value = minValue + (maxValue - minValue) * t
            canvas.drawText(
                formatTick(value),
                cx + cosA * labelR,
                cy + sinA * labelR + textPaint.textSize / 3f,
                textPaint
            )
        }
    }

    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, length: Float, fraction: Float, color: Int) {
        if (!hasValue) return
        val angle = Math.toRadians((START_ANGLE + SWEEP * fraction).toDouble())
        needlePaint.color = color
        needlePaint.style = Paint.Style.STROKE
        needlePaint.strokeCap = Paint.Cap.ROUND
        needlePaint.strokeWidth = length * 0.035f
        canvas.drawLine(
            cx, cy,
            cx + cos(angle).toFloat() * length,
            cy + sin(angle).toFloat() * length,
            needlePaint
        )
        needlePaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, length * 0.07f, needlePaint)
    }

    private fun drawReadout(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.color = if (hasValue) colorText else colorMuted
        textPaint.textSize = size * 0.20f
        // The needle eases toward the target, but the digits show the real value at once.
        canvas.drawText(if (hasValue) formatValue(target) else "--", cx, cy + size * 0.30f, textPaint)

        textPaint.typeface = Typeface.DEFAULT
        textPaint.color = colorMuted
        textPaint.textSize = size * 0.075f
        canvas.drawText(unit, cx, cy + size * 0.39f, textPaint)

        textPaint.color = if (hasValue) color else colorMuted
        textPaint.textSize = size * 0.08f
        canvas.drawText(label, cx, cy - size * 0.17f, textPaint)
    }

    /** Eases the displayed value toward the target and requests another frame if needed. */
    private fun advanceAnimation() {
        val diff = target - shown
        if (abs(diff) < (maxValue - minValue) * 0.001f) {
            shown = target
            return
        }
        shown += diff * SMOOTHING
        postInvalidateOnAnimation()
    }

    private fun formatValue(v: Float) = "%.${decimals}f".format(v)

    private fun formatTick(v: Float): String =
        if (maxValue - minValue >= 2000f) "%.0f".format(v / 1000f) else "%.0f".format(v)

    private companion object {
        const val START_ANGLE = 135f
        const val SWEEP = 270f
        const val TICK_COUNT = 8
        const val SMOOTHING = 0.22f
        const val REDLINE_ZONE_ALPHA = 70
    }
}
