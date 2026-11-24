package com.nightstand

import android.graphics.Canvas
import android.graphics.Paint
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/**
 * iOS StandBy-style analog face: bold numerals 1–12, 60 ticks (hour ticks
 * longer), thick hour/minute hands with a thin stalk at the centre, thin
 * accent-coloured second hand with a ring at the pivot. No dial outline —
 * on OLED black the ticks define the circle, as on the iPhone.
 */
object AnalogClock {
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.semibold; textAlign = Paint.Align.CENTER
    }

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, t: Calendar, p: Palette, showSeconds: Boolean) {
        // Ticks
        for (i in 0 until 60) {
            val hour = i % 5 == 0
            val a = Math.toRadians(i * 6.0)
            val outer = r
            val inner = r - if (hour) r * 0.075f else r * 0.04f
            tick.color = if (hour) p.primary else p.secondary
            tick.strokeWidth = if (hour) r * 0.018f else r * 0.011f
            c.drawLine(
                cx + (sin(a) * inner).toFloat(), cy - (cos(a) * inner).toFloat(),
                cx + (sin(a) * outer).toFloat(), cy - (cos(a) * outer).toFloat(), tick
            )
        }
        // Numerals
        num.color = p.primary
        num.textSize = r * 0.2f
        val nr = r * 0.76f
        val fm = num.fontMetrics
        val baseline = -(fm.ascent + fm.descent) / 2
        for (h in 1..12) {
            val a = Math.toRadians(h * 30.0)
            c.drawText(h.toString(), cx + (sin(a) * nr).toFloat(), cy - (cos(a) * nr).toFloat() + baseline, num)
        }

        val sec = t.get(Calendar.SECOND)
        val min = t.get(Calendar.MINUTE) + sec / 60f
        val hr = t.get(Calendar.HOUR) + min / 60f

        // Hour + minute hands: thin stalk from the centre, then the thick blade.
        hand.color = p.primary
        drawHand(c, cx, cy, hr * 30f, r * 0.50f, r * 0.055f, r)
        drawHand(c, cx, cy, min * 6f, r * 0.86f, r * 0.055f, r)
        hand.style = Paint.Style.FILL
        c.drawCircle(cx, cy, r * 0.045f, hand)

        if (showSeconds) {
            hand.color = p.second
            hand.strokeWidth = r * 0.012f
            val a = Math.toRadians(sec * 6.0)
            c.drawLine(
                cx - (sin(a) * r * 0.12f).toFloat(), cy + (cos(a) * r * 0.12f).toFloat(),
                cx + (sin(a) * r * 0.93f).toFloat(), cy - (cos(a) * r * 0.93f).toFloat(), hand
            )
            c.drawCircle(cx, cy, r * 0.03f, hand)
        }
        // Pivot ring
        hand.color = p.background
        c.drawCircle(cx, cy, r * 0.014f, hand)
    }

    private fun drawHand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, width: Float, r: Float) {
        val a = Math.toRadians(deg.toDouble())
        val sx = sin(a).toFloat(); val sy = -cos(a).toFloat()
        val stalk = r * 0.1f
        hand.style = Paint.Style.STROKE
        hand.strokeWidth = width * 0.35f
        c.drawLine(cx, cy, cx + sx * stalk, cy + sy * stalk, hand)
        hand.strokeWidth = width
        c.drawLine(cx + sx * stalk, cy + sy * stalk, cx + sx * len, cy + sy * len, hand)
    }
}
