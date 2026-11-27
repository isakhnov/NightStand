package com.nightstand

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CalendarContract
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.nightstand.pages.DisplayData
import com.nightstand.pages.PageContainer
import com.nightstand.pages.PageRegistry
import com.nightstand.pages.PhotosPage
import com.nightstand.pages.WeatherPage
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * The StandBy screen. Shown over the lock screen (showWhenLocked) without
 * unlocking, turns the screen on when launched, keeps it on while it's up.
 * Content is a [PageContainer] of swipeable pages (PageRegistry).
 *
 * Gestures: swipe ←/→ = next/previous view · swipe ↑/↓ = night brightness
 * (on the Photos page: on the clock half; ↓ on the photo hides it by day) ·
 * tap = brighten briefly (night) · double-tap = unlock and close.
 * Closes itself when the charger is pulled or the phone is picked up
 * (WatcherService → StandByState.requestHide).
 */
class NightstandActivity : Activity(), SensorEventListener {

    private lateinit var container: PageContainer
    private val data = DisplayData()
    private lateinit var sensors: SensorManager
    private val main = Handler(Looper.getMainLooper())
    private val nightCtl = NightModeController()
    private var lightSensor: Sensor? = null
    private var boosted = false

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> updateBattery(i)
                android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> refreshAlarm()
                else -> onMinute() // TIME_TICK / TIME_SET / TIMEZONE_CHANGED
            }
        }
    }

    /** Per-second redraw, only while the visible page has a second hand. */
    private val secondTick = object : Runnable {
        override fun run() {
            val page = container.current ?: return
            if (!page.needsSeconds) return
            page.onTick()
            main.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    private val calendarObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshEvents()
    }

    private val onFeedChanged: () -> Unit = { refreshNotifs() }
    private val onAlarmChanged: () -> Unit = { refreshAlarm() }
    private val hide: () -> Unit = { if (!isFinishing) finish() }
    private val endBoost = Runnable { boosted = false; applyBrightness() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        container = PageContainer(this)
        setContentView(container)
        hideSystemBars()
        buildPages()

        container.onTap = { boost() }
        container.onDoubleTap = { unlockAndClose() }
        container.onBrightnessDrag = { delta -> adjustNightBrightness(delta) }
        container.onPageChanged = { page ->
            Prefs.setLastPage(this, page.pageId)
            PageRegistry.all.firstOrNull { it.id == page.pageId }?.let { container.flash(it.title) }
            restartSecondTick()
        }

        sensors = getSystemService(SensorManager::class.java)
        lightSensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor == null) {
            nightCtl.set(NightModeController.nightBySchedule(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
        }

        StandByState.hideRequest = hide
        StandByState.setVisible(true)
        WatcherService.cancelLaunchNotification(this)
        WatcherService.log("Nightstand shown")
    }

    private fun buildPages() {
        val off = Prefs.disabledPages(this)
        val entries = PageRegistry.ordered(this).filter { it.id !in off }.ifEmpty { PageRegistry.all.take(1) }
        container.setPages(entries.map { it.create(this, data) }, Prefs.startPage(this) ?: Prefs.lastPage(this))
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }, if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0)
        if (CalendarSource.isGranted(this)) {
            contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver)
        }
        StandByState.changeListeners += onFeedChanged
        StandByState.alarmListeners += onAlarmChanged
        Prefs.darkness(this).let { nightCtl.enterLux = it.enterLux; nightCtl.exitLux = it.exitLux }
        // 1 sample/s, batched 2 s: night mode reacts over seconds anyway (8 s hold).
        lightSensor?.let { sensors.registerListener(this, it, 1_000_000, 2_000_000) }
        data.showNotifContent = Prefs.showContent(this)
        refreshEvents(); refreshNotifs(); refreshAlarm()
        applyBrightness()
        container.current?.onShown()
        restartSecondTick()
    }

    override fun onStop() {
        unregisterReceiver(tick)
        contentResolver.unregisterContentObserver(calendarObserver)
        StandByState.changeListeners -= onFeedChanged
        StandByState.alarmListeners -= onAlarmChanged
        sensors.unregisterListener(this)
        main.removeCallbacks(secondTick)
        container.current?.onHidden()
        super.onStop()
        // Stopped because the screen went off → stay, so Nightstand is still
        // there when the screen comes back (iPhone behaviour). Stopped because
        // the user unlocked and went elsewhere → close, so the watcher can show
        // it again next time the phone is locked on the stand.
        val screenOff = !getSystemService(PowerManager::class.java).isInteractive
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!screenOff && !locked && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        if (StandByState.hideRequest === hide) StandByState.hideRequest = null
        StandByState.setVisible(false)
        main.removeCallbacksAndMessages(null)
        WatcherService.log("Nightstand closed")
        super.onDestroy()
    }

    /** Memory pressure: drop the photo model's interpreter now (it reloads only if needed). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.nightstand.photos.smart.SmartPhotos.peek()?.trimMemory()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // --- content ------------------------------------------------------------

    private fun onMinute() {
        // Calendar changes arrive via the ContentObserver; this is only a safety net.
        if (Calendar.getInstance().get(Calendar.MINUTE) % 5 == 0) refreshEvents()
        refreshAlarm()
        if (lightSensor == null &&
            nightCtl.set(NightModeController.nightBySchedule(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
        ) applyBrightness()
        container.current?.onTick()
    }

    private fun restartSecondTick() {
        main.removeCallbacks(secondTick)
        main.post(secondTick)
    }

    private fun refreshEvents() { data.events = CalendarSource.upcoming(this); container.refreshAll() }
    private fun refreshNotifs() {
        data.notifs = NotificationFeed.snapshot
        data.timers = NotificationFeed.timers
        container.refreshAll()
        restartSecondTick() // a timer may have started: count down every second
    }

    private fun refreshAlarm() { data.nextAlarm = com.nightstand.clock.Alarms.next(this); container.refreshAll() }

    private fun updateBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        data.batteryPct = if (level >= 0) level * 100 / scale else -1
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        data.charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val limit = BatteryRing.learnLimit(Prefs.chargeLimit(this), data.batteryPct, plugged, status)
        if (limit != Prefs.chargeLimit(this)) { Prefs.setChargeLimit(this, limit); WatcherService.log("charge limit learned: $limit%") }
        container.battery = if (data.batteryPct >= 0) BatteryState(data.batteryPct, plugged, data.charging, limit) else null
        container.refreshAll()
    }

    // --- night mode ---------------------------------------------------------

    private var firstLux = true

    override fun onSensorChanged(e: SensorEvent) {
        val lux = e.values[0]
        val changed = if (firstLux) {
            // Decide immediately on launch instead of waiting out the hold time.
            firstLux = false
            nightCtl.set(lux < nightCtl.enterLux)
        } else nightCtl.onLux(lux, System.currentTimeMillis())
        if (changed) {
            WatcherService.log("night=${nightCtl.night} (lux=$lux)")
            applyBrightness()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private val isNight get() = StandByState.forceNight ?: nightCtl.night

    /** Night state last applied; null until the first decision after launch. */
    private var appliedNight: Boolean? = null

    private fun applyBrightness() {
        if (isNight && appliedNight != true) rollOffPhotosAtNight()
        appliedNight = isNight
        container.palette = if (isNight) Palette.night(Prefs.nightColor(this)) else Palette.DAY
        restartSecondTick() // second hand may be off at night and on by day
        val night = Prefs.nightBrightness(this)
        window.attributes = window.attributes.apply {
            screenBrightness = when {
                !isNight -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                boosted -> minOf(1f, night + 0.3f)
                else -> night
            }
        }
    }

    /**
     * When night begins (or Nightstand opens at night) on the Photos view, move
     * to Clock + weather — or the next non-photo view if weather is off. Only on
     * that transition: swiping back to Photos at night keeps showing photos.
     */
    private fun rollOffPhotosAtNight() {
        if (container.current?.pageId != PhotosPage.ID) return
        val target = listOf(WeatherPage.ID).plus(container.pageIds).firstOrNull { it != PhotosPage.ID && it in container.pageIds }
            ?: return
        if (container.showPage(target)) {
            WatcherService.log("night: Photos → $target")
            restartSecondTick()
        }
    }

    /** Swipe up/down: tune night brightness live and remember it. */
    private fun adjustNightBrightness(delta: Float): String? {
        if (!isNight) return "Brightness follows the system in day mode"
        val v = (Prefs.nightBrightness(this) + delta * 0.6f).coerceIn(Prefs.MIN_NIGHT, 1f)
        Prefs.setNightBrightness(this, v)
        applyBrightness()
        return "Night brightness ${(v * 100).roundToInt()}%"
    }

    private fun boost() {
        if (!isNight) return
        boosted = true
        applyBrightness()
        main.removeCallbacks(endBoost)
        main.postDelayed(endBoost, 6000)
    }

    private fun unlockAndClose() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
            })
        } else finish()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
