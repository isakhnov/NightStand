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

    private var scale = 1f

    private fun drawRightScaled(c: Canvas, r: RectF, w: com.nightstand.weather.WeatherNow, k: Float) {
        scale *= k
        try { drawRight(c, r, Calendar.getInstance()) } finally { scale = 1f }
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

        // iPhone StandBy layout (user feedback on 0.7.6): two groups pushed apart —
        //   top:    place + arrow, huge temperature
        //   bottom: condition icon on its own line, condition text, H/L
        // The whole block stays left-aligned and is centred horizontally in the pane.
        place.textSize = h * 0.085f * scale
        temp.textSize = h * 0.40f * scale
        label.textSize = h * 0.08f * scale
        val icon = h * 0.11f * scale
        val nameStr = w.place ?: "Current location"
        val asz = place.textSize * 0.62f
        val tempStr = "${w.temp}°"
        val condStr = WeatherCodes.label(w.code)
        val hlStr = "H:${w.high}° L:${w.low}°"
        val maxW = r.width() - dp(16f)
        val name = TextUtils.ellipsize(nameStr, place, maxW - place.textSize, TextUtils.TruncateAt.END)
        val placeW = place.measureText(name, 0, name.length) + place.textSize * 0.35f + asz
        val tempW = temp.measureText(tempStr)
        val condW = maxOf(icon, label.measureText(condStr))
        val hlW = label.measureText(hlStr)
        // The place name ellipsizes on its own; only the fixed-content lines drive scaling.
        val fixedW = maxOf(tempW, condW, hlW)
        if (fixedW > maxW && scale == 1f) return drawRightScaled(c, r, w, maxW / fixedW) // one re-layout only
        val blockW = minOf(maxW, maxOf(minOf(placeW, maxW), fixedW))
        val x = r.centerX() - blockW / 2

        val pfm = place.fontMetrics; val lfm = label.fontMetrics
        val tb = android.graphics.Rect(); temp.getTextBounds(tempStr, 0, tempStr.length, tb)
        val capPlace = -pfm.ascent * 0.78f
        val capLabel = -lfm.ascent * 0.78f

        // --- top group, anchored to the top of the pane ---
        val top = r.top + h * 0.10f
        var y = top + capPlace
        c.drawText(name, 0, name.length, x, y, place)
        val ax = x + place.measureText(name, 0, name.length) + place.textSize * 0.35f
        arrow.color = p.accent
        c.drawPath(Path().apply {
            moveTo(ax + asz, y - asz); lineTo(ax, y - asz * 0.45f)
            lineTo(ax + asz * 0.45f, y - asz * 0.4f); lineTo(ax + asz * 0.55f, y); close()
        }, arrow)
        y += h * 0.06f - tb.top // gap, then the numerals' real ink height
        // Big glyphs carry left side-bearing; nudge so the digit lines up with the text above.
        c.drawText(tempStr, maxOf(r.left, x - temp.textSize * 0.04f), y, temp) // never into the clock
        val topBottom = y

        // --- bottom group, anchored to the bottom of the pane ---
        val hlBase = r.bottom - h * 0.08f
        val condBase = hlBase - capLabel - h * 0.05f
        val iconTop = condBase - capLabel - h * 0.035f - icon
        if (iconTop < topBottom + h * 0.03f) {
            // Not enough room (very wide/short pane): keep groups from touching.
            if (scale == 1f) return drawRightScaled(c, r, w, 0.85f)
        }
        WeatherIcons.draw(c, WeatherCodes.icon(w.code), w.isDay, x, iconTop, icon, p.primary)
        c.drawText(condStr, x, condBase, label)
        c.drawText(hlStr, x, hlBase, label)
    }
}
