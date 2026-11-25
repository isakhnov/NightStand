package com.nightstand

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.Calendar
import java.util.TimeZone

data class Event(
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
)

/**
 * Upcoming events from Android's shared calendar provider, restricted to
 * Google accounts (Google Calendar). Outlook/Exchange and local Samsung
 * calendars are deliberately excluded.
 */
object CalendarSource {
    const val GOOGLE_ACCOUNT_TYPE = "com.google"

    fun isGranted(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Events still running or starting before the end of tomorrow. */
    fun upcoming(c: Context, now: Long = System.currentTimeMillis(), limit: Int = 4): List<Event> {
        if (!isGranted(c)) return emptyList()
        val endOfTomorrow = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 2)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // All-day instances are stored in UTC midnight-to-midnight; widen the
        // query window so today's all-day events aren't missed in negative-UTC zones.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now - 86_400_000L)
            android.content.ContentUris.appendId(it, endOfTomorrow)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val ids = googleCalendarIds(c).ifEmpty { return emptyList() }.joinToString(",")
        val out = mutableListOf<Event>()
        try {
            c.contentResolver.query(
                uri, proj,
                // Google Calendar accounts only (user's choice: no Outlook/work sources).
                "${CalendarContract.Instances.VISIBLE}=1 AND ${CalendarContract.Instances.CALENDAR_ID} IN ($ids)",
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val allDay = cur.getInt(3) == 1
                    var begin = cur.getLong(1)
                    var end = cur.getLong(2)
                    if (allDay) { begin = utcDayToLocal(begin); end = utcDayToLocal(end) }
                    if (end <= now) continue
                    out += Event(cur.getString(0) ?: "(no title)", begin, end, allDay, cur.getInt(4))
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        // Timed events first by start, all-day ones of today float to the top.
        return out.sortedWith(compareBy({ !it.allDay }, { it.begin })).take(limit)
    }

    data class Cal(val id: Long, val name: String, val account: String, val color: Int)

    /** All Google calendars on the phone (for the Views tab). */
    fun googleCalendars(c: Context): List<Cal> {
        if (!isGranted(c)) return emptyList()
        val out = mutableListOf<Cal>()
        try {
            c.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
                ),
                "${CalendarContract.Calendars.ACCOUNT_TYPE}=?", arrayOf(GOOGLE_ACCOUNT_TYPE), null
            )?.use { cur ->
                while (cur.moveToNext()) out += Cal(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: "", cur.getInt(3))
            }
        } catch (_: SecurityException) {}
        return out
    }

    private fun googleCalendarIds(c: Context): List<Long> {
        val off = com.nightstand.Prefs.calendarsOff(c)
        return googleCalendars(c).map { it.id }.filter { it.toString() !in off }
    }

    private fun utcDayToLocal(utcMidnight: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
}
