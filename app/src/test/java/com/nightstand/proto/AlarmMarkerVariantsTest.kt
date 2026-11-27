package com.nightstand.proto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import androidx.test.core.app.ApplicationProvider
import com.nightstand.AnalogClock
import com.nightstand.Fonts
import com.nightstand.Palette
import com.nightstand.clock.Glyphs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/**
 * PROTOTYPES ONLY (not in the app): alarm-marker designs for the analog face,
 * each drawn for an alarm 7 h away (within 12 h) and 15 h away (12–24 h),
 * day and night. Output: build/previews/alarm-variants.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xhdpi")
class AlarmMarkerVariantsTest {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; textAlign = Paint.Align.CENTER }

    private fun ang(cal: Calendar) = (cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f) * 30f
    private fun pt(cx: Float, cy: Float, deg: Float, rr: Float) =
        Math.toRadians(deg.toDouble()).let { (cx + sin(it) * rr).toFloat() to (cy - cos(it) * rr).toFloat() }

    // A — "alarm hand": short slim hand with a round tip; near = solid, far = hollow outline
    private fun a(c: Canvas, cx: Float, cy: Float, r: Float, now: Calendar, al: Calendar, far: Boolean, pal: Palette) {
        val d = ang(al); val (ex, ey) = pt(cx, cy, d, r * 0.62f)
        p.color = pal.accent; p.strokeCap = Paint.Cap.ROUND
        if (!far) { p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.03f; c.drawLine(cx, cy, ex, ey, p)
            p.style = Paint.Style.FILL; c.drawCircle(ex, ey, r * 0.055f, p) }
        else { p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.012f; c.drawLine(cx, cy, ex, ey, p); c.drawCircle(ex, ey, r * 0.055f, p) }
        Glyphs.bell(c, ex, ey, r * 0.06f, if (far) pal.accent else pal.background)
    }

    // B — arc on the ring from NOW to the alarm; far = arc wraps a full turn: thin outer ring + arc
    private fun b(c: Canvas, cx: Float, cy: Float, r: Float, now: Calendar, al: Calendar, far: Boolean, pal: Palette) {
        val s = ang(now); var sweep = (ang(al) - s + 360f) % 360f
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.color = pal.accent
        val rr = r * 1.03f
        if (far) { p.strokeWidth = r * 0.008f; p.alpha = 140; c.drawCircle(cx, cy, rr + r * 0.03f, p); p.alpha = 255 }
        p.strokeWidth = r * 0.025f; p.alpha = 200
        c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), s - 90f, sweep, false, p); p.alpha = 255
        val (bx, by) = pt(cx, cy, ang(al), rr); Glyphs.bell(c, bx, by - r * 0.0f, r * 0.08f, pal.accent)
    }

    // C — soft wedge (pie slice) from now to the alarm; far = two layers (fuller)
    private fun cW(c: Canvas, cx: Float, cy: Float, r: Float, now: Calendar, al: Calendar, far: Boolean, pal: Palette) {
        val s = ang(now); val sweep = (ang(al) - s + 360f) % 360f
        p.style = Paint.Style.FILL; p.color = pal.accent
        val rr = r * 0.9f; val box = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
        if (far) { p.alpha = 22; c.drawCircle(cx, cy, rr, p) }
        p.alpha = if (far) 40 else 55; c.drawArc(box, s - 90f, sweep, true, p); p.alpha = 255
        val (bx, by) = pt(cx, cy, ang(al), r * 0.94f); Glyphs.bell(c, bx, by, r * 0.085f, pal.accent)
    }

    // D — lit ticks: minute ticks between now and the alarm glow in accent; far = every 5th only
    private fun d(c: Canvas, cx: Float, cy: Float, r: Float, now: Calendar, al: Calendar, far: Boolean, pal: Palette) {
        val s = ang(now); val sweep = (ang(al) - s + 360f) % 360f
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.color = pal.accent
        var k = 0
        while (k * 6f <= sweep) {
            val deg = s + k * 6f
            if (!far || k % 5 == 0) {
                p.strokeWidth = r * 0.02f
                val (x1, y1) = pt(cx, cy, deg, r * 0.92f); val (x2, y2) = pt(cx, cy, deg, r)
                c.drawLine(x1, y1, x2, y2, p)
            }
            k++
        }
        val (bx, by) = pt(cx, cy, ang(al), r * 1.06f); Glyphs.bell(c, bx, by, r * 0.08f, pal.accent)
    }

    // E — bezel marker: triangle outside the ring pointing in + pill label; far = hollow + "tomorrow"
    private fun e(c: Canvas, cx: Float, cy: Float, r: Float, now: Calendar, al: Calendar, far: Boolean, pal: Palette) {
        val deg = ang(al); val rad = Math.toRadians(deg.toDouble())
        val tip = pt(cx, cy, deg, r * 1.0f); val b1 = pt(cx, cy, deg - 4f, r * 1.1f); val b2 = pt(cx, cy, deg + 4f, r * 1.1f)
        p.color = pal.accent; p.strokeWidth = r * 0.015f
        p.style = if (far) Paint.Style.STROKE else Paint.Style.FILL
        c.drawPath(Path().apply { moveTo(tip.first, tip.second); lineTo(b1.first, b1.second); lineTo(b2.first, b2.second); close() }, p)
        t.textSize = r * 0.085f; t.color = pal.accent
        val (lx, ly) = pt(cx, cy, deg, r * 0.7f)
        c.drawText(if (far) "tomorrow" else "%d:%02d".format(al.get(Calendar.HOUR).let { if (it == 0) 12 else it }, al.get(Calendar.MINUTE)), lx, ly + t.textSize / 3, t)
    }

    // F — dashed "ghost hour hand" at the alarm time (same shape as the hour hand); far = fainter, longer dashes
    private fun f(c: Canvas, cx: Float, cy: Float, r: Float, now: Calendar, al: Calendar, far: Boolean, pal: Palette) {
        val (ex, ey) = pt(cx, cy, ang(al), r * 0.5f)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT; p.color = pal.accent
        p.strokeWidth = r * 0.055f; p.alpha = if (far) 110 else 220
        p.pathEffect = DashPathEffect(if (far) floatArrayOf(r * 0.02f, r * 0.05f) else floatArrayOf(r * 0.05f, r * 0.02f), 0f)
        c.drawLine(cx, cy, ex, ey, p); p.pathEffect = null; p.alpha = 255
        val (bx, by) = pt(cx, cy, ang(al), r * 0.94f); Glyphs.bell(c, bx, by, r * 0.085f, pal.accent)
    }

    @Test fun render() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        val variants = listOf(
            "A  short alarm hand · solid / hollow" to ::a,
            "B  arc on the ring, now → alarm · far adds an outer ring" to ::b,
            "C  soft wedge, now → alarm · far shades the whole dial" to ::cW,
            "D  lit ticks, now → alarm · far lights every 5th" to ::d,
            "E  bezel triangle + time · far hollow + 'tomorrow'" to ::e,
            "F  dashed ghost hour-hand · far fainter, sparse dashes" to ::f,
        )
        val now = Calendar.getInstance().apply { set(2026, 10, 27, 22, 50, 20) }
        val near = (now.clone() as Calendar).apply { add(Calendar.MINUTE, 7 * 60 + 10) }   // 6:00 AM
        val far = (now.clone() as Calendar).apply { add(Calendar.MINUTE, 15 * 60 + 40) }   // 2:30 PM tomorrow
        val cellW = 520; val cellH = 470; val pad = 16; val labelH = 46
        val cols = listOf(Triple(near, false, "within 12 h · day"), Triple(far, false, "12–24 h · day"),
            Triple(near, true, "within 12 h · night"), Triple(far, true, "12–24 h · night"))
        val sheet = Bitmap.createBitmap(pad + cols.size * (cellW + pad), pad + variants.size * (cellH + labelH + pad), Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet); sc.drawColor(0xFF2B2B30.toInt())
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; textSize = 26f; color = 0xFFE8E8EE.toInt() }
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.semibold; textSize = 20f; color = 0xFF9A9AA2.toInt() }
        variants.forEachIndexed { row, (name, fn) ->
            val y0 = pad + row * (cellH + labelH + pad)
            sc.drawText(name, pad.toFloat(), y0 + 30f, title)
            cols.forEachIndexed { col, (al, night, cap) ->
                val x0 = pad + col * (cellW + pad); val yc = y0 + labelH
                val pal = if (night) Palette.night() else Palette.DAY
                p.color = 0xFF000000.toInt(); p.style = Paint.Style.FILL; p.alpha = 255
                sc.drawRect(x0.toFloat(), yc.toFloat(), (x0 + cellW).toFloat(), (yc + cellH).toFloat(), p)
                val cx = x0 + cellW / 2f; val cy = yc + cellH / 2f - 6f; val r = cellH * 0.40f
                AnalogClock.draw(sc, cx, cy, r, now, pal, showSeconds = false) { cv -> fn(cv, cx, cy, r, now, al, al === far, pal) }
                sc.drawText(cap, x0 + 10f, yc + cellH - 10f, sub)
            }
        }
        File("build/previews").mkdirs()
        File("build/previews/alarm-variants.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
