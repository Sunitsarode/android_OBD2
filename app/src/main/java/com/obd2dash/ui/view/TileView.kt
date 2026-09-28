package com.obd2dash.ui.view

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.obd2dash.R

/**
 * Compact readout tile: a large value, a unit, and a caption.
 *
 * Built in code rather than XML so the dashboard can lay out any number of
 * tiles and reflow them by column count.
 */
class TileView(context: Context) : LinearLayout(context) {

    private val valueView = TextView(context)
    private val labelView = TextView(context)

    private val normalColor = ContextCompat.getColor(context, R.color.text_primary)
    private val warnColor = ContextCompat.getColor(context, R.color.danger)
    private val mutedColor = ContextCompat.getColor(context, R.color.text_secondary)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_tile)
        val pad = dp(8)
        setPadding(pad, dp(10), pad, dp(10))

        valueView.setTextColor(normalColor)
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        valueView.typeface = android.graphics.Typeface.DEFAULT_BOLD
        valueView.maxLines = 1
        valueView.gravity = Gravity.CENTER
        addView(valueView)

        labelView.setTextColor(mutedColor)
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        labelView.maxLines = 1
        labelView.gravity = Gravity.CENTER
        addView(labelView)
    }

    fun setLabel(text: String) {
        labelView.text = text
    }

    /** Shows a value, or a dash placeholder when the ECU has nothing for this PID. */
    fun setValue(text: String?, unit: String = "", warn: Boolean = false) {
        valueView.text = if (text == null) "--" else if (unit.isEmpty()) text else text + " " + unit
        valueView.setTextColor(
            when {
                text == null -> mutedColor
                warn -> warnColor
                else -> normalColor
            }
        )
    }

    fun setAccent(color: Int) {
        valueView.setTextColor(if (color == Color.TRANSPARENT) normalColor else color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
