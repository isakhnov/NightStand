package com.nightstand.pages

import android.content.Context
import android.view.View
import com.nightstand.Event
import com.nightstand.NotifSummary
import com.nightstand.Palette

/** Shared, activity-owned data every page can draw from. */
class DisplayData {
    var events: List<Event> = emptyList()
    var notifs: List<NotifSummary> = emptyList()
    var batteryPct = -1
    var charging = true
    var showNotifContent = false
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
        Entry(BigClockPage.ID, "Big clock") { c, d -> BigClockPage(c, d) },
        Entry(PhotosPage.ID, "Clock + photos") { c, d -> PhotosPage(c, d) },
        Entry(NowPlayingPage.ID, "Clock + now playing") { c, d -> NowPlayingPage(c, d) },
    )
}
