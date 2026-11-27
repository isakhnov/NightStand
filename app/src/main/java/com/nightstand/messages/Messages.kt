package com.nightstand.messages

import android.app.Notification
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.text.TextPaint
import com.nightstand.Fonts
import com.nightstand.Palette

/** One messaging app with unread messages (private: icon + count only). */
data class MessageApp(val pkg: String, val count: Int, val smallIcon: Icon? = null, val postTime: Long = 0)

/** Settings: Look → Messages. */
data class MessageStyle(
    val on: Boolean = true,
    val vertical: Boolean = true,
    val overlay: Boolean = false,   // false = dedicated strip (views shrink), true = on top in a pill
    val fading: Boolean = false,    // fade to half strength 5 s after the last change
)

/**
 * Which notifications are "messages", and how many. Pure; unit-tested.
 * A notification counts if Android marks it as a message or a conversation
 * (WhatsApp, Signal, Messages, Telegram… all do). Count: the app's own
 * `number` if set, else the messages in a MessagingStyle, else 1.
 */
object MessageRules {
    data class Fields(
        val pkg: String, val ongoing: Boolean, val groupSummary: Boolean,
        val category: String?, val template: String?, val isConversation: Boolean,
        val number: Int, val styleMessages: Int, val postTime: Long, val smallIcon: Icon? = null,
    )

    fun isMessage(f: Fields): Boolean =
        !f.ongoing && !f.groupSummary &&
            (f.category == Notification.CATEGORY_MESSAGE || f.isConversation ||
                f.template?.endsWith("MessagingStyle") == true)

    fun countOf(f: Fields): Int = when {
        f.number > 0 -> f.number
        f.styleMessages > 0 -> f.styleMessages
        else -> 1
    }

    /** Group by app, newest app first. */
    fun summarize(all: List<Fields>, ownPkg: String): List<MessageApp> = all
        .filter { it.pkg != ownPkg && isMessage(it) }
        .groupBy { it.pkg }
        .map { (pkg, l) -> MessageApp(pkg, l.sumOf { countOf(it) }, l.maxBy { it.postTime }.smallIcon, l.maxOf { it.postTime }) }
        .sortedByDescending { it.postTime }
}

/**
 * Layout + drawing of the message badges. Strip mode reserves a column
 * (vertical, right edge) or a row (horizontal, bottom edge) and the views
 * shrink to the rest; overlay mode draws the same badges on top in a
 * translucent pill. Day: the app's own icon; night: its monochrome status
 * icon in a ring, all in the night colour.
 */
object MessageStrip {
    /** Space a dedicated strip takes, in dp (column width / row height). */
    const val STRIP_DP = 64f
    const val MAX_ITEMS = 5
    const val RADIUS_DP = 19f

    /** Distance between badge centres, px. */
    fun step(vertical: Boolean, d: Float) = 2 * RADIUS_DP * d + (if (vertical) 30 else 34) * d
    /** Badges drawn for [n] apps (at most [MAX_ITEMS], the last one "+k" on overflow). */
    fun slots(n: Int) = minOf(n, MAX_ITEMS)

    /** Pure: centres of up to [n] items in a strip area, plus whether an overflow "+k" slot is needed. */
    fun centres(n: Int, vertical: Boolean, area: RectF, step: Float, maxItems: Int = MAX_ITEMS): List<Pair<Float, Float>> {
        val k = minOf(n, maxItems)
        if (k == 0) return emptyList()
        val span = step * (k - 1)
        return List(k) { i ->
            if (vertical) area.centerX() to (area.centerY() - span / 2 + i * step)
            else (area.centerX() - span / 2 + i * step) to area.centerY()
        }
    }

