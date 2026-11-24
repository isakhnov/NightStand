package com.nightstand.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.format.DateFormat
import com.nightstand.AnalogClock
import com.nightstand.BurnIn
import com.nightstand.Fonts
import com.nightstand.Prefs
import java.util.Calendar
import java.util.Locale

/** Clock only, as large as the screen allows — iOS StandBy's full clock page. */
class BigClockPage(context: Context, data: DisplayData) : Page(context, data) {
    companion object { const val ID = "big_clock" }
    override val pageId = ID
    override val needsSeconds get() = Prefs.clockStyle(context) == Prefs.ClockStyle.ANALOG

    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.heavy; textAlign = Paint.Align.CENTER }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold }

    override fun onDraw(c: Canvas) {
        val p = palette
        c.drawColor(p.background)
        val now = Calendar.getInstance()
        val (sx, sy) = BurnIn.shiftFor(now.get(Calendar.MINUTE), dp(8f))
        c.save(); c.translate(sx, sy)
        val w = width.toFloat(); val h = height.toFloat()
        val date = DateFormat.format(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), now
        ).toString().uppercase()

        if (Prefs.clockStyle(context) == Prefs.ClockStyle.ANALOG) {
            val r = h / 2 - dp(18f)
            AnalogClock.draw(c, w / 2, h / 2, r, now, p, showSeconds = true)
            small.textSize = dp(15f); small.color = p.accent
            small.textAlign = Paint.Align.LEFT
            c.drawText(date, dp(24f), h - dp(24f), small)
        } else {
            val is24 = DateFormat.is24HourFormat(context)
            val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", now).toString()
            big.color = p.primary
            big.textSize = 100f
            big.textSize = minOf(100f * (w * 0.88f) / big.measureText("00:00"), h * 0.78f)
            val fm = big.fontMetrics
            c.drawText(time, w / 2, h / 2 - (fm.ascent + fm.descent) / 2 - dp(10f), big)
            small.textSize = dp(18f); small.color = p.accent; small.textAlign = Paint.Align.CENTER
            c.drawText(date, w / 2, h - dp(20f), small)
        }
        c.restore()
    }
}
