package com.nightstand

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.nightstand.clock.ClockOverlay
import com.nightstand.clock.NextAlarm
import com.nightstand.pages.BigClockPage
import com.nightstand.pages.DisplayData
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xhdpi")
class AlarmHandTest {
    @Test fun denseWithin12h_sparseBeyond() {
        val (gNear, _) = ClockOverlay.alarmHandDots(9 * 3_600_000L)
        val (gFar, _) = ClockOverlay.alarmHandDots(15 * 3_600_000L)
        assertTrue(gFar > gNear * 2)
        assertTrue(ClockOverlay.alarmHandDots(12 * 3_600_000L).first == gNear) // exactly 12 h counts as near
    }

    /** Renders both cases for review: build/previews/alarm-hand-{near,far}-{day,night}.png */
    @Test fun render() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.setClockStyle(c, Prefs.ClockStyle.ANALOG)
        val now = System.currentTimeMillis()
        for ((name, h) in listOf("near" to 7.3, "far" to 15.5)) for (night in listOf(false, true)) {
            val data = DisplayData().apply { nextAlarm = NextAlarm(now + (h * 3_600_000).toLong(), "com.google.android.deskclock") }
            val page = BigClockPage(c, data)
            page.palette = if (night) Palette.night() else Palette.DAY
            val dm = c.resources.displayMetrics
            val w = dm.widthPixels; val hh = dm.heightPixels
            page.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
            page.layout(0, 0, w, hh)
            val b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
            page.draw(Canvas(b))
            File("build/previews").mkdirs()
            File("build/previews/alarm-hand-$name-${if (night) "night" else "day"}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
