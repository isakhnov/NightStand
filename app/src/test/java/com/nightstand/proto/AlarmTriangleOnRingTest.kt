package com.nightstand.proto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
 * PROTOTYPE (not in the app): G1 with the triangle ON the tick ring, drawn on
 * full S26 frames (915×412 dp) with the real Clock-split geometry:
 * pad 14 dp, r = min(h/2 − pad, 0.46w/2 − pad), cx = pad + r, burn-in ±8 dp.
 * Output: build/previews/alarm-triangle-on-ring.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xhdpi")
class AlarmTriangleOnRingTest {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun pt(cx: Float, cy: Float, deg: Float, rr: Float) =
        Math.toRadians(deg.toDouble()).let { (cx + sin(it) * rr).toFloat() to (cy - cos(it) * rr).toFloat() }

    /** Triangle sitting in the tick band: base on the ring, tip pointing to the centre. */
    private fun triangle(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, hollow: Boolean, pal: Palette) {
        val half = 5.2f                          // half base angle → ~0.18 r wide
        val b1 = pt(cx, cy, deg - half, r * 1.0f); val b2 = pt(cx, cy, deg + half, r * 1.0f)
        val tip = pt(cx, cy, deg, r * 0.855f)    // stops just outside the numerals (≈0.86 r)
        val path = Path().apply { moveTo(b1.first, b1.second); lineTo(b2.first, b2.second); lineTo(tip.first, tip.second); close() }
        // Clear the ticks underneath so a hollow triangle reads cleanly.
        p.style = Paint.Style.FILL; p.color = pal.background; p.alpha = 255
        val k = 1.25f
        val kb1 = pt(cx, cy, deg - half * k, r * 1.02f); val kb2 = pt(cx, cy, deg + half * k, r * 1.02f); val kt = pt(cx, cy, deg, r * 0.835f)
        c.drawPath(Path().apply { moveTo(kb1.first, kb1.second); lineTo(kb2.first, kb2.second); lineTo(kt.first, kt.second); close() }, p)
        p.color = pal.accent; p.strokeJoin = Paint.Join.ROUND
        if (hollow) { p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.018f } else p.style = Paint.Style.FILL
        c.drawPath(path, p)
    }

    /** Today's readout: bell + time, on the side away from the hour hand. */
    private fun readout(c: Canvas, cx: Float, cy: Float, r: Float, now: Calendar, al: Calendar, pal: Palette) {
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; textSize = r * 0.095f; color = pal.secondary }
        val hourDeg = (now.get(Calendar.HOUR) + now.get(Calendar.MINUTE) / 60f) * 30f
        val handUp = cos(Math.toRadians(hourDeg.toDouble())) > 0
        val h = al.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val text = "%d:%02d %s".format(h, al.get(Calendar.MINUTE), if (al.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM")
        val glyph = small.textSize * 0.9f
        val w = glyph * 1.25f + small.measureText(text); val left = cx - w / 2
        val y = if (handUp) cy + r * 0.26f + small.textSize else cy - r * 0.26f
        Glyphs.bell(c, left + glyph / 2, y - small.textSize * 0.36f, glyph, small.color)
        c.drawText(text, left + glyph * 1.25f, y, small)
    }

    @Test fun render() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dm = ctx.resources.displayMetrics
        val W = dm.widthPixels; val H = dm.heightPixels; val d = dm.density
        val pad = 14 * d
        val r = minOf(H / 2f - pad, W * 0.46f / 2 - pad)
        fun cal(h: Int, m: Int, plusDay: Boolean = false) = Calendar.getInstance().apply {
            set(2026, 10, 27, h, m, 0); if (plusDay) add(Calendar.DAY_OF_YEAR, 1)
        }
        val now = cal(22, 50)
        data class Case(val title: String, val alarm: Calendar, val far: Boolean, val night: Boolean, val sx: Float, val sy: Float)
        val cases = listOf(
            Case("Within 12 h · 6:00 AM · day", cal(6, 0, true), false, false, 0f, 0f),
            Case("12–24 h · 2:30 PM tomorrow · day", cal(14, 30, true), true, false, 0f, 0f),
            Case("Within 12 h · 6:00 AM · night", cal(6, 0, true), false, true, 0f, 0f),
            Case("12–24 h · 2:30 PM tomorrow · night", cal(14, 30, true), true, true, 0f, 0f),
            Case("Edge check: alarm 12:00 at the top, burn-in −8 dp up", cal(12, 0, true), true, false, 0f, -8 * d),
            Case("Edge check: alarm 9:00 at the left, burn-in −8 dp left", cal(9, 0, true), false, true, -8 * d, 0f),
        )
        val scale = 0.5f; val gap = 24; val labelH = 44
        val cw = (W * scale).toInt(); val ch = (H * scale).toInt()
        val sheet = Bitmap.createBitmap(gap + 2 * (cw + gap), gap + 3 * (ch + labelH + gap), Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet); sc.drawColor(0xFF2B2B30.toInt())
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; textSize = 24f; color = 0xFFE8E8EE.toInt() }
        cases.forEachIndexed { i, cs ->
            val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(frame); c.drawColor(0xFF000000.toInt())
            val pal = if (cs.night) Palette.night() else Palette.DAY
            c.save(); c.translate(cs.sx, cs.sy)
            val cx = pad + r; val cy = H / 2f
            val deg = (cs.alarm.get(Calendar.HOUR) + cs.alarm.get(Calendar.MINUTE) / 60f) * 30f
            AnalogClock.draw(c, cx, cy, r, now, pal, showSeconds = false) { cv ->
                triangle(cv, cx, cy, r, deg, cs.far, pal)
                readout(cv, cx, cy, r, now, cs.alarm, pal)
            }
            // right pane placeholder so the frame reads like the real page
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.heavy; textSize = H * 0.4f; color = pal.primary }
            c.drawText("77°", pad + 2 * r + 60 * d, H * 0.62f, tp)
            c.restore()
            val col = i % 2; val row = i / 2
            val x0 = gap + col * (cw + gap); val y0 = gap + row * (ch + labelH + gap)
            sc.drawText(cs.title, x0.toFloat(), y0 + 30f, title)
            sc.drawBitmap(frame, null, android.graphics.Rect(x0, y0 + labelH, x0 + cw, y0 + labelH + ch), Paint(Paint.FILTER_BITMAP_FLAG))
            // screen edge outline
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFF55555C.toInt()
            sc.drawRect(x0.toFloat(), (y0 + labelH).toFloat(), (x0 + cw).toFloat(), (y0 + labelH + ch).toFloat(), p)
        }
        File("build/previews").mkdirs()
        File("build/previews/alarm-triangle-on-ring.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
