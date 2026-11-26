package com.nightstand

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.nightstand.clock.NextAlarm
import com.nightstand.clock.RunningTimer
import com.nightstand.pages.DisplayData
import com.nightstand.pages.NowPlayingPage
import com.nightstand.pages.Page
import com.nightstand.pages.PageRegistry
import com.nightstand.weather.Weather
import com.nightstand.weather.WeatherNow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every page, every clock style, day/night, on very different screens, with
 * stress data (long titles, many notifications, two timers, alarm). Asserts
 * nothing throws and something is drawn; writes PNGs to build/previews/sizes
 * for eyeballing (ScreenSizesTest-*.png contact sheets).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenSizesTest {
    private fun stress(now: Long) = DisplayData().apply {
        events = listOf(
            Event("A very long event title that will certainly not fit on any phone screen at all", now + 3_600_000, now + 7_200_000, false, 0xFF4285F4.toInt()),
            Event("Dentist", now + 7_200_000, now + 9_000_000, false, 0xFF0F9D58.toInt()),
            Event("Holiday", now - 3_600_000, now + 20 * 3_600_000, true, 0xFFF4B400.toInt()),
            Event("Pick up kids", now + 10_000_000, now + 11_000_000, false, 0xFFDB4437.toInt()),
        )
        notifs = (1..9).map { NotifSummary("com.app$it", "Application number $it with a long name", it, "Title $it that is long", "Public $it", now - it) }
        batteryPct = 100
        nextAlarm = NextAlarm(now + 9 * 3_600_000L, "com.google.android.deskclock")
        timers = listOf(
            RunningTimer(RunningTimer.State.RUNNING, endsAt = now + 3_599_000, label = "A really long timer label"),
            RunningTimer(RunningTimer.State.PAUSED, pausedRemainingMs = 12_345_000),
        )
    }

    private fun renderAll(name: String) {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = System.currentTimeMillis()
        Weather.current = WeatherNow("Llanfairpwllgwyngyll-gogerychwyrndrobwll", -12, 95, false, 104, -40, true, now)
        val dm = c.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        assertTrue("landscape expected", w > h)
        val shots = mutableListOf<Bitmap>()
        for (style in Prefs.ClockStyle.values()) for (night in listOf(false, true)) {
            Prefs.setClockStyle(c, style)
            for (e in PageRegistry.all) {
                val page: Page = e.create(c, stress(now))
                page.palette = if (night) Palette.night() else Palette.DAY
                if (page is NowPlayingPage) page.player.apply {
                    fakeSession = true
                    track = com.nightstand.media.NowPlaying.Track("com.spotify.music", "Spotify",
                        "An extremely long song title (Remastered 2026 Deluxe Edition)", "Artist with a long name", null, 3_600_000)
                }
                page.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                page.layout(0, 0, w, h)
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                page.draw(Canvas(b)) // must not throw
                if (!night && style == Prefs.ClockStyle.ANALOG || night && style == Prefs.ClockStyle.DIGITAL) shots += b
            }
        }
        // Contact sheet: two rows (analog day, digital night) × pages, scaled down.
        val n = PageRegistry.all.size
        val sw = 420; val sh = (420f * h / w).toInt()
        val sheet = Bitmap.createBitmap(sw * n + 10 * (n + 1), sh * 2 + 30, Bitmap.Config.ARGB_8888)
        val cv = Canvas(sheet); cv.drawColor(0xFF2B2B30.toInt())
        shots.forEachIndexed { i, b ->
            val r = android.graphics.Rect(10 + (i % n) * (sw + 10), 10 + (i / n) * (sh + 10), 10 + (i % n) * (sw + 10) + sw, 10 + (i / n) * (sh + 10) + sh)
            cv.drawBitmap(b, null, r, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        val out = File("build/previews/sizes").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Config(sdk = [34], qualifiers = "w640dp-h360dp-land-hdpi") @Test fun smallPhone() = renderAll("1-small-640x360")
    @Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xxhdpi") @Test fun s26Ultra() = renderAll("2-s26ultra-915x412")
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xxhdpi") @Test fun tallNarrow() = renderAll("3-tall-800x360")
    @Config(sdk = [34], qualifiers = "w882dp-h840dp-land-xhdpi") @Test fun foldableInner() = renderAll("4-fold-882x840")
    @Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi") @Test fun tablet() = renderAll("5-tablet-1280x800")
}