    /** Pure: overlay/strip alpha for "fading" (1 → 0.45 over 1 s, starting 5 s after the last change). */
    fun alpha(fading: Boolean, now: Long, lastChange: Long): Float {
        if (!fading) return 1f
        val t = now - lastChange - 5_000
        return if (t <= 0) 1f else (1f - 0.55f * (t / 1_000f).coerceAtMost(1f))
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; textAlign = Paint.Align.CENTER }
    private val icons = HashMap<String, Drawable?>()
    /** Tests: stand-in app icons (Robolectric has no WhatsApp). */
    internal var iconOverride: ((String) -> Drawable?)? = null

    /**
     * Draws the badges into [area] (the strip, or the overlay region).
     * [d] = density. Returns nothing; caller handles burn-in shift and layout.
     */
    fun draw(ctx: Context, c: Canvas, area: RectF, apps: List<MessageApp>, style: MessageStyle,
             pal: Palette, alpha: Float, d: Float) {
        if (apps.isEmpty()) return
        val r = RADIUS_DP * d
        val step = step(style.vertical, d)
        val shown = if (apps.size > MAX_ITEMS) apps.take(MAX_ITEMS - 1) else apps
        val overflow = apps.size - shown.size
        val slots = slots(apps.size)
        val pts = centres(slots, style.vertical, area, step)
        val a = (255 * alpha).toInt()

        if (style.overlay) { // translucent pill behind the badges
            val first = pts.first(); val last = pts.last()
            val pad = 10 * d
            val box = if (style.vertical) RectF(first.first - r - pad, first.second - r - pad, last.first + r + pad, last.second + r + 28 * d)
                else RectF(first.first - r - pad, first.second - r - pad, last.first + r + pad, last.second + r + 26 * d)
            p.style = Paint.Style.FILL; p.color = Color.BLACK; p.alpha = (0x99 * alpha).toInt()
            c.drawRoundRect(box, 24 * d, 24 * d, p)
        }
        shown.forEachIndexed { i, m ->
            val (cx, cy) = pts[i]
            drawIcon(ctx, c, cx, cy, r, m, pal, a)
            label(c, if (m.count > 99) "99+" else m.count.toString(), cx, cy + r + 21 * d, pal, a, d)
        }
        if (overflow > 0) {
            val (cx, cy) = pts.last()
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = if (pal.night) pal.primary else 0xFF8E8E93.toInt(); p.alpha = a
            c.drawCircle(cx, cy, r, p)
            label(c, "+$overflow", cx, cy + 6 * d, pal, a, d, size = 15f)
        }
        p.alpha = 255
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, pal: Palette, a: Int, d: Float, size: Float = 19f) {
        t.textSize = size * d
        t.color = if (pal.night) pal.primary else 0xFFF2F2F5.toInt(); t.alpha = a
        c.drawText(s, x, y, t)
    }

    private fun drawIcon(ctx: Context, c: Canvas, cx: Float, cy: Float, r: Float, m: MessageApp, pal: Palette, a: Int) {
        val box = android.graphics.Rect((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt())
        if (pal.night) {
            // Ring + the app's monochrome status icon, so apps stay distinguishable in one colour.
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * ctx.resources.displayMetrics.density
            p.color = pal.primary; p.alpha = a
            c.drawCircle(cx, cy, r, p)
            val small = runCatching { m.smallIcon?.loadDrawable(ctx)?.mutate() }.getOrNull()
            if (small != null) {
                val inset = (r * 0.45f).toInt()
                small.setBounds(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
                small.colorFilter = PorterDuffColorFilter(pal.primary, PorterDuff.Mode.SRC_IN); small.alpha = a
                small.draw(c)
            }
        } else {
            val icon = iconOverride?.invoke(m.pkg) ?: icons.getOrPut(m.pkg) { runCatching { ctx.packageManager.getApplicationIcon(m.pkg).mutate() }.getOrNull() }
            if (icon != null) { icon.bounds = box; icon.alpha = a; icon.colorFilter = null; icon.draw(c) }
            else { p.style = Paint.Style.FILL; p.color = 0xFF3A3A3C.toInt(); p.alpha = a; c.drawCircle(cx, cy, r, p) }
        }
    }
}
