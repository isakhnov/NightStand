package com.nightstand.pages

import android.content.Context
import android.view.View
import com.nightstand.Event
import com.nightstand.NotifSummary
import com.nightstand.Palette

enum class DragTarget { BRIGHTNESS, PAGE, NONE }

/** Shared, activity-owned data every page can draw from. */
class DisplayData {
    var events: List<Event> = emptyList()
    var notifs: List<NotifSummary> = emptyList()
    var batteryPct = -1
    var charging = true
    var showNotifContent = false
    var nextAlarm: com.nightstand.clock.NextAlarm? = null
    var timers: List<com.nightstand.clock.RunningTimer> = emptyList()
    /** The timer to feature: soonest running, else paused, else ringing. */
    val mainTimer get() = timers.sortedWith(
        compareBy({ it.state.ordinal }, { it.endsAt.takeIf { e -> e > 0 } ?: Long.MAX_VALUE })
    ).firstOrNull()
    val hasRunningTimer get() = timers.any { it.state != com.nightstand.clock.RunningTimer.State.PAUSED }
}

/**
 * One full-screen view in the Nightstand container. Pages are plain Views
 * that draw themselves; they never handle touch (the container owns swipes,
 * taps and brightness drags), which keeps every page trivial to add.
 *
 * Lifecycle: [onShown]/[onHidden] when it becomes / stops being the visible
 * page (start/stop any sensors, sessions or loaders there), [onTick] once a
 * minute, or once a second while visible if [needsSeconds].
 */
abstract class Page(context: Context, val data: DisplayData) : View(context) {
    abstract val pageId: String

    var palette: Palette = Palette.DAY
        set(v) { field = v; invalidate() }

    open val needsSeconds: Boolean get() = false
    open fun onShown() {}
    open fun onHidden() {}
    open fun onTick() = invalidate()
    /** A tap at (x, y). Return true if the page handled it (a button). */
    open fun onTapAt(x: Float, y: Float): Boolean = false
    /** Data in [data] changed (events, notifications, battery). */
    open fun onDataChanged() = invalidate()

    /**
     * Who gets a vertical drag that started at (x, y): night brightness (the
     * default), the page itself (e.g. swipe a photo away), or nobody.
     * Horizontal drags always change pages; the container decides direction first.
     */
    open fun verticalDragTarget(x: Float, y: Float): DragTarget = DragTarget.BRIGHTNESS
    /** Shown when a vertical drag went to [DragTarget.NONE]. */
    open fun verticalDragHint(): String? = null
    /** Page-owned vertical drag in progress; [dy] px since the finger went down (positive = down). */
    open fun onVerticalDrag(dy: Float) {}
    /** Finger lifted (or cancelled); [vyDp] = vertical velocity in dp/s (positive = down). */
    open fun onVerticalDragEnd(dy: Float, vyDp: Float, cancelled: Boolean) {}

    /** Alarm/timer additions for the clock, honouring the Look settings. */
    protected fun clockExtras(now: Long = System.currentTimeMillis()): com.nightstand.clock.ClockExtras {
        val timers = if (com.nightstand.Prefs.showTimers(context)) data.timers else emptyList()
        return com.nightstand.clock.ClockExtras(data.nextAlarm, timers.let { data.mainTimer?.takeIf { _ -> it.isNotEmpty() } }, timers, now)
    }

    /** Second hand shown now? Off at night by default (power: per-minute redraw). */
    protected val showSecondHand get() = com.nightstand.Prefs.secondHand(context) &&
        (!palette.night || com.nightstand.Prefs.secondHandAtNight(context))

    /** A timer is counting: redraw every second while this page is visible. */
    protected val timerTicking get() = data.hasRunningTimer && com.nightstand.Prefs.showTimers(context)

    protected val density = resources.displayMetrics.density
    protected fun dp(v: Float) = v * density
}

/** Every page the app knows, in default order. Adding a view = one line here. */
object PageRegistry {
    data class Entry(val id: String, val title: String, val create: (Context, DisplayData) -> Page)

    /** All entries in the user's order (Views tab); unknown/new ones appended. */
    fun ordered(c: Context): List<Entry> {
        val order = com.nightstand.Prefs.pageOrder(c)
        return all.sortedBy { e -> order.indexOf(e.id).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    fun title(id: String?) = all.firstOrNull { it.id == id }?.title

    val all = listOf(
        Entry(ClockAgendaPage.ID, "Clock + up next") { c, d -> ClockAgendaPage(c, d) },
        Entry(WeatherPage.ID, "Clock + weather") { c, d -> WeatherPage(c, d) },
        Entry(PhotosPage.ID, "Clock + photos") { c, d -> PhotosPage(c, d) },
        Entry(NowPlayingPage.ID, "Clock + now playing") { c, d -> NowPlayingPage(c, d) },
    )
}
