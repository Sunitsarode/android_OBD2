package com.obd2dash.auto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.content.ContextCompat
import com.obd2dash.R
import com.obd2dash.core.AlertMonitor
import com.obd2dash.core.Metrics
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Settings
import com.obd2dash.core.TripStats
import com.obd2dash.obd.Pids
import com.obd2dash.ui.DashMetrics
import com.obd2dash.ui.view.GaugeView
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the driving dashboard onto the Android Auto surface: RPM and speed
 * dials, the gear, and a row of tiles. The dials are the phone app's
 * [GaugeView], measured and drawn off-screen, so both screens look the same.
 */
class CarDashRenderer(context: Context) {

    /** Everything one frame needs, read once so a frame never mixes two moments. */
    class Frame(
        val live: ObdRepository.Live,
        val trip: TripStats,
        val alerts: Set<AlertMonitor.Alert>,
        val state: ObdRepository.ConnState,
        val status: String,
        val settings: Settings,
        val tiles: List<DashMetrics.Metric>
    )

    private val rpmGauge = GaugeView(context).apply {
        label = "RPM"
        unit = "rpm"
        maxValue = 8000f
    }
    private val speedGauge = GaugeView(context).apply {
        label = "SPEED"
        unit = "km/h"
        maxValue = 200f
    }

    private val colorBg = ContextCompat.getColor(context, R.color.bg)
    private val colorTile = ContextCompat.getColor(context, R.color.surface)
    private val colorPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val colorMuted = ContextCompat.getColor(context, R.color.text_secondary)
    private val colorAmber = ContextCompat.getColor(context, R.color.amber)
    private val colorDanger = ContextCompat.getColor(context, R.color.danger)
    private val colorAccent = ContextCompat.getColor(context, R.color.accent)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()

    fun draw(canvas: Canvas, area: Rect, dpi: Int, f: Frame) {
        canvas.drawColor(colorBg)
        val dp = max(dpi, 120) / 160f
        val pad = 8f * dp
        val left = area.left + pad
        val right = area.right - pad
        val bottom = area.bottom - pad
        var top = area.top + pad
        if (right - left < 120f * dp || bottom - top < 90f * dp) return

        if (f.alerts.isNotEmpty()) top = drawBanner(canvas, left, top, right, bottom, dp, f.alerts)

        if (f.state != ObdRepository.ConnState.CONNECTED) {
            drawStatus(canvas, left, top, right, bottom, dp, f)
            return
        }
        val tilesHeight = max(56f * dp, (bottom - top) * TILE_SHARE)
        val gaugesBottom = bottom - tilesHeight - pad
        drawGauges(canvas, left, top, right, gaugesBottom, dp, f)
        drawTiles(canvas, left, gaugesBottom + pad, right, bottom, dp, f)
    }

    private fun drawGauges(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, dp: Float, f: Frame) {
        val s = f.settings
        val width = right - left
        val height = bottom - top
        val centre = max(width * 0.2f, 80f * dp)
        val side = (width - centre) / 2f
        val size = min(side, height).toInt()
        if (size <= 0) return
        val gaugeTop = top + (height - size) / 2f

        rpmGauge.redline = if (s.redlineRpm > 0) s.redlineRpm.toFloat() else -1f
        rpmGauge.maxValue = if (s.redlineRpm > 7000) 10000f else 8000f
        rpmGauge.setValue(f.live.readings[Pids.RPM])
        drawView(canvas, rpmGauge, left + (side - size) / 2f, gaugeTop, size)

        val speed = f.live.readings[Pids.SPEED]
        speedGauge.unit = if (s.imperial) "mph" else "km/h"
        speedGauge.maxValue = if (s.imperial) 140f else 200f
        speedGauge.setValue(if (speed == null) null else if (s.imperial) Metrics.kmhToMph(speed) else speed)
        drawView(canvas, speedGauge, right - side + (side - size) / 2f, gaugeTop, size)

        val cx = left + side + centre / 2f
        val gear = f.live.gear
        text.typeface = Typeface.DEFAULT
        text.color = colorMuted
        text.textSize = min(14f * dp, height * 0.08f)
        canvas.drawText("GEAR", cx, top + height * 0.24f, text)

        text.typeface = Typeface.DEFAULT_BOLD
        text.color = when (gear.gear) {
            null -> colorMuted
            0 -> colorAmber
            else -> colorPrimary
        }
        fit(gear.label, centre * 0.9f, height * 0.42f)
        canvas.drawText(gear.label, cx, top + height * 0.64f, text)

        if (gear.shiftUp) {
            text.color = colorAmber
            fit("SHIFT UP", centre * 0.95f, min(16f * dp, height * 0.09f))
            canvas.drawText("SHIFT UP", cx, top + height * 0.80f, text)
        }
    }

