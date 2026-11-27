package com.nightstand.clock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import com.nightstand.Fonts
import com.nightstand.Palette
import java.util.Calendar
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin

/** What the clock faces show besides the time (docs/views/05-alarms-timers). */
data class ClockExtras(val alarm: NextAlarm?, val timer: RunningTimer?, val timers: List<RunningTimer>, val now: Long) {
    val isEmpty get() = alarm == null && timer == null
}

/** Single-colour vector glyphs so they tint with the palette (no emoji). */
object Glyphs {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun bell(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        p.color = color; p.style = Paint.Style.FILL
        val path = Path().apply {
            moveTo(cx - s * 0.42f, cy + s * 0.22f)
            cubicTo(cx - s * 0.32f, cy + s * 0.05f, cx - s * 0.34f, cy - s * 0.45f, cx, cy - s * 0.45f)
            cubicTo(cx + s * 0.34f, cy - s * 0.45f, cx + s * 0.32f, cy + s * 0.05f, cx + s * 0.42f, cy + s * 0.22f)
            close()
        }
        c.drawPath(path, p)
        c.drawCircle(cx, cy + s * 0.34f, s * 0.11f, p)
        c.drawCircle(cx, cy - s * 0.5f, s * 0.06f, p)
    }

    fun hourglass(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        p.color = color; p.style = Paint.Style.FILL
        val w = s * 0.34f; val h = s * 0.46f; val bar = s * 0.08f
        c.drawRect(cx - w - bar / 2, cy - h - bar, cx + w + bar / 2, cy - h, p)
        c.drawRect(cx - w - bar / 2, cy + h, cx + w + bar / 2, cy + h + bar, p)
        c.drawPath(Path().apply {
            moveTo(cx - w, cy - h); lineTo(cx + w, cy - h); lineTo(cx + s * 0.06f, cy)
            lineTo(cx + w, cy + h); lineTo(cx - w, cy + h); lineTo(cx - s * 0.06f, cy); close()
        }, p)
    }

    fun pause(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        p.color = color; p.style = Paint.Style.FILL
        c.drawRect(cx - s * 0.32f, cy - s * 0.4f, cx - s * 0.1f, cy + s * 0.4f, p)
        c.drawRect(cx + s * 0.1f, cy - s * 0.4f, cx + s * 0.32f, cy + s * 0.4f, p)
    }
}

