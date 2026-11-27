package com.nightstand

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.nightstand.clock.NextAlarm
import com.nightstand.pages.DisplayData
import com.nightstand.pages.PageContainer
import com.nightstand.pages.PageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.SensorEventBuilder

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xhdpi")
class RobustnessAndroidTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()
    private fun sp(name: String) = c.getSharedPreferences(name, Context.MODE_PRIVATE)

    // --- settings survive garbage (e.g. an enum renamed in a later version) ---
    @Test fun prefs_corruptValuesFallBackToDefaults() {
        sp("nightstand").edit().putString("clockStyle", "SUNDIAL").putString("nightColor", "PURPLE")
            .putString("darkness", "PITCH").putString("photoSource", "CLOUD").putString("pageOrder", ",,nope,clock_photos,")
            .commit()
        assertEquals(Prefs.ClockStyle.ANALOG, Prefs.clockStyle(c))
        assertEquals(NightColor.PURE, Prefs.nightColor(c))
        assertEquals(Prefs.Darkness.DARK, Prefs.darkness(c))
        assertEquals(Prefs.PhotoSource.ALL, Prefs.photoSource(c))
        val ordered = PageRegistry.ordered(c).map { it.id }
        assertEquals("clock_photos", ordered.first())
        assertEquals(PageRegistry.all.size, ordered.size) // nothing lost, nothing invented
    }

    @Test fun prefs_nightBrightnessClamped() {
        Prefs.setNightBrightness(c, 5f); assertEquals(1f, Prefs.nightBrightness(c), 0f)
        Prefs.setNightBrightness(c, -1f); assertEquals(Prefs.MIN_NIGHT, Prefs.nightBrightness(c), 0f)
    }

    // --- gestures on the display ---
    private fun touch(v: PageContainer, action: Int, x: Float, y: Float, t: Long) =
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    @Test fun container_swipeCyclesAndWraps_tapsAndBrightness() {
        val v = PageContainer(c)
        val data = DisplayData()
        v.setPages(PageRegistry.all.take(3).map { it.create(c, data) }, null)
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(1830, 1 shl 30), android.view.View.MeasureSpec.makeMeasureSpec(824, 1 shl 30))
        v.layout(0, 0, 1830, 824)
        var taps = 0; var doubles = 0; var bright = 0f
        v.onTap = { taps++ }; v.onDoubleTap = { doubles++ }; v.onBrightnessDrag = { bright += it; null }

        // swipe right→left: next page
        touch(v, MotionEvent.ACTION_DOWN, 1500f, 400f, 0); touch(v, MotionEvent.ACTION_MOVE, 900f, 405f, 50)
        touch(v, MotionEvent.ACTION_UP, 300f, 410f, 100)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(1, v.index)
        // swipe left→right twice: back past the first page, wraps to the last
        repeat(2) {
            touch(v, MotionEvent.ACTION_DOWN, 300f, 400f, 0); touch(v, MotionEvent.ACTION_MOVE, 900f, 405f, 50)
            touch(v, MotionEvent.ACTION_UP, 1500f, 410f, 100)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        }
        assertEquals(2, v.index)

        // vertical drag up: brightness up
        touch(v, MotionEvent.ACTION_DOWN, 900f, 700f, 0); touch(v, MotionEvent.ACTION_MOVE, 905f, 400f, 50)
        touch(v, MotionEvent.ACTION_UP, 905f, 300f, 100)
        assertTrue(bright > 0)

        // single tap (after double-tap timeout) and double tap
        touch(v, MotionEvent.ACTION_DOWN, 900f, 400f, 10_000); touch(v, MotionEvent.ACTION_UP, 900f, 400f, 10_030)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(1, taps)
        touch(v, MotionEvent.ACTION_DOWN, 900f, 400f, 20_000); touch(v, MotionEvent.ACTION_UP, 900f, 400f, 20_030)
        touch(v, MotionEvent.ACTION_DOWN, 900f, 400f, 20_060); touch(v, MotionEvent.ACTION_UP, 900f, 400f, 20_090)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(1, doubles); assertEquals(1, taps)
    }

    // --- the whole trigger path: plugged + landscape + locked → launch alert ---
    @Test fun watcher_launchesWhenPluggedLandscapeLocked() {
        shadowOf(c.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        shadowOf(c.getSystemService(SensorManager::class.java))
            .addSensor(org.robolectric.shadows.ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        val controller = Robolectric.buildService(WatcherService::class.java).create()
        val app = c as Application
        app.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        shadowOf(Looper.getMainLooper()).idle()
        val sm = c.getSystemService(SensorManager::class.java)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        assertNotNull("accelerometer registered while plugged", accel)
        var t = 0L
        repeat(20) {
            val e = SensorEventBuilder.newBuilder().setSensor(accel).setTimestamp(t * 1_000_000)
                .setValues(floatArrayOf(9.4f, 0.2f, 2.8f)).build()
            shadowOf(sm).sendSensorEventToListeners(e); t += 200
        }
        shadowOf(Looper.getMainLooper()).idle()
        val nm = c.getSystemService(NotificationManager::class.java)
        val launch = shadowOf(nm).allNotifications.firstOrNull { it.channelId == WatcherService.CH_LAUNCH }
        assertNotNull("launch alert posted", launch)
        controller.destroy()
    }

    // --- display screen lifecycle ---
    @Test fun nightstand_everyStartPageAndNightModeOpensAndCloses() {
        for (e in PageRegistry.all) for (night in listOf(true, false)) {
            Prefs.setStartPage(c, e.id)
            StandByState.forceNight = night
            val ctl = Robolectric.buildActivity(NightstandActivity::class.java).setup()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            StandByState.requestHide() // charger pulled
            assertTrue("${e.id} night=$night finishes on hide", ctl.get().isFinishing)
            ctl.pause().stop().destroy()
        }
        StandByState.forceNight = null
        Prefs.setStartPage(c, null)
    }

    @Test fun weather_parsesRealShapeAndRounds() {
        val j = org.json.JSONObject("""{"current":{"temperature_2m":78.6,"weather_code":61,"is_day":0},
            "daily":{"temperature_2m_max":[80.4],"temperature_2m_min":[76.5]}}""")
        val w = com.nightstand.weather.Weather.parseOpenMeteo(j, true, "Cancún", 1L)
        assertEquals(79, w.temp); assertEquals(80, w.high); assertEquals(77, w.low)
        org.junit.Assert.assertFalse(w.isDay); assertEquals(61, w.code)
    }

    @Test(expected = Exception::class)
    fun weather_malformedThrows_soRefreshCanCatchIt() {
        com.nightstand.weather.Weather.parseOpenMeteo(org.json.JSONObject("""{"current":{}}"""), true, null, 1L)
    }

    // --- night rolls Photos off to Weather; swiping back keeps Photos ---
    @Test fun night_rollsPhotosToWeather_onceAndDoesNotSaveIt() {
        Prefs.setStartPage(c, com.nightstand.pages.PhotosPage.ID)
        Prefs.setLastPage(c, com.nightstand.pages.PhotosPage.ID)
        fun currentId(a: NightstandActivity): String? {
            val f = NightstandActivity::class.java.getDeclaredField("container").apply { isAccessible = true }
            return (f.get(a) as PageContainer).current?.pageId
        }
        // Day: stays on Photos
        StandByState.forceNight = false
        Robolectric.buildActivity(NightstandActivity::class.java).setup().also { ctl ->
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(com.nightstand.pages.PhotosPage.ID, currentId(ctl.get()))
            ctl.pause().stop().destroy()
        }
        // Night: moves to Weather, and "last used" is not overwritten
        StandByState.forceNight = true
        Robolectric.buildActivity(NightstandActivity::class.java).setup().also { ctl ->
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(com.nightstand.pages.WeatherPage.ID, currentId(ctl.get()))
            assertEquals(com.nightstand.pages.PhotosPage.ID, Prefs.lastPage(c))
            ctl.pause().stop().destroy()
        }
        StandByState.forceNight = null
        Prefs.setStartPage(c, null)
    }

    // --- crash capture ---
    @Test fun crashReporter_savesLastCrash() {
        CrashReporter.clear(c)
        CrashReporter.install(c)
        val h = Thread.getDefaultUncaughtExceptionHandler()!!
        try { h.uncaughtException(Thread.currentThread(), IllegalStateException("boom")) } catch (_: Throwable) {}
        val r = CrashReporter.last(c)
        assertNotNull(r); assertTrue(r!!.contains("boom"))
    }

}
