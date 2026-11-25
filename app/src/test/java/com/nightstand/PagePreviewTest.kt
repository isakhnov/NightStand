package com.nightstand

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.nightstand.media.NowPlaying
import com.nightstand.pages.DisplayData
import com.nightstand.pages.NowPlayingPage
import com.nightstand.pages.Page
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
 * Not an assertion test: renders every page, day and night, with sample data
 * as PNGs in app/build/previews at S26 Ultra landscape size (~915×412 dp).
 * Run: ./gradlew testDebugUnitTest --tests com.nightstand.PagePreviewTest
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xhdpi")
class PagePreviewTest {
    @Test fun renderAll() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = System.currentTimeMillis()
        val data = DisplayData().apply {
            events = listOf(
                Event("Dentist", now + 3_600_000, now + 5_400_000, false, 0xFF4285F4.toInt()),
                Event("Pick up kids", now + 7_200_000, now + 9_000_000, false, 0xFF0F9D58.toInt()),
            )
            notifs = listOf(NotifSummary("com.whatsapp", "WhatsApp", 3, "Alex", "Alex", now))
            batteryPct = 87
            nextAlarm = com.nightstand.clock.NextAlarm(now + (6 * 60 + 49) * 60_000L, "com.sec.android.app.clockpackage")
            timers = listOf(com.nightstand.clock.RunningTimer(com.nightstand.clock.RunningTimer.State.RUNNING, endsAt = now + 299_000, label = "Pasta"))
        }
        Weather.current = WeatherNow("Cancún", 77, 61, true, 80, 77, true, now)
        val out = File("build/previews").apply { mkdirs() }
        for (night in listOf(false, true)) for (style in Prefs.ClockStyle.values()) {
            Prefs.setClockStyle(ctx, style)
            for (e in PageRegistry.all) {
                val page = e.create(ctx, data)
                page.palette = if (night) Palette.night() else Palette.DAY
                when (page) {
                    is PhotosPage -> page.showForPreview(samplePhoto(), 6_000)
                    is NowPlayingPage -> page.player.apply {
                        fakeSession = true
                        track = NowPlaying.Track("com.spotify.music", "Spotify", "Midnight City", "Sample Artist", samplePhoto(600, 600), 243_000)
                    }
                }
                render(page, File(out, "${e.id}-${style.name.lowercase()}-${if (night) "night" else "day"}.png"))
            }
        }
    }

    private fun render(v: Page, f: File) {
        val dm = v.resources.displayMetrics
        val w = (915 * dm.density).toInt(); val h = (412 * dm.density).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun samplePhoto(w: Int = 1600, h: Int = 1200): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF3B4A8C.toInt(), 0xFFF29E6D.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(w * 0.65f, h * 0.55f, h * 0.12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE9A8.toInt() })
        val hill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2140.toInt() }
        c.drawRect(0f, h * 0.68f, w.toFloat(), h.toFloat(), hill)
        c.drawCircle(w * 0.2f, h * 1.1f, h * 0.55f, hill)
        return b
    }
}
