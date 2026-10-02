package com.obd2dash.auto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.obd2dash.R
import com.obd2dash.core.AlertMonitor
import com.obd2dash.core.Metrics
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.ObdRepository.ConnState
import com.obd2dash.core.Settings
import com.obd2dash.core.TripStats
import com.obd2dash.obd.Pids
import com.obd2dash.ui.DashMetrics
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the driving dashboard on the Android Auto map surface as a digital
 * cluster: road speed inside an RPM ring, the gear beneath it, and up to four
 * info cards.
 *
 * The layout follows the space Android Auto grants: side by side on wide
 * screens, stacked on narrow ones, and cluster-only in a small split-screen
 * card. Sizes derive from that space rather than fixed pixels, so the same
 * code reads well on a 7-inch screen and a 12-inch one.
 */
class CarDashRenderer(context: Context) {

    /** Everything one frame needs, read once so a frame never mixes two moments. */
    class Frame(
        val live: ObdRepository.Live,
        val trip: TripStats,
        val alerts: Set<AlertMonitor.Alert>,
        val state: ConnState,
        val status: String,
        val settings: Settings,
        val tiles: List<DashMetrics.Metric>,
        /** Connected, but no fresh data for a few seconds. */
        val stale: Boolean
    ) {
        /** Whether anything on screen would differ. Published values are immutable, so identity suffices. */
        fun differsFrom(other: Frame?): Boolean = other == null ||
                live !== other.live || trip !== other.trip || alerts != other.alerts ||
                state != other.state || status != other.status || settings != other.settings ||
                tiles !== other.tiles || stale != other.stale
    }

    private val colorBg = ContextCompat.getColor(context, R.color.bg)
    private val colorCard = ContextCompat.getColor(context, R.color.surface)
    private val colorOutline = ContextCompat.getColor(context, R.color.outline)
    private val colorTrack = ContextCompat.getColor(context, R.color.track)
    private val colorPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val colorMuted = ContextCompat.getColor(context, R.color.text_secondary)
    private val colorAccent = ContextCompat.getColor(context, R.color.accent)
    private val colorAmber = ContextCompat.getColor(context, R.color.amber)
    private val colorDanger = ContextCompat.getColor(context, R.color.danger)
    private val colorGreen = ContextCompat.getColor(context, R.color.ok)
    private val colorInkOnAmber = Color.parseColor("#1A1300")

