package com.nightstand.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.format.DateFormat
import com.nightstand.AnalogClock
import com.nightstand.BurnIn
import com.nightstand.Fonts
import com.nightstand.Prefs
import java.util.Calendar
import java.util.Locale

/**
 * The iOS StandBy two-widget layout: clock on the left, something else on the
 * right. Subclasses draw only the right side ([drawRight]); the clock follows
 * the analog/digital setting. Burn-in shift applies to both halves.
 */
abstract class ClockSplitPage(context: Context, data: DisplayData) : Page(context, data) {
    override val needsSeconds get() = Prefs.clockStyle(context) == Prefs.ClockStyle.ANALOG

    /** Most of the width the clock may take; it is normally height-limited. */
    protected open val maxClockFraction = 0.46f

    private val digit = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.heavy; textAlign = Paint.Align.CENTER }
    private val date = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; textAlign = Paint.Align.CENTER }

    /** Right-hand area in view coordinates (before burn-in shift), for hit testing. */
    protected val rightRect = RectF()
    protected var shiftX = 0f; protected var shiftY = 0f

    override fun onDraw(c: Canvas) {
        val p = palette
        c.drawColor(p.background)
        val now = Calendar.getInstance()
        val (sx, sy) = BurnIn.shiftFor(now.get(Calendar.MINUTE), dp(8f))
        shiftX = sx; shiftY = sy
        c.save(); c.translate(sx, sy)
        val w = width.toFloat(); val h = height.toFloat()
        val pad = dp(14f)
        val gap = dp(22f)
        // Clock hugs the left edge and the right pane starts right after it
        // (user feedback: half the empty space, bigger right pane). The clock
        // is sized by the screen height, capped at [maxClockFraction] of width.
        val clockRight: Float

        if (Prefs.clockStyle(context) == Prefs.ClockStyle.ANALOG) {
            val r = minOf(h / 2 - pad, w * maxClockFraction / 2 - pad)
            AnalogClock.draw(c, pad + r, h / 2, r, now, p, showSeconds = true)
            clockRight = pad + 2 * r
        } else {
            val is24 = DateFormat.is24HourFormat(context)
            val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", now).toString()
            digit.color = p.primary
            digit.textAlign = Paint.Align.LEFT
            digit.textSize = 100f
            // Size for the widest time so the pane doesn't jump as digits change.
            digit.textSize = minOf(100f * (w * maxClockFraction - pad) / digit.measureText("00:00"), h * 0.42f)
            val fm = digit.fontMetrics
            val base = h / 2 - (fm.ascent + fm.descent) / 2 - dp(8f)
            c.drawText(time, pad, base, digit)
            date.color = p.accent; date.textSize = digit.textSize * 0.17f
            date.textAlign = Paint.Align.LEFT
            c.drawText(
                DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), now).toString(),
                pad + dp(4f), base + fm.descent + date.textSize * 1.2f, date
            )
            clockRight = pad + digit.measureText(if (is24) "00:00" else "00:00".substring(1))
        }
        rightRect.set(clockRight + gap, pad, w - pad, h - pad)
        drawRight(c, rightRect, now)
        c.restore()
    }

    protected abstract fun drawRight(c: Canvas, r: RectF, now: Calendar)
}