    private fun drawTiles(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, dp: Float, f: Frame) {
        if (f.tiles.isEmpty() || bottom <= top) return
        val gap = 6f * dp
        val count = f.tiles.size
        val tileWidth = (right - left - gap * (count - 1)) / count
        val height = bottom - top
        val inputs = DashMetrics.Inputs(f.live, f.trip, f.settings)

        f.tiles.forEachIndexed { index, metric ->
            val x = left + index * (tileWidth + gap)
            box.set(x, top, x + tileWidth, bottom)
            fill.color = colorTile
            canvas.drawRoundRect(box, 10f * dp, 10f * dp, fill)

            val (value, unit) = metric.render(inputs)
            text.typeface = Typeface.DEFAULT
            text.color = colorMuted
            fit(metric.label, tileWidth - 8f * dp, min(13f * dp, height * 0.2f))
            canvas.drawText(metric.label, box.centerX(), top + height * 0.32f, text)

            val shown = if (value == null) "--" else if (unit.isEmpty()) value else value + " " + unit
            text.typeface = Typeface.DEFAULT_BOLD
            text.color = when {
                value == null -> colorMuted
                metric.warn(inputs) -> colorDanger
                else -> colorPrimary
            }
            fit(shown, tileWidth - 10f * dp, height * 0.4f)
            canvas.drawText(shown, box.centerX(), top + height * 0.78f, text)
        }
    }

    /** Returns the new top edge below the banner. */
    private fun drawBanner(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, dp: Float,
        alerts: Set<AlertMonitor.Alert>
    ): Float {
        val height = max(30f * dp, (bottom - top) * 0.1f)
        box.set(left, top, right, top + height)
        fill.color = colorDanger
        canvas.drawRoundRect(box, 8f * dp, 8f * dp, fill)
        val message = alerts.joinToString("   |   ") { it.title.uppercase() }
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = android.graphics.Color.WHITE
        fit(message, right - left - 16f * dp, height * 0.55f)
        canvas.drawText(message, box.centerX(), box.centerY() + text.textSize / 3f, text)
        return top + height + 8f * dp
    }

    /** Shown instead of the dials until data is flowing. */
    private fun drawStatus(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, dp: Float, f: Frame) {
        val (title, detail) = when (f.state) {
            ObdRepository.ConnState.DISCONNECTED ->
                "Not connected" to "Tap Connect above, or open OBD2 Dashboard on your phone"
            ObdRepository.ConnState.CONNECTING, ObdRepository.ConnState.INITIALIZING ->
                "Connecting..." to f.status
            ObdRepository.ConnState.WAITING_FOR_ECU ->
                "Ignition off" to (f.live.batteryVolts?.let { "Battery %.1f V".format(it) } ?: "Start the car to see live data")
            ObdRepository.ConnState.ERROR ->
                "Reconnecting" to f.status
            ObdRepository.ConnState.CONNECTED -> "" to ""
        }
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val width = right - left - 24f * dp

        text.typeface = Typeface.DEFAULT_BOLD
        text.color = if (f.state == ObdRepository.ConnState.ERROR) colorAmber else colorAccent
        fit(title, width, min(40f * dp, (bottom - top) * 0.2f))
        canvas.drawText(title, cx, cy, text)

        text.typeface = Typeface.DEFAULT
        text.color = colorMuted
        fit(detail, width, 16f * dp)
        canvas.drawText(detail, cx, cy + 32f * dp, text)
    }

    /** Lays out a detached view at [size] and draws it at the given position. */
    private fun drawView(canvas: Canvas, view: View, x: Float, y: Float, size: Int) {
        if (view.width != size || view.height != size) {
            val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            view.measure(spec, spec)
            view.layout(0, 0, size, size)
        }
        canvas.save()
        canvas.translate(x, y)
        view.draw(canvas)
        canvas.restore()
    }

    /** Sets the text size to [maxSize], shrinking it if the text would overflow [maxWidth]. */
    private fun fit(value: String, maxWidth: Float, maxSize: Float) {
        text.textSize = maxSize
        val measured = text.measureText(value)
        if (measured > maxWidth && measured > 0f) text.textSize = maxSize * maxWidth / measured
    }

    private companion object {
        /** Fraction of the height given to the tile row. */
        const val TILE_SHARE = 0.26f
    }
}
