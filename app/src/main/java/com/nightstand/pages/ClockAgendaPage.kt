package com.nightstand.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import com.nightstand.AnalogClock
import com.nightstand.BurnIn
import com.nightstand.Fonts
import com.nightstand.Prefs
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The original Nightstand layout, now with a choice of clock:
 *
 *   ┌───────────────────────────┬───────────────────────┐
 *   │   clock (digital/analog)  │ UP NEXT               │
 *   │   Tuesday, November 24    │ ▌ Standup   11:00     │
 *   ├───────────────────────────┴───────────────────────┤
 *   │ ◔ Messages 3 · Alex    ◔ Gmail 2                  │
 *   └───────────────────────────────────────────────────┘
 */
class ClockAgendaPage(context: Context, data: DisplayData) : Page(context, data) {
    companion object { const val ID = "clock_agenda" }
    override val pageId = ID
    override val needsSeconds get() = Prefs.clockStyle(context) == Prefs.ClockStyle.ANALOG

    private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.heavy }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.semibold }
    private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconCache = HashMap<String, Drawable?>()

    override fun onDraw(c: Canvas) {
        val p = palette
        c.drawColor(p.background)
        val now = Calendar.getInstance()
        val (sx, sy) = BurnIn.shiftFor(now.get(Calendar.MINUTE), dp(8f))
        c.save(); c.translate(sx, sy)

        val w = width.toFloat(); val h = height.toFloat()
        val pad = dp(24f)
        val notifH = if (data.notifs.isNotEmpty()) dp(56f) else 0f
        val split = w * 0.55f
        val bottom = h - notifH - pad * 0.5f

        if (Prefs.clockStyle(context) == Prefs.ClockStyle.ANALOG) {
            val r = minOf(split - pad * 2, bottom - pad) / 2
            AnalogClock.draw(c, pad + split / 2 - pad / 2, pad / 2 + (bottom) / 2, r, now, p, showSeconds = true)
        } else {
            drawDigital(c, now, pad, pad, split - pad, bottom)
        }
        drawAgenda(c, now, split + dp(12f), pad, w - pad, bottom)
        if (notifH > 0) drawNotifs(c, pad, h - notifH, w - pad, h - dp(10f))
        c.restore()
    }

    private fun drawDigital(c: Canvas, now: Calendar, l: Float, t: Float, r: Float, b: Float) {
        val is24 = DateFormat.is24HourFormat(context)
        val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", now).toString()
        clockPaint.color = palette.primary
        clockPaint.textSize = 100f
        clockPaint.textSize = minOf(100f * (r - l) / clockPaint.measureText("00:00"), (b - t) * 0.6f)
        val fm = clockPaint.fontMetrics
        text.textSize = clockPaint.textSize * 0.17f
        val blockH = (fm.descent - fm.ascent) + text.textSize * 1.6f
        var y = t + ((b - t) - blockH) / 2 - fm.ascent
        c.drawText(time, l, y, clockPaint)
        y += fm.descent + text.textSize * 1.2f
        text.color = palette.accent
        c.drawText(dateLine(now), l + dp(4f), y, text)
    }

    private fun dateLine(now: Calendar) = DateFormat.format(
        DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"), now
    ).toString()

    private fun drawAgenda(c: Canvas, now: Calendar, l: Float, t: Float, r: Float, b: Float) {
        val p = palette
        val analog = Prefs.clockStyle(context) == Prefs.ClockStyle.ANALOG
        var y = t + dp(8f)
        // With the analog face the date moves here, iOS-widget style.
        if (analog) {
            bold.textSize = dp(15f); bold.color = p.accent
            y += bold.textSize
            c.drawText(DateFormat.format("EEEE", now).toString().uppercase(), l, y, bold)
            clockPaint.textSize = dp(54f); clockPaint.color = p.primary
            y += clockPaint.textSize * 0.95f
            c.drawText(now.get(Calendar.DAY_OF_MONTH).toString(), l - dp(2f), y, clockPaint)
            y += dp(18f)
        }
        bold.textSize = dp(13f); bold.color = p.secondary
        y += bold.textSize
        c.drawText(if (data.events.isEmpty()) "NO MORE EVENTS" else "UP NEXT", l, y, bold)
        y += dp(10f)

        val todayEnd = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }.timeInMillis
        val is24 = DateFormat.is24HourFormat(context)
        val titleSize = dp(20f)
        val rowH = titleSize * 2.5f
        for (e in data.events) {
            if (y + rowH > b) break
            val tomorrow = e.begin >= todayEnd
            val whenStr = if (e.allDay) (if (tomorrow) "Tomorrow · all day" else "All day")
            else (if (tomorrow) "Tomorrow " else "") + DateFormat.format(if (is24) "HH:mm" else "h:mm a", Date(e.begin))
            bar.color = if (p.night) p.primary else (e.color or 0xFF000000.toInt())
            c.drawRoundRect(l, y + dp(4f), l + dp(4f), y + rowH - dp(8f), dp(2f), dp(2f), bar)
            text.textSize = titleSize; text.color = p.primary
            val title = TextUtils.ellipsize(e.title, text, r - l - dp(14f), TextUtils.TruncateAt.END)
            c.drawText(title, 0, title.length, l + dp(14f), y + titleSize, text)
            text.textSize = titleSize * 0.72f; text.color = p.secondary
            c.drawText(whenStr, l + dp(14f), y + titleSize * 2.0f, text)
            y += rowH
        }
        if (data.batteryPct >= 0 && y + dp(24f) < b) {
            text.textSize = dp(14f); text.color = p.secondary
            c.drawText((if (data.charging) "⚡ " else "") + "${data.batteryPct}%", l, b, text)
        }
    }

    private fun drawNotifs(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val p = palette
        val iconSize = (b - t) * 0.5f
        val cy = (t + b) / 2
        text.textSize = iconSize * 0.75f
        val maxChips = 3
        val chipW = (r - l) / maxChips
        var x = l
        for ((i, n) in data.notifs.withIndex()) {
            if (i == maxChips) break
            icon(n)?.let { d ->
                d.setBounds(x.toInt(), (cy - iconSize / 2).toInt(), (x + iconSize).toInt(), (cy + iconSize / 2).toInt())
                // Small icons are monochrome masks: tint to the palette, day or night.
                d.colorFilter = PorterDuffColorFilter(p.primary, PorterDuff.Mode.SRC_IN)
                d.draw(c)
            }
            val shown = if (data.showNotifContent) n.title else n.publicTitle
            val more = if (i == maxChips - 1 && data.notifs.size > maxChips) "  +${data.notifs.size - maxChips}" else ""
            val label = buildString {
                append(n.appLabel)
                if (n.count > 1) append(" ").append(n.count)
                if (!shown.isNullOrBlank()) append(" · ").append(shown)
            }
            text.color = p.primary
            val avail = chipW - iconSize - dp(22f) - text.measureText(more)
            val s = TextUtils.ellipsize(label, text, avail, TextUtils.TruncateAt.END).toString() + more
            c.drawText(s, x + iconSize + dp(8f), cy + text.textSize / 3, text)
            x += chipW
        }
    }

    private fun icon(n: com.nightstand.NotifSummary): Drawable? = iconCache.getOrPut(n.pkg + n.iconKey) {
        try {
            n.smallIcon?.loadDrawable(context)?.mutate()
                ?: context.packageManager.getApplicationIcon(n.pkg).mutate()
        } catch (_: Throwable) { null }
    }
}
