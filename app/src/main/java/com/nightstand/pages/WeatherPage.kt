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
        val h = r.height()
        place.color = p.accent; temp.color = p.primary; label.color = p.primary

        if (w == null) {
            label.textSize = dp(20f)
            val msg = if (Weather.hasLocationPermission(context)) "Getting weather…" else "Allow location in Nightstand setup"
            c.drawText(msg, r.left + dp(12f), r.centerY(), label)
            return
        }

        // Sizes first, then centre the whole block in the pane as one
        // left-aligned group (iOS widget style), horizontally and vertically.
        place.textSize = h * 0.085f
        temp.textSize = h * 0.42f
        label.textSize = h * 0.085f
        val icon = h * 0.1f
        val nameStr = w.place ?: "Current location"
        val asz = place.textSize * 0.62f
        val tempStr = "${w.temp}°"
        val condStr = WeatherCodes.label(w.code)
        val hlStr = "H:${w.high}° L:${w.low}°"
        val maxW = r.width() - dp(16f)
        val name = TextUtils.ellipsize(nameStr, place, maxW - place.textSize, TextUtils.TruncateAt.END)
        val placeW = place.measureText(name, 0, name.length) + place.textSize * 0.35f + asz
        val tempW = temp.measureText(tempStr)
        val condW = icon * 1.35f + label.measureText(condStr)
        val hlW = label.measureText(hlStr)
        val blockW = minOf(maxW, maxOf(placeW, tempW, condW, hlW))

        val pfm = place.fontMetrics; val lfm = label.fontMetrics
        val placeH = -pfm.ascent
        // Big numerals: use their real ink height (no descender) so spacing looks even.
        val tb = android.graphics.Rect(); temp.getTextBounds(tempStr, 0, tempStr.length, tb)
        val tempTop = -tb.top.toFloat()
        val gap1 = h * 0.08f; val gap2 = h * 0.07f; val gap3 = h * 0.04f
        val condH = maxOf(icon, -lfm.ascent)
        val hlH = -lfm.ascent
        val blockH = placeH + gap1 + tempTop + gap2 + condH + gap3 + hlH

        val x = r.centerX() - blockW / 2
        var y = r.centerY() - blockH / 2

        y += placeH
        c.drawText(name, 0, name.length, x, y, place)
        val ax = x + place.measureText(name, 0, name.length) + place.textSize * 0.35f
        arrow.color = p.accent
        c.drawPath(Path().apply {
            moveTo(ax + asz, y - asz); lineTo(ax, y - asz * 0.45f)
            lineTo(ax + asz * 0.45f, y - asz * 0.4f); lineTo(ax + asz * 0.55f, y); close()
        }, arrow)

        y += gap1 + tempTop
        // Big glyphs carry left side-bearing; nudge so the "7" lines up with the text above.
        c.drawText(tempStr, x - temp.textSize * 0.04f, y, temp)

        y += gap2
        WeatherIcons.draw(c, WeatherCodes.icon(w.code), w.isDay, x, y + (condH - icon) / 2, icon, p.primary)
        c.drawText(condStr, x + icon * 1.35f, y + condH / 2 + (-lfm.ascent) * 0.36f, label)
        y += condH + gap3 + hlH
        c.drawText(hlStr, x, y, label)
    }
}