    private val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Numbers use tabular figures, so a changing value never shifts sideways. */
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = condensedBold
        fontFeatureSettings = "tnum"
    }
    private val digitsLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = condensedBold
        fontFeatureSettings = "tnum"
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = medium
    }
    private val textLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = medium
    }

    private val oval = RectF()
    private val rect = RectF()
    private val bar = RectF()
    private val cardBox = RectF()

    /** True while the RPM ring is still easing or a spinner is turning; the caller keeps drawing. */
    var animating = false
        private set

    private var shownRpm = 0f
    private var lastDrawAt = 0L
    private var spinnerDeg = 0f

    /** Pixels per dp on the current surface. */
    private var dp = 1f

    fun draw(canvas: Canvas, area: Rect, dpi: Int, f: Frame) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastDrawAt == 0L) 16L else (now - lastDrawAt).coerceIn(1L, 250L)
        lastDrawAt = now
        dp = max(dpi, 120) / 160f
        animating = false
        canvas.drawColor(colorBg)

        val gap = 10f * dp
        val bounds = RectF(area).apply { inset(gap, gap) }
        if (bounds.width() < 120f * dp || bounds.height() < 90f * dp) return

        var top = bounds.top
        if (f.alerts.isNotEmpty()) {
            top = drawBanner(canvas, bounds.left, top, bounds.right, bounds.height(), f.alerts) + gap
        }
        val body = RectF(bounds.left, top, bounds.right, bounds.bottom)

        if (f.state != ConnState.CONNECTED) {
            drawStatus(canvas, body, f, dt)
            return
        }

        // Exponential easing keeps the ring smooth between the 4-6 samples a second
        // the adapter delivers, without trailing noticeably behind them.
        val targetRpm = f.live.readings[Pids.RPM] ?: 0f
        shownRpm += (targetRpm - shownRpm) * (1f - exp(-dt / EASE_MS))
        if (abs(targetRpm - shownRpm) > 4f) animating = true else shownRpm = targetRpm

        val compact = body.width() < COMPACT_WIDTH_DP * dp || body.height() < COMPACT_HEIGHT_DP * dp
        val cards = if (compact) 0 else min(MAX_CARDS, f.tiles.size)
        when {
            cards == 0 -> drawCluster(canvas, body, f)
            body.width() >= body.height() * WIDE_ASPECT -> {
                val clusterWidth = min(body.height() * 1.12f, body.width() * 0.52f)
                drawCluster(canvas, RectF(body.left, body.top, body.left + clusterWidth, body.bottom), f)
                drawCards(canvas, RectF(body.left + clusterWidth + gap, body.top, body.right, body.bottom), f, cards, gap, false)
            }
            else -> {
                val cardsHeight = max(72f * dp, body.height() * 0.28f)
                drawCluster(canvas, RectF(body.left, body.top, body.right, body.bottom - cardsHeight - gap), f)
                drawCards(canvas, RectF(body.left, body.bottom - cardsHeight, body.right, body.bottom), f, cards, gap, true)
            }
        }
    }

    /** Speed inside an RPM ring, with gear, fuel, and shift or data hints along the bottom. */
    private fun drawCluster(canvas: Canvas, box: RectF, f: Frame) {
        val s = f.settings
        val radius = min(box.width(), box.height()) / 2f * 0.96f
        if (radius < 40f * dp) return
        val cx = box.centerX()
        val cy = box.centerY() - radius * 0.02f
        val ringWidth = radius * 0.085f
        val ringR = radius - ringWidth / 2f
        oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        val maxRpm = if (s.redlineRpm > 7000) 10000f else 8000f

        // Track, then the redline zone, then the live value on top.
        arc.strokeWidth = ringWidth
        arc.color = colorTrack
        canvas.drawArc(oval, START_DEG, SWEEP_DEG, false, arc)
        if (s.redlineRpm > 0 && s.redlineRpm < maxRpm) {
            val from = s.redlineRpm / maxRpm
            arc.color = colorDanger
            arc.alpha = REDLINE_ZONE_ALPHA
            canvas.drawArc(oval, START_DEG + SWEEP_DEG * from, SWEEP_DEG * (1f - from), false, arc)
        }
        val fraction = (shownRpm / maxRpm).coerceIn(0f, 1f)
        if (fraction > 0.003f) {
            arc.color = when {
                f.stale -> colorMuted
                s.redlineRpm > 0 && shownRpm >= s.redlineRpm -> colorDanger
                f.live.gear.shiftUp -> colorAmber
                else -> colorAccent
            }
            canvas.drawArc(oval, START_DEG, SWEEP_DEG * fraction, false, arc)
        }
        drawTicks(canvas, cx, cy, ringR - ringWidth, radius, maxRpm)

        val speed = f.live.readings[Pids.SPEED]
        val speedText = if (speed == null) "--" else "%.0f".format(if (s.imperial) Metrics.kmhToMph(speed) else speed)
        digits.color = if (speed == null || f.stale) colorMuted else colorPrimary
        fit(digits, speedText, radius * 1.05f, radius * 0.56f)
        canvas.drawText(speedText, cx, cy + digits.textSize * 0.30f, digits)

        text.color = colorMuted
        text.textSize = radius * 0.105f
        canvas.drawText(if (s.imperial) "mph" else "km/h", cx, cy + radius * 0.36f, text)

        drawBottomRow(canvas, cx, cy + radius * 0.80f, radius, f)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, innerR: Float, radius: Float, maxRpm: Float) {
        val steps = (maxRpm / 1000f).toInt()
        stroke.color = colorMuted
        stroke.strokeWidth = max(1.5f * dp, radius * 0.012f)
        text.color = colorMuted
        text.textSize = radius * 0.085f
        val tickInner = innerR - radius * 0.05f
        val labelR = innerR - radius * 0.14f
        for (k in 0..steps) {
            val rad = Math.toRadians((START_DEG + SWEEP_DEG * k / steps).toDouble())
            val c = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            canvas.drawLine(cx + c * tickInner, cy + sn * tickInner, cx + c * innerR, cy + sn * innerR, stroke)
            canvas.drawText(k.toString(), cx + c * labelR, cy + sn * labelR + text.textSize * 0.35f, text)
        }
    }

    /** Gear chip in the ring's open bottom, with the fuel in use to its left and hints to its right. */
    private fun drawBottomRow(canvas: Canvas, cx: Float, rowY: Float, radius: Float, f: Frame) {
        val s = f.settings
        val gear = f.live.gear
        val chipH = radius * 0.34f
        val chipW = radius * 0.40f
        val corner = chipH * 0.25f
        rect.set(cx - chipW / 2f, rowY - chipH / 2f, cx + chipW / 2f, rowY + chipH / 2f)
        fill.color = colorCard
        canvas.drawRoundRect(rect, corner, corner, fill)
        stroke.color = if (gear.shiftUp) colorAmber else colorOutline
        stroke.strokeWidth = max(1.5f * dp, radius * 0.012f)
        canvas.drawRoundRect(rect, corner, corner, stroke)
        digits.color = when (gear.gear) {
            null -> colorMuted
            0 -> colorAmber
            else -> colorPrimary
        }
        fit(digits, gear.label, chipW * 0.8f, chipH * 0.78f)
        canvas.drawText(gear.label, cx, rowY + digits.textSize * 0.36f, digits)

        if (s.fuelSystem.isBiFuel) {
            val fuel = f.live.derived.fuel
            val label = fuel.label.uppercase()
            val tint = if (fuel == s.fuelSystem.primary) colorAmber else colorGreen
            text.color = tint
            text.textSize = radius * 0.10f
            val pillH = radius * 0.19f
            val pillW = text.measureText(label) + radius * 0.12f
            val pillCx = cx - radius * 0.52f
            rect.set(pillCx - pillW / 2f, rowY - pillH / 2f, pillCx + pillW / 2f, rowY + pillH / 2f)
            stroke.color = tint
            canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, stroke)
            canvas.drawText(label, pillCx, rowY + text.textSize * 0.36f, text)
        }

        val sideX = cx + radius * 0.50f
        if (f.stale) {
            text.color = colorAmber
            fit(text, "NO DATA", radius * 0.34f, radius * 0.09f)
            canvas.drawText("NO DATA", sideX, rowY + text.textSize * 0.36f, text)
        } else if (gear.shiftUp) {
            digits.color = colorAmber
            digits.textSize = chipH * 0.72f
            canvas.drawText("↑", cx + radius * 0.40f, rowY + digits.textSize * 0.36f, digits)
        }
    }

    private fun drawCards(canvas: Canvas, box: RectF, f: Frame, count: Int, gap: Float, singleRow: Boolean) {
        val inputs = DashMetrics.Inputs(f.live, f.trip, f.settings)
        val cols = when {
            singleRow -> count
            box.width() >= 2f * MIN_CARD_WIDTH_DP * dp + gap -> 2
            else -> 1
        }
        val rows = (count + cols - 1) / cols
        val cardW = (box.width() - gap * (cols - 1)) / cols
        val cardH = min((box.height() - gap * (rows - 1)) / rows, MAX_CARD_HEIGHT_DP * dp)
        val gridH = cardH * rows + gap * (rows - 1)
        val firstTop = box.top + (box.height() - gridH) / 2f
        for (i in 0 until count) {
            val left = box.left + (i % cols) * (cardW + gap)
            val top = firstTop + (i / cols) * (cardH + gap)
            cardBox.set(left, top, left + cardW, top + cardH)
            drawCard(canvas, cardBox, f.tiles[i], inputs, f.stale)
        }
    }

    /** Label at the top, value and unit sharing a baseline below; a red edge marks a warning. */
    private fun drawCard(canvas: Canvas, box: RectF, metric: DashMetrics.Metric, inputs: DashMetrics.Inputs, stale: Boolean) {
        val corner = min(14f * dp, box.height() * 0.18f)
        fill.color = colorCard
        canvas.drawRoundRect(box, corner, corner, fill)
        stroke.color = colorOutline
        stroke.strokeWidth = max(1f, dp)
        canvas.drawRoundRect(box, corner, corner, stroke)

        val (value, unitText) = metric.render(inputs)
        val warn = value != null && metric.warn(inputs)
        if (warn) {
            fill.color = colorDanger
            bar.set(box.left, box.top + corner, box.left + 4f * dp, box.bottom - corner)
            canvas.drawRoundRect(bar, 2f * dp, 2f * dp, fill)
        }

        val padX = max(12f * dp, box.width() * 0.07f)
        val innerW = box.width() - padX * 2f
        textLeft.color = colorMuted
        textLeft.letterSpacing = 0.06f
        fit(textLeft, metric.label, innerW, min(15f * dp, box.height() * 0.17f))
        canvas.drawText(metric.label, box.left + padX, box.top + box.height() * 0.32f, textLeft)
        textLeft.letterSpacing = 0f

        val shown = value ?: "--"
        val suffix = if (value == null || unitText.isEmpty()) "" else " " + unitText
        digitsLeft.textSize = box.height() * 0.42f
        textLeft.textSize = digitsLeft.textSize * 0.42f
        val total = digitsLeft.measureText(shown) + textLeft.measureText(suffix)
        if (total > innerW && total > 0f) {
            val scale = innerW / total
            digitsLeft.textSize *= scale
            textLeft.textSize *= scale
        }
        val baseline = box.top + box.height() * 0.80f
        digitsLeft.color = when {
            value == null || stale -> colorMuted
            warn -> colorDanger
            else -> colorPrimary
        }
        canvas.drawText(shown, box.left + padX, baseline, digitsLeft)
        if (suffix.isNotEmpty()) {
            textLeft.color = colorMuted
            canvas.drawText(suffix, box.left + padX + digitsLeft.measureText(shown), baseline, textLeft)
        }
    }

    /** Returns the new top edge below the banner. Red when anything critical is active, amber otherwise. */
    private fun drawBanner(
        canvas: Canvas, left: Float, top: Float, right: Float, available: Float,
        alerts: Set<AlertMonitor.Alert>
    ): Float {
        val ordered = alerts.sortedWith(compareBy<AlertMonitor.Alert>({ !it.critical }, { it.ordinal }))
        val critical = ordered.first().critical
        val h = min(max(40f * dp, available * 0.11f), 56f * dp)
        rect.set(left, top, right, top + h)
        fill.color = if (critical) colorDanger else colorAmber
        canvas.drawRoundRect(rect, h * 0.3f, h * 0.3f, fill)

        val ink = if (critical) Color.WHITE else colorInkOnAmber
        val markR = h * 0.27f
        val markCx = left + h * 0.55f
        val cy = top + h / 2f
        fill.color = ink
        canvas.drawCircle(markCx, cy, markR, fill)
        digits.color = if (critical) colorDanger else colorAmber
        digits.textSize = markR * 1.5f
        canvas.drawText("!", markCx, cy + digits.textSize * 0.36f, digits)

        val message = ordered.joinToString("   ·   ") { it.title.uppercase() }
        val textX = markCx + markR + h * 0.3f
        textLeft.color = ink
        fit(textLeft, message, right - textX - h * 0.3f, h * 0.42f)
        canvas.drawText(message, textX, cy + textLeft.textSize * 0.36f, textLeft)
        return top + h
    }

    /** Everything other than live driving: not connected, connecting, ignition off, retrying. */
    private fun drawStatus(canvas: Canvas, box: RectF, f: Frame, dt: Long) {
        val cx = box.centerX()
        val ringR = min(box.width(), box.height()) * 0.17f
        val cy = box.top + box.height() * 0.40f
        oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        arc.strokeWidth = ringR * 0.12f
        arc.color = colorTrack
        canvas.drawArc(oval, 0f, 360f, false, arc)

        val title: String
        val detail: String
        var centre = ""
        when (f.state) {
            ConnState.CONNECTING, ConnState.INITIALIZING, ConnState.ERROR -> {
                val retrying = f.state == ConnState.ERROR
                title = if (retrying) "Reconnecting" else "Connecting"
                detail = f.status
                // A turning arc says "working on it" without words; slower while retrying.
                spinnerDeg = (spinnerDeg + dt * (if (retrying) SPIN_SLOW else SPIN_FAST)) % 360f
                arc.color = if (retrying) colorAmber else colorAccent
                canvas.drawArc(oval, spinnerDeg - 90f, 90f, false, arc)
                animating = true
            }
            ConnState.WAITING_FOR_ECU -> {
                title = "Ignition off"
                detail = "Start the engine to see live data"
                arc.color = colorAmber
                canvas.drawArc(oval, 0f, 360f, false, arc)
                // The adapter still reads the battery, which is worth seeing while parked.
                centre = f.live.batteryVolts?.let { "%.1f V".format(it) } ?: ""
            }
            else -> {
                title = "Not connected"
                detail = "Tap Connect, or open OBD2 Dashboard on your phone"
                centre = "OBD"
            }
        }

        if (centre.isNotEmpty()) {
            digits.color = if (f.state == ConnState.WAITING_FOR_ECU) colorPrimary else colorMuted
            fit(digits, centre, ringR * 1.5f, ringR * 0.55f)
            canvas.drawText(centre, cx, cy + digits.textSize * 0.36f, digits)
        }
        val width = box.width() * 0.9f
        text.color = colorPrimary
        fit(text, title, width, min(34f * dp, box.height() * 0.11f))
        val titleY = cy + ringR + text.textSize * 1.6f
        canvas.drawText(title, cx, titleY, text)
        text.color = colorMuted
        fit(text, detail, width, min(18f * dp, box.height() * 0.06f))
        canvas.drawText(detail, cx, titleY + text.textSize * 1.8f, text)
    }

    /** Sets [paint] to [maxSize], shrinking it when [value] would be wider than [maxWidth]. */
    private fun fit(paint: Paint, value: String, maxWidth: Float, maxSize: Float) {
        paint.textSize = maxSize
        val measured = paint.measureText(value)
        if (measured > maxWidth && measured > 0f) paint.textSize = maxSize * maxWidth / measured
    }

    private companion object {
        /** The ring opens at the bottom: 240 degrees starting from lower left. */
        const val START_DEG = 150f
        const val SWEEP_DEG = 240f
        const val REDLINE_ZONE_ALPHA = 90
        const val EASE_MS = 90f
        const val SPIN_FAST = 0.30f
        const val SPIN_SLOW = 0.12f

        /** Below this the app is in a small card or tiny screen: show the cluster only. */
        const val COMPACT_WIDTH_DP = 440f
        const val COMPACT_HEIGHT_DP = 210f
        const val WIDE_ASPECT = 1.3f
        const val MAX_CARDS = 4
        const val MIN_CARD_WIDTH_DP = 150f
        const val MAX_CARD_HEIGHT_DP = 150f
    }
}
