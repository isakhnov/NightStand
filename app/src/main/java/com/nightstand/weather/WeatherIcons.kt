package com.nightstand.weather

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/**
 * Weather glyphs drawn with Canvas in a single colour, so they tint to the
 * night red for free and need no icon assets. [s] is the glyph's box size.
 */
object WeatherIcons {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    fun draw(c: Canvas, icon: WeatherCodes.Icon, isDay: Boolean, x: Float, y: Float, s: Float, color: Int) {
        p.color = color
        when (icon) {
            WeatherCodes.Icon.CLEAR -> if (isDay) sun(c, x + s / 2, y + s / 2, s * 0.22f, s) else moon(c, x + s / 2, y + s / 2, s * 0.36f)
            WeatherCodes.Icon.PARTLY_CLOUDY -> {
                if (isDay) sun(c, x + s * 0.62f, y + s * 0.36f, s * 0.15f, s * 0.7f) else moon(c, x + s * 0.64f, y + s * 0.34f, s * 0.22f)
                cloud(c, x, y + s * 0.18f, s * 0.82f)
            }
            WeatherCodes.Icon.CLOUDY -> cloud(c, x, y + s * 0.08f, s)
            WeatherCodes.Icon.FOG -> {
                cloud(c, x, y - s * 0.05f, s * 0.9f)
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.07f
                c.drawLine(x + s * 0.1f, y + s * 0.78f, x + s * 0.9f, y + s * 0.78f, p)
                c.drawLine(x + s * 0.2f, y + s * 0.92f, x + s * 0.8f, y + s * 0.92f, p)
            }
            WeatherCodes.Icon.DRIZZLE, WeatherCodes.Icon.RAIN -> {
                cloud(c, x, y - s * 0.08f, s)
                p.style = Paint.Style.STROKE; p.strokeWidth = s * (if (icon == WeatherCodes.Icon.RAIN) 0.075f else 0.05f)
                for (i in 0..2) {
                    val dx = x + s * (0.3f + i * 0.2f)
                    c.drawLine(dx, y + s * 0.74f, dx - s * 0.07f, y + s * 0.94f, p)
                }
            }
            WeatherCodes.Icon.SNOW -> {
                cloud(c, x, y - s * 0.08f, s)
                p.style = Paint.Style.FILL
                for (i in 0..2) c.drawCircle(x + s * (0.3f + i * 0.2f), y + s * (0.8f + (i % 2) * 0.1f), s * 0.05f, p)
            }
            WeatherCodes.Icon.THUNDER -> {
                cloud(c, x, y - s * 0.08f, s)
                p.style = Paint.Style.FILL
                val b = Path().apply {
                    moveTo(x + s * 0.52f, y + s * 0.62f); lineTo(x + s * 0.38f, y + s * 0.84f)
                    lineTo(x + s * 0.5f, y + s * 0.84f); lineTo(x + s * 0.44f, y + s * 1.0f)
                    lineTo(x + s * 0.64f, y + s * 0.76f); lineTo(x + s * 0.52f, y + s * 0.76f); close()
                }
                c.drawPath(b, p)
            }
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, box: Float) {
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = box * 0.06f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val r1 = r * 1.45f; val r2 = r * 1.95f
            c.drawLine(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(),
                cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), p)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.FILL
        val m = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            op(Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.85f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        }
        c.drawPath(m, p)
    }

    /** Filled cloud occupying roughly the top 70% of an s×s box at (x, y). */
    private fun cloud(c: Canvas, x: Float, y: Float, s: Float) {
        p.style = Paint.Style.FILL
        val path = Path().apply {
            addCircle(x + s * 0.33f, y + s * 0.48f, s * 0.2f, Path.Direction.CW)
            addCircle(x + s * 0.55f, y + s * 0.38f, s * 0.25f, Path.Direction.CW)
            addCircle(x + s * 0.75f, y + s * 0.52f, s * 0.16f, Path.Direction.CW)
            addRoundRect(x + s * 0.13f, y + s * 0.46f, x + s * 0.91f, y + s * 0.68f, s * 0.11f, s * 0.11f, Path.Direction.CW)
        }
        c.drawPath(path, p)
    }
}