object ClockOverlay {
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.heavy }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold }

    fun alarmTime(c: android.content.Context, a: NextAlarm): String =
        (if (a.expected) "~" else "") +
            DateFormat.format(if (DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm a", Date(a.triggerAt)).toString()

    /**
     * Analog face additions: timer arc on the ring (minute hand → end minute,
     * timers under 60 min), bell at the alarm's hour, and a small readout in
     * the half of the dial away from the hour hand.
     */
    fun drawAnalog(ctx: android.content.Context, c: Canvas, cx: Float, cy: Float, r: Float, t: Calendar, p: Palette, x: ClockExtras) {
        val timer = x.timer
        if (timer != null && timer.state == RunningTimer.State.RUNNING) {
            val rem = timer.remainingMs(x.now)
            if (rem in 1 until 60 * 60_000L) {
                val minuteDeg = (t.get(Calendar.MINUTE) + t.get(Calendar.SECOND) / 60f) * 6f
                val sweep = rem / 60_000f * 6f
                arc.color = p.accent; arc.strokeWidth = r * 0.035f
                val rr = r * 0.965f
                c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), minuteDeg - 90f, sweep, false, arc)
                val end = Math.toRadians((minuteDeg + sweep).toDouble())
                arc.style = Paint.Style.FILL
                c.drawCircle(cx + (sin(end) * rr).toFloat(), cy - (cos(end) * rr).toFloat(), r * 0.04f, arc)
                arc.style = Paint.Style.STROKE
            }
        }
        x.alarm?.let { a ->
            val at = Calendar.getInstance().apply { timeInMillis = a.triggerAt }
            val deg = (at.get(Calendar.HOUR) + at.get(Calendar.MINUTE) / 60f) * 30f
            drawAlarmTriangle(c, cx, cy, r, deg, hollow = !alarmWithin12h(a.triggerAt - x.now),
                color = if (a.expected) p.secondary else p.accent, background = p.background)
        }

        // Readout, opposite the hour hand.
        val lines = mutableListOf<Pair<String, Boolean>>() // text, isTimer
        timer?.let { lines += timerText(it, x.now) to true }
        x.alarm?.let { lines += alarmTime(ctx, it) to false }
        if (lines.isEmpty()) return
        val hourDeg = (t.get(Calendar.HOUR) + t.get(Calendar.MINUTE) / 60f) * 30f
        val handUp = cos(Math.toRadians(hourDeg.toDouble())) > 0
        big.textSize = r * 0.15f; small.textSize = r * 0.095f
        val blockH = lines.sumOf { (if (it.second) big.textSize else small.textSize).toDouble() }.toFloat() +
            (lines.size - 1) * small.textSize * 0.55f
        var y = if (handUp) cy + r * 0.26f else cy - r * 0.26f - blockH
        for ((i, pair) in lines.withIndex()) {
            val (text, isTimer) = pair
            if (i > 0) y += small.textSize * 0.55f // breathing room under the timer line
            val paint = if (isTimer) big else small
            paint.color = if (isTimer) (if (timer?.state == RunningTimer.State.PAUSED) p.secondary else p.accent) else p.secondary
            val glyph = paint.textSize * 0.9f
            val w = glyph * 1.25f + paint.measureText(text)
            val left = cx - w / 2
            y += paint.textSize
            val gy = y - paint.textSize * 0.36f
            if (isTimer) {
                if (timer?.state == RunningTimer.State.PAUSED) Glyphs.pause(c, left + glyph / 2, gy, glyph, paint.color)
                else Glyphs.hourglass(c, left + glyph / 2, gy, glyph, paint.color)
            } else Glyphs.bell(c, left + glyph / 2, gy, glyph, paint.color)
            c.drawText(text, left + glyph * 1.25f, y, paint)
        }
    }

    private val tri = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }

    /** Solid triangle within 12 h (this turn of the dial), hollow for 12–24 h. */
    fun alarmWithin12h(untilMs: Long) = untilMs <= 12 * 3_600_000L

    /**
     * Alarm marker ON the tick ring: base on the ring, tip pointing to the centre,
     * stopping just outside the numerals (≈0.86 r). Not outside the ring: on the
     * S26 the ring is only 14 dp from the screen edge (6 dp with burn-in shift),
     * and an outside marker needs ~23 dp. Ticks under it are cleared so the
     * hollow version reads cleanly. (docs/previews/alarm-triangle-on-ring.png)
     */
    private fun drawAlarmTriangle(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, hollow: Boolean, color: Int, background: Int) {
        fun pt(d: Float, rr: Float) = Math.toRadians(d.toDouble()).let { (cx + sin(it) * rr).toFloat() to (cy - cos(it) * rr).toFloat() }
        val half = 5.2f
        fun tri(h: Float, outer: Float, tip: Float) = android.graphics.Path().apply {
            val b1 = pt(deg - h, outer); val b2 = pt(deg + h, outer); val t = pt(deg, tip)
            moveTo(b1.first, b1.second); lineTo(b2.first, b2.second); lineTo(t.first, t.second); close()
        }
        tri.style = Paint.Style.FILL; tri.color = background
        c.drawPath(tri(half * 1.25f, r * 1.02f, r * 0.835f), tri) // clear ticks underneath
        tri.color = color
        if (hollow) { tri.style = Paint.Style.STROKE; tri.strokeWidth = r * 0.018f } else tri.style = Paint.Style.FILL
        c.drawPath(tri(half, r, r * 0.855f), tri)
    }

    fun timerText(tm: RunningTimer, now: Long) =
        if (tm.state == RunningTimer.State.RINGING) "Time's up" else formatCountdown(tm.remainingMs(now))

    /** Height [drawDigital] will use, so callers can centre their block. */
    fun digitalHeight(x: ClockExtras, size: Float): Float {
        var h = 0f
        if (x.alarm != null) h += size * 1.7f
        h += minOf(x.timers.size, 2) * size * 2.1f
        return h
    }

    /**
     * Digital face additions under the date: "🔔 6:30 AM · in 6 h 49 m" and
     * up to two timer chips counting down (paused: dimmed with a pause sign).
     */
    fun drawDigital(ctx: android.content.Context, c: Canvas, left: Float, top: Float, maxW: Float, size: Float, p: Palette, x: ClockExtras) {
        var y = top
        x.alarm?.let { a ->
            small.textSize = size
            y += size * 1.3f
            val col = if (a.expected) p.secondary else p.primary
            Glyphs.bell(c, left + size * 0.45f, y - size * 0.36f, size, col)
            small.color = col
            val t = alarmTime(ctx, a)
            c.drawText(t, left + size * 1.3f, y, small)
            small.color = p.secondary
            c.drawText(" · " + formatIn(a.triggerAt - x.now) + if (a.expected) " · expected" else "",
                left + size * 1.3f + small.measureText(t), y, small)
            y += size * 0.4f
        }
        val chips = x.timers.sortedBy { it.state.ordinal }.take(2)
        for (tm in chips) {
            y += size * 0.5f
            val h = size * 1.6f
            val paused = tm.state == RunningTimer.State.PAUSED
            val col = if (paused) p.secondary else p.primary
            big.textSize = size * 1.05f; small.textSize = size * 0.75f
            val txt = timerText(tm, x.now)
            val labelW = tm.label?.let { small.measureText(it) + size * 0.6f } ?: 0f
            val w = minOf(maxW, size * 2.2f + big.measureText(txt) + labelW + size * 0.6f)
            arc.color = col; arc.strokeWidth = size * 0.1f
            c.drawRoundRect(left, y, left + w, y + h, h / 2, h / 2, arc)
            val gy = y + h / 2
            if (paused) Glyphs.pause(c, left + size * 0.9f, gy, size * 0.8f, col) else Glyphs.hourglass(c, left + size * 0.9f, gy, size * 0.8f, col)
            big.color = col
            c.drawText(txt, left + size * 1.7f, gy + big.textSize * 0.36f, big)
            tm.label?.let {
                small.color = p.secondary
                val lx = left + size * 1.7f + big.measureText(txt) + size * 0.5f
                val s = TextUtils.ellipsize(it, small, left + w - lx - size * 0.5f, TextUtils.TruncateAt.END)
                c.drawText(s, 0, s.length, lx, gy + small.textSize * 0.36f, small)
            }
            y += h
        }
    }

    /** Last-minute takeover of the right-hand view. */
    fun drawBigCountdown(c: Canvas, r: RectF, p: Palette, tm: RunningTimer, now: Long) {
        val txt = timerText(tm, now)
        big.color = p.accent
        big.textSize = 100f
        big.textSize = minOf(100f * r.width() * 0.85f / big.measureText(txt), r.height() * 0.5f)
        val w = big.measureText(txt)
        val fm = big.fontMetrics
        val base = r.centerY() - (fm.ascent + fm.descent) / 2
        c.drawText(txt, r.centerX() - w / 2, base, big)
        val label = tm.label ?: "Timer"
        small.textSize = big.textSize * 0.16f; small.color = p.secondary
        val lw = small.measureText(label)
        Glyphs.hourglass(c, r.centerX() - lw / 2 - small.textSize * 0.8f, base + fm.descent + small.textSize * 0.9f, small.textSize, p.secondary)
        c.drawText(label, r.centerX() - lw / 2 + small.textSize * 0.2f, base + fm.descent + small.textSize * 1.25f, small)
    }

    /** True when the big countdown should replace the right-hand view. */
    fun bigCountdownActive(ctx: android.content.Context, tm: RunningTimer?, night: Boolean, now: Long): Boolean {
        tm ?: return false
        if (!(if (night) com.nightstand.Prefs.bigCountdownNight(ctx) else com.nightstand.Prefs.bigCountdownDay(ctx))) return false
        return tm.state == RunningTimer.State.RINGING ||
            (tm.state == RunningTimer.State.RUNNING && tm.remainingMs(now) in 1..60_000L)
    }
}
