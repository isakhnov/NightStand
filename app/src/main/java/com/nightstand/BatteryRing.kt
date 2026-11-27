package com.nightstand

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/** What the corner battery ring shows. */
data class BatteryState(
    val level: Int,          // 0..100
    val plugged: Boolean,
    val charging: Boolean,   // actively charging right now
    val limit: Int,          // learned charge limit (Samsung battery protection), 100 if none
) {
    /** Charging has stopped at the limit (or 100 %): the ring turns into a solid disc. */
    val atLimit get() = plugged && !charging && level >= limit - 1
}

/**
 * Small charging indicator for the display's top-left corner: an arc for the
 * level, a tick at the learned charge limit, and a centre that fills in as the
 * battery approaches the limit, becoming a solid disc once charging stops
 * there. Green while charging by day, grey otherwise, the night colour at night.
 */
object BatteryRing {
    const val GREEN = 0xFF34C759.toInt()

    /**
     * Android doesn't expose Samsung's "charge to 80/85/90/95 %" setting. But
     * "plugged in, not charging, below 100 %" means the limit has been reached,
     * so the level at that moment is the limit. Pure; unit-tested.
     */
    fun learnLimit(prevLimit: Int, level: Int, plugged: Boolean, status: Int): Int = when {
        !plugged -> prevLimit
        status == android.os.BatteryManager.BATTERY_STATUS_FULL -> 100
        status == android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING && level in 50..99 -> level
        status == android.os.BatteryManager.BATTERY_STATUS_CHARGING && level > prevLimit -> 100 // limit was raised/turned off
        else -> prevLimit
    }

    /** How solid the centre is: 0 until 85 % of the way to the limit, then up to 1; 1 at the limit. */
    fun fillFraction(s: BatteryState): Float {
        if (s.atLimit) return 1f
        val p = (s.level.toFloat() / s.limit).coerceIn(0f, 1f)
        return ((p - 0.85f) / 0.15f).coerceIn(0f, 0.85f)
    }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(c: Canvas, cx: Float, cy: Float, radius: Float, s: BatteryState, palette: Palette) {
        val color = when {
            palette.night -> palette.primary
            s.charging || s.atLimit -> GREEN
            else -> 0xFF8E8E93.toInt()
        }
        val w = radius * 0.24f
        val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        // track
        ring.strokeWidth = w; ring.color = color; ring.alpha = 60
        c.drawArc(box, 0f, 360f, false, ring)
        // level arc, clockwise from 12
        ring.alpha = 255
        c.drawArc(box, -90f, 360f * s.level / 100f, false, ring)
        // centre fill approaching the limit → solid disc at the limit
        val f = fillFraction(s)
        if (f > 0f) {
            fill.color = color; fill.alpha = (255 * f).toInt()
            c.drawCircle(cx, cy, radius + w / 2, fill)
        }
        // tick at the charge limit (only when there is one below 100 %)
        if (s.limit < 100) {
            val a = Math.toRadians((s.limit * 3.6).toDouble())
            ring.strokeWidth = w * 0.55f; ring.alpha = 255
            ring.color = if (f >= 1f) palette.background else color
            val r1 = radius - w * 1.2f; val r2 = radius + w * 1.2f
            c.drawLine(cx + (sin(a) * r1).toFloat(), cy - (cos(a) * r1).toFloat(),
                cx + (sin(a) * r2).toFloat(), cy - (cos(a) * r2).toFloat(), ring)
        }
    }
}
