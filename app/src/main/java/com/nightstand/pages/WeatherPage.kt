package com.nightstand.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import com.nightstand.Fonts
import com.nightstand.weather.Weather
import com.nightstand.weather.WeatherCodes
import com.nightstand.weather.WeatherIcons
import java.util.Calendar

/**
 * iOS StandBy clock + weather: analog/digital clock left; right: place name
 * with a location arrow, huge temperature, condition glyph + label, H/L.
 */
class WeatherPage(context: Context, data: DisplayData) : ClockSplitPage(context, data) {
    companion object { const val ID = "clock_weather" }
    override val pageId = ID

    private val place = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold }
    private val temp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.heavy }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onShown() {
        Weather.loadCache(context)
        Weather.refreshIfStale(context) { invalidate() }
    }

    override fun onTick() {
        Weather.refreshIfStale(context) { invalidate() } // no-op unless 30 min old
        invalidate()
    }

    override fun drawRight(c: Canvas, r: RectF, now: Calendar) {
        val p = palette
        val w = Weather.current
        val x = r.left + dp(12f)
        val h = r.height()
        place.color = p.accent; temp.color = p.primary; label.color = p.primary

        if (w == null) {
            label.textSize = dp(20f)
            val msg = if (Weather.hasLocationPermission(context)) "Getting weather…" else "Allow location in Nightstand setup"
            c.drawText(msg, x, r.centerY(), label)
            return
        }

        place.textSize = h * 0.085f
        var y = r.top + h * 0.17f
        val name = TextUtils.ellipsize(w.place ?: "Current location", place, r.width() * 0.75f, TextUtils.TruncateAt.END)
        c.drawText(name, 0, name.length, x, y, place)
        // Location arrow (iOS style), only for GPS-derived places — which is always, by design.
        val ax = x + place.measureText(name, 0, name.length) + place.textSize * 0.35f
        val asz = place.textSize * 0.62f
        arrow.color = p.accent
        c.drawPath(Path().apply {
            moveTo(ax + asz, y - asz); lineTo(ax, y - asz * 0.45f)
            lineTo(ax + asz * 0.45f, y - asz * 0.4f); lineTo(ax + asz * 0.55f, y); close()
        }, arrow)

        temp.textSize = h * 0.42f
        y += temp.textSize * 0.95f
        c.drawText("${w.temp}°", x - temp.textSize * 0.04f, y, temp)

        val icon = h * 0.1f
        y += h * 0.1f
        WeatherIcons.draw(c, WeatherCodes.icon(w.code), w.isDay, x, y - icon * 0.1f, icon, p.primary)
        label.textSize = h * 0.085f
        c.drawText(WeatherCodes.label(w.code), x + icon * 1.35f, y + icon * 0.72f, label)
        y += icon + h * 0.11f
        c.drawText("H:${w.high}° L:${w.low}°", x, y, label)
    }
}
