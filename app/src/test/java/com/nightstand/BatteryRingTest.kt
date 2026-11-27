package com.nightstand

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.BatteryManager.BATTERY_STATUS_CHARGING
import android.os.BatteryManager.BATTERY_STATUS_DISCHARGING
import android.os.BatteryManager.BATTERY_STATUS_FULL
import android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BatteryRingTest {
    @Test fun learnsSamsungLimitFromPausedCharging() {
        assertEquals(100, BatteryRing.learnLimit(100, 60, true, BATTERY_STATUS_CHARGING))
        assertEquals(85, BatteryRing.learnLimit(100, 85, true, BATTERY_STATUS_NOT_CHARGING)) // paused at 85 → limit
        assertEquals(85, BatteryRing.learnLimit(85, 40, false, BATTERY_STATUS_DISCHARGING))  // unplugged: keep
        assertEquals(100, BatteryRing.learnLimit(85, 92, true, BATTERY_STATUS_CHARGING))     // limit turned off
        assertEquals(100, BatteryRing.learnLimit(85, 100, true, BATTERY_STATUS_FULL))
        assertEquals(85, BatteryRing.learnLimit(85, 20, true, BATTERY_STATUS_NOT_CHARGING))  // too low to be a limit
    }

    @Test fun fillsGraduallyAndSolidAtLimit() {
        assertEquals(0f, BatteryRing.fillFraction(BatteryState(50, true, true, 85)), 0f)
        val near = BatteryRing.fillFraction(BatteryState(80, true, true, 85))
        assertTrue(near > 0f && near < 1f)
        assertEquals(1f, BatteryRing.fillFraction(BatteryState(85, true, false, 85)), 0f)
        assertEquals(1f, BatteryRing.fillFraction(BatteryState(100, true, false, 100)), 0f)
    }

    @Test fun hideAbove95() {
        assertTrue(BatteryRing.visible(BatteryState(96, true, false, 100), hideAbove95 = false))
        assertTrue(!BatteryRing.visible(BatteryState(96, true, false, 100), hideAbove95 = true))
        assertTrue(BatteryRing.visible(BatteryState(95, true, true, 100), hideAbove95 = true)) // "above" 95: 95 still shows
    }

    /** Review sheet: build/previews/battery-ring.png */
    @Test fun render() {
        val cases = listOf(
            "35% charging" to BatteryState(35, true, true, 85),
            "78% charging" to BatteryState(78, true, true, 85),
            "83% charging" to BatteryState(83, true, true, 85),
            "85% at limit" to BatteryState(85, true, false, 85),
            "100% no limit" to BatteryState(100, true, false, 100),
            "62% unplugged" to BatteryState(62, false, false, 85),
        )
        val cell = 220; val sheet = Bitmap.createBitmap(cell * cases.size, cell * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(0xFF000000.toInt())
        val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; color = 0xFF9A9AA2.toInt(); textAlign = Paint.Align.CENTER }
        for ((row, pal) in listOf(Palette.DAY, Palette.night()).withIndex()) cases.forEachIndexed { i, (name, s) ->
            BatteryRing.draw(c, i * cell + cell / 2f, row * cell + cell / 2f - 14, 40f, s, pal)
            c.drawText(name, i * cell + cell / 2f, row * cell + cell - 22f, t)
        }
        File("build/previews").mkdirs()
        File("build/previews/battery-ring.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Full S26 frames with the ring in place: build/previews/battery-ring-placement.png */
    @Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xhdpi")
    @Test fun placement() {
        val c = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val dm = c.resources.displayMetrics; val w = dm.widthPixels; val h = dm.heightPixels
        val shots = mutableListOf<Bitmap>()
        for ((style, night) in listOf(Prefs.ClockStyle.ANALOG to false, Prefs.ClockStyle.DIGITAL to true)) {
            Prefs.setClockStyle(c, style)
            for (id in listOf(com.nightstand.pages.WeatherPage.ID, com.nightstand.pages.PhotosPage.ID, com.nightstand.pages.ClockAgendaPage.ID)) {
                val box = com.nightstand.pages.PageContainer(c)
                val data = com.nightstand.pages.DisplayData()
                box.setPages(listOf(com.nightstand.pages.PageRegistry.all.first { it.id == id }.create(c, data)), null)
                box.palette = if (night) Palette.night() else Palette.DAY
                box.battery = BatteryState(78, true, true, 85)
                box.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, 1 shl 30), android.view.View.MeasureSpec.makeMeasureSpec(h, 1 shl 30))
                box.layout(0, 0, w, h)
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); box.draw(Canvas(b)); shots += b
            }
        }
        val sw = w / 2; val sh = h / 2
        val sheet = Bitmap.createBitmap(sw * 3 + 40, sh * 2 + 30, Bitmap.Config.ARGB_8888)
        val cv = Canvas(sheet); cv.drawColor(0xFF2B2B30.toInt())
        shots.forEachIndexed { i, b ->
            val x = 10 + (i % 3) * (sw + 10); val y = 10 + (i / 3) * (sh + 10)
            cv.drawBitmap(b, null, android.graphics.Rect(x, y, x + sw, y + sh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        File("build/previews/battery-ring-placement.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
