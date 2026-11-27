package com.nightstand.proto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.nightstand.BatteryState
import com.nightstand.Fonts
import com.nightstand.Palette
import com.nightstand.Prefs
import com.nightstand.clock.NextAlarm
import com.nightstand.pages.DisplayData
import com.nightstand.pages.NowPlayingPage
import com.nightstand.pages.PageContainer
import com.nightstand.pages.PageRegistry
import com.nightstand.pages.PhotosPage
import com.nightstand.weather.Weather
import com.nightstand.weather.WeatherNow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * PROTOTYPE (not in the app): private message badges — app icon + count —
 * in the top-right corner, drawn over the real views on an S26 frame.
 * Placeholder icons stand in for WhatsApp / Signal / Messages.
 * Output: build/previews/message-badges.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xhdpi")
class MessageBadgesPrototypeTest {
    data class App(val color: Int, val glyph: String, val count: Int)
    private val apps = listOf(App(0xFF25D366.toInt(), "W", 3), App(0xFF3A76F0.toInt(), "S", 1), App(0xFF1A73E8.toInt(), "M", 12))

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; textAlign = Paint.Align.CENTER }

    /** Placeholder app icon: coloured disc + letter (real app icons on the phone). */
    private fun icon(c: Canvas, cx: Float, cy: Float, r: Float, a: App, pal: Palette) {
        p.style = Paint.Style.FILL; p.color = if (pal.night) pal.primary else a.color; p.alpha = if (pal.night) 255 else 255
        c.drawCircle(cx, cy, r, p)
        t.color = Color.BLACK; t.textSize = r * 1.15f
        c.drawText(a.glyph, cx, cy + t.textSize * 0.36f, t)
    }

    /** iOS-style count bubble on the icon's top-right edge. */
    private fun badges(c: Canvas, w: Float, d: Float, pal: Palette) {
        val r = 13 * d; val gap = 12 * d
        var cx = w - 26 * d - r * 0.2f
        val cy = 26 * d
        for (a in apps.reversed()) {
            icon(c, cx, cy, r, a, pal)
            val txt = if (a.count > 9) "9+" else a.count.toString()
            t.textSize = 10.5f * d
            val bw = maxOf(16 * d, t.measureText(txt) + 8 * d); val bh = 16 * d
            val bx = cx + r * 0.55f; val by = cy - r * 0.85f
            val rect = RectF(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
            p.style = Paint.Style.FILL
            p.color = pal.background; c.drawRoundRect(RectF(rect.left - 2 * d, rect.top - 2 * d, rect.right + 2 * d, rect.bottom + 2 * d), bh, bh, p)
            p.color = if (pal.night) pal.primary else 0xFFFF3B30.toInt(); c.drawRoundRect(rect, bh / 2, bh / 2, p)
            t.color = if (pal.night) Color.BLACK else Color.WHITE
            c.drawText(txt, bx, by + t.textSize * 0.36f, t)
            cx -= 2 * r + gap
        }
    }

    /**
     * Vertical strip on the right edge, in its own column (views laid out
     * narrower), icons 1.5× larger with the count underneath. Night: outlined
     * icons so apps stay distinguishable in one colour.
     */
    private fun strip(c: Canvas, x0: Float, w: Float, h: Float, d: Float, pal: Palette) {
        // hairline divider
        p.style = Paint.Style.FILL; p.color = if (pal.night) pal.redAlpha(40) else 0xFF1C1C1F.toInt()
        c.drawRect(x0, h * 0.12f, x0 + 1.5f * d, h * 0.88f, p)
        val r = 18 * d; val step = 2 * r + 30 * d
        val cx = x0 + w / 2
        var cy = h / 2 - step * (apps.size - 1) / 2f - 8 * d
        for (a in apps) {
            if (pal.night) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * d; p.color = pal.primary
                c.drawCircle(cx, cy, r, p)
                t.color = pal.primary; t.textSize = r * 1.05f
                c.drawText(a.glyph, cx, cy + t.textSize * 0.36f, t)
            } else icon(c, cx, cy, r, a, pal)
            t.color = if (pal.night) pal.primary else 0xFFF2F2F5.toInt()
            t.textSize = 15 * d
            c.drawText(if (a.count > 99) "99+" else a.count.toString(), cx, cy + r + 20 * d, t)
            cy += step
        }
        p.style = Paint.Style.FILL
    }

    @Test fun renderStrip() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dm = c.resources.displayMetrics; val W = dm.widthPixels; val H = dm.heightPixels; val d = dm.density
        val stripW = (64 * d).toInt()
        val now = System.currentTimeMillis()
        Weather.current = WeatherNow("Cancún", 77, 61, true, 80, 77, true, now)
        Prefs.setClockStyle(c, Prefs.ClockStyle.ANALOG)
        val ids = listOf("clock_weather", "clock_agenda", PhotosPage.ID, NowPlayingPage.ID)
        val frames = mutableListOf<Pair<String, Bitmap>>()
        for (night in listOf(false, true)) for (id in ids) {
            val data = DisplayData().apply {
                events = listOf(com.nightstand.Event("Dentist", now + 3_600_000, now + 5_400_000, false, 0xFF4285F4.toInt()))
                nextAlarm = NextAlarm(now + 8 * 3_600_000L, "com.google.android.deskclock")
            }
            val box = PageContainer(c)
            val page = PageRegistry.all.first { it.id == id }.create(c, data)
            if (page is PhotosPage) {
                val b = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
                Canvas(b).apply {
                    drawPaint(Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, 1200f, 0xFF3B4A8C.toInt(), 0xFFF29E6D.toInt(), android.graphics.Shader.TileMode.CLAMP) })
                    drawCircle(1050f, 640f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE9A8.toInt() })
                    drawRect(0f, 820f, 1600f, 1200f, Paint().apply { color = 0xFF2A2140.toInt() })
                }
                page.showForPreview(b, 60_000)
            }
            if (page is NowPlayingPage) page.player.apply {
                fakeSession = true
                track = com.nightstand.media.NowPlaying.Track("com.spotify.music", "Spotify", "Midnight City", "Sample Artist", null, 243_000)
            }
            box.setPages(listOf(page), null)
            box.palette = if (night) Palette.night() else Palette.DAY
            box.battery = BatteryState(78, true, true, 85)
            // The view gets the screen minus the strip.
            box.measure(View.MeasureSpec.makeMeasureSpec(W - stripW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            box.layout(0, 0, W - stripW, H)
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b); cv.drawColor(Color.BLACK); box.draw(cv)
            strip(cv, (W - stripW).toFloat(), stripW.toFloat(), H.toFloat(), d, box.palette)
            frames += "${PageRegistry.title(id)} · ${if (night) "night" else "day"}" to b
        }
        val sw = W / 2; val sh = H / 2; val g = 20; val lh = 40
        val sheet = Bitmap.createBitmap(g + 2 * (sw + g), g + 4 * (sh + lh + g), Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet); sc.drawColor(0xFF2B2B30.toInt())
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; textSize = 24f; color = 0xFFE8E8EE.toInt() }
        ids.forEachIndexed { row, _ ->
            for (col in 0..1) {
                val (name, b) = frames[col * ids.size + row]
                val x = g + col * (sw + g); val y = g + row * (sh + lh + g)
                sc.drawText(name, x.toFloat(), y + 28f, title)
                sc.drawBitmap(b, null, Rect(x, y + lh, x + sw, y + lh + sh), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        File("build/previews/message-strip.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun render() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dm = c.resources.displayMetrics; val W = dm.widthPixels; val H = dm.heightPixels; val d = dm.density
        val now = System.currentTimeMillis()
        Weather.current = WeatherNow("Cancún", 77, 61, true, 80, 77, true, now)
        Prefs.setClockStyle(c, Prefs.ClockStyle.ANALOG)
        val ids = listOf("clock_weather", "clock_agenda", PhotosPage.ID, NowPlayingPage.ID)
        val frames = mutableListOf<Pair<String, Bitmap>>()
        for (night in listOf(false, true)) for (id in ids) {
            val data = DisplayData().apply {
                events = listOf(com.nightstand.Event("Dentist", now + 3_600_000, now + 5_400_000, false, 0xFF4285F4.toInt()))
                nextAlarm = NextAlarm(now + 8 * 3_600_000L, "com.google.android.deskclock")
            }
            val box = PageContainer(c)
            val page = PageRegistry.all.first { it.id == id }.create(c, data)
            if (page is PhotosPage) {
                val b = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
                Canvas(b).apply {
                    drawPaint(Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, 1200f, 0xFF3B4A8C.toInt(), 0xFFF29E6D.toInt(), android.graphics.Shader.TileMode.CLAMP) })
                    drawCircle(1050f, 640f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE9A8.toInt() })
                    drawRect(0f, 820f, 1600f, 1200f, Paint().apply { color = 0xFF2A2140.toInt() })
                }
                page.showForPreview(b, 60_000)
            }
            if (page is NowPlayingPage) page.player.apply {
                fakeSession = true
                track = com.nightstand.media.NowPlaying.Track("com.spotify.music", "Spotify", "Midnight City", "Sample Artist", null, 243_000)
            }
            box.setPages(listOf(page), null)
            box.palette = if (night) Palette.night() else Palette.DAY
            box.battery = BatteryState(78, true, true, 85)
            box.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            box.layout(0, 0, W, H)
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b); box.draw(cv)
            badges(cv, W.toFloat(), d, box.palette)
            frames += "${PageRegistry.title(id)} · ${if (night) "night" else "day"}" to b
        }
        val sw = W / 2; val sh = H / 2; val g = 20; val lh = 40
        val sheet = Bitmap.createBitmap(g + 2 * (sw + g), g + 4 * (sh + lh + g), Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet); sc.drawColor(0xFF2B2B30.toInt())
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; textSize = 24f; color = 0xFFE8E8EE.toInt() }
        // columns: day | night ; rows: views
        ids.forEachIndexed { row, _ ->
            for (col in 0..1) {
                val (name, b) = frames[col * ids.size + row]
                val x = g + col * (sw + g); val y = g + row * (sh + lh + g)
                sc.drawText(name, x.toFloat(), y + 28f, title)
                sc.drawBitmap(b, null, Rect(x, y + lh, x + sw, y + lh + sh), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        File("build/previews").mkdirs()
        File("build/previews/message-badges.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
