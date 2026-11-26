package com.nightstand.clock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Learns the user's clock alarms (weekday + time) whenever Android reports
 * one, so they can be shown as "expected" while a Samsung Routines /
 * Bedtime entry holds Android's single next-alarm slot (seen on the S26:
 * routines at 9:30 PM and 4:30 AM hide a 7:30 AM Google Clock alarm).
 *
 * Self-correcting: Android always reports the EARLIEST alarm, so any learned
 * occurrence earlier than what Android reports right now cannot exist any
 * more (alarm turned off or moved) and is dropped. Patterns not seen for
 * 8 days are dropped too. The pure logic is unit-tested.
 */
object AlarmPatterns {
    data class Pattern(val dow: Int, val hour: Int, val minute: Int, val pkg: String, val lastSeen: Long)

    private const val MAX_AGE = 8 * 24 * 3_600_000L
    /** Samsung may report an alarm a few minutes early; don't treat that as a contradiction. */
    private const val TOLERANCE = 6 * 60_000L

    // --- pure ---------------------------------------------------------------

    fun record(list: List<Pattern>, a: NextAlarm, now: Long): List<Pattern> {
        val c = Calendar.getInstance().apply { timeInMillis = a.triggerAt }
        val p = Pattern(c.get(Calendar.DAY_OF_WEEK), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), a.sourcePkg ?: "", now)
        return list.filterNot { it.dow == p.dow && it.hour == p.hour && it.minute == p.minute } + p
    }

    /** Next time [p] rings after [now]. */
    fun occurrence(p: Pattern, now: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, p.hour); set(Calendar.MINUTE, p.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (c.get(Calendar.DAY_OF_WEEK) != p.dow || c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /**
     * Drop stale patterns and any whose next occurrence comes before what
     * Android reports as the next alarm now ([systemNext], any owner).
     */
    fun prune(list: List<Pattern>, systemNext: Long?, now: Long): List<Pattern> = list.filter { p ->
        now - p.lastSeen <= MAX_AGE &&
            (systemNext == null || occurrence(p, now) >= systemNext - TOLERANCE)
    }

    /** Soonest learned alarm within [window], strictly after the internal holder's time. */
    fun expected(list: List<Pattern>, now: Long, window: Long, after: Long?): NextAlarm? =
        list.map { NextAlarm(occurrence(it, now), it.pkg, expected = true) }
            .filter { it.triggerAt - now <= window && (after == null || it.triggerAt > after) }
            .minByOrNull { it.triggerAt }

    // --- storage ------------------------------------------------------------

    private fun sp(c: Context) = c.getSharedPreferences("alarm_patterns", Context.MODE_PRIVATE)

    fun load(c: Context): List<Pattern> = runCatching {
        val a = JSONArray(sp(c).getString("list", "[]"))
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Pattern(it.getInt("d"), it.getInt("h"), it.getInt("m"), it.getString("p"), it.getLong("s")) }
        }
    }.getOrDefault(emptyList())

    fun save(c: Context, list: List<Pattern>) = sp(c).edit().putString("list", JSONArray(list.map {
        JSONObject().put("d", it.dow).put("h", it.hour).put("m", it.minute).put("p", it.pkg).put("s", it.lastSeen)
    }).toString()).apply()

    fun clear(c: Context) = sp(c).edit().clear().apply()
}
