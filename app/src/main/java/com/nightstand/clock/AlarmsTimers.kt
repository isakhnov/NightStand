package com.nightstand.clock

import android.app.AlarmManager
import android.content.Context
import com.nightstand.Prefs

/** The single next alarm Android reports (see docs/views/05-alarms-timers). */
data class NextAlarm(val triggerAt: Long, val sourcePkg: String?)

/** A timer a clock app is showing in its ongoing notification. */
data class RunningTimer(
    val state: State,
    /** RUNNING: wall-clock end time. */
    val endsAt: Long = 0,
    /** PAUSED: time left, frozen. */
    val pausedRemainingMs: Long = 0,
    val label: String? = null,
) {
    enum class State { RUNNING, PAUSED, RINGING }

    fun remainingMs(now: Long): Long = when (state) {
        State.RUNNING -> (endsAt - now).coerceAtLeast(0)
        State.PAUSED -> pausedRemainingMs
        State.RINGING -> 0
    }
}

object ClockApps {
    val PACKAGES = setOf(
        "com.sec.android.app.clockpackage", // Samsung Clock
        "com.google.android.deskclock",     // Google Clock
        "com.android.deskclock",            // AOSP
    )
}

object Alarms {
    const val WINDOW_MS = 24 * 60 * 60_000L

    /**
     * Next alarm if it's set by a clock app and rings within 24 h.
     *
     * UNDER DEVELOPMENT: alarms from other apps (Samsung Calendar, Routines,
     * Tasker also use setAlarmClock and can take the "next alarm" slot).
     * [Prefs.alarmsFromOtherApps] exists but is disabled in the UI and NOT
     * honoured here yet — clock apps only, by the user's decision.
     */
    fun next(c: Context, now: Long = System.currentTimeMillis()): NextAlarm? {
        if (!Prefs.showAlarm(c)) return null
        val info = c.getSystemService(AlarmManager::class.java).nextAlarmClock
        val system = info?.let { NextAlarm(it.triggerTime, it.showIntent?.creatorPackage) }
        return choose(system, AlarmMemory.get(c, now), now)
    }

    /**
     * Android exposes only ONE next alarm. Internal holders (seen on the S26:
     * the 9:30 PM Bedtime action, no owner) are filtered out; then the alarm
     * remembered from the clock app's own "Upcoming alarm" notification is
     * used. A real clock alarm reported by Android always wins. Pure; tested.
     */
    fun choose(system: NextAlarm?, remembered: NextAlarm?, now: Long): NextAlarm? =
        filter(system, now) ?: filter(remembered, now)

    /** The slot holder if it's an internal alarm we hide (bedtime etc.), else null. */
    fun internalHolder(c: Context, now: Long = System.currentTimeMillis()): NextAlarm? {
        val info = c.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        val a = NextAlarm(info.triggerTime, info.showIntent?.creatorPackage)
        return if (a.sourcePkg !in ClockApps.PACKAGES && a.triggerAt > now) a else null
    }

    /**
     * What Android reports, and why it is or isn't shown — for Look and Debug,
     * so "my alarm doesn't appear" can be answered on the phone.
     */
    fun explain(c: Context, now: Long = System.currentTimeMillis()): String {
        val info = c.getSystemService(AlarmManager::class.java).nextAlarmClock
            ?: return "Android reports no upcoming alarm"
        val pkg = info.showIntent?.creatorPackage
        val app = pkg?.let { p ->
            runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() ?: p
        } ?: "unknown app"
        val time = android.text.format.DateFormat.format(
            if (android.text.format.DateFormat.is24HourFormat(c)) "EEE HH:mm" else "EEE h:mm a", info.triggerTime)
        val fallback = filter(AlarmMemory.get(c, now), now)
        val why = when {
            pkg !in ClockApps.PACKAGES && fallback != null ->
                "internal alarm (${pkg ?: "no owner"}) hidden; showing ${alarmLabel(c, fallback)} from the clock's notification"
            pkg == null -> "hidden: set by an app that doesn't identify itself (often a Bedtime/Sleep " +
                "schedule). It holds Android's single next-alarm slot, so your clock alarm appears once it has passed"
            pkg !in ClockApps.PACKAGES -> "hidden: not a clock app ($pkg)"
            info.triggerTime - now > WINDOW_MS -> "hidden: more than 24 h away"
            !Prefs.showAlarm(c) -> "hidden: Next alarm is off"
            else -> "shown"
        }
        return "$time from $app · $why"
    }

    /** Pure rule, unit-tested. */
    fun filter(a: NextAlarm?, now: Long): NextAlarm? {
        if (a == null) return null
        if (a.sourcePkg !in ClockApps.PACKAGES) return null
        if (a.triggerAt < now || a.triggerAt - now > WINDOW_MS) return null
        return a
    }

    private fun alarmLabel(c: Context, a: NextAlarm) = android.text.format.DateFormat.format(
        if (android.text.format.DateFormat.is24HourFormat(c)) "EEE HH:mm" else "EEE h:mm a", a.triggerAt).toString()
}

/**
 * Alarm learned from a clock app's "Upcoming alarm" / "Snoozing until"
 * notification, kept after the notification is swiped away by the user and
 * forgotten when the clock app withdraws it itself (alarm dismissed or rung)
 * or once its time has passed.
 */
object AlarmMemory {
    private fun sp(c: Context) = c.getSharedPreferences("alarm_memory", Context.MODE_PRIVATE)

    fun remember(c: Context, a: NextAlarm) {
        val cur = get(c)
        if (cur == a) return
        sp(c).edit().putLong("at", a.triggerAt).putString("pkg", a.sourcePkg).apply()
        com.nightstand.WatcherService.log("alarm learned from notification: ${java.util.Date(a.triggerAt)} (${a.sourcePkg})")
    }

    fun forget(c: Context, why: String) {
        if (get(c) == null) return
        sp(c).edit().clear().apply()
        com.nightstand.WatcherService.log("alarm memory cleared: $why")
    }

    fun get(c: Context, now: Long = System.currentTimeMillis()): NextAlarm? {
        val at = sp(c).getLong("at", 0)
        if (at <= now) return null
        return NextAlarm(at, sp(c).getString("pkg", null))
    }
}

/**
 * Re-reads the next alarm right after an internal holder (e.g. Bedtime at
 * 9:30 PM) passes, so the real clock alarm shows immediately. Uses a plain
 * inexact wake-up — NOT setAlarmClock, which would itself take the slot.
 */
object AlarmRecheck {
    const val ACTION = "com.nightstand.ALARM_RECHECK"

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = android.app.PendingIntent.getBroadcast(
            c, 42, android.content.Intent(c, AlarmRecheckReceiver::class.java).setAction(ACTION),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        val holder = Alarms.internalHolder(c) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, holder.triggerAt + 15_000, pi)
        com.nightstand.WatcherService.log("recheck scheduled after internal alarm at ${java.util.Date(holder.triggerAt)}")
    }
}

class AlarmRecheckReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: android.content.Intent) {
        com.nightstand.WatcherService.log("internal alarm passed; next alarm now: " + Alarms.explain(c))
        com.nightstand.StandByState.notifyAlarmChanged()
        AlarmRecheck.schedule(c) // a later internal alarm may hold the slot next
    }

}

/**
 * Reads running timers from clock apps' ongoing notifications. Pure (takes
 * plain fields) so it is unit-tested; NotificationFeed extracts the fields.
 *
 * Two shapes are understood:
 *  1. Chronometer countdown (when + android.chronometerCountDown): RUNNING,
 *     ends at `when`. This is what AOSP DeskClock used before Android 17.
 *  2. A static "m:ss" / "h:mm:ss" in the text with a Resume/Start action:
 *     PAUSED. "Time's up" text: RINGING.
 * Samsung Clock's exact format on One UI 9 is still to be confirmed with
 * Debug → "Capture clock notifications"; Android 17 MetricStyle needs
 * compileSdk 37 and is a follow-up.
 */
object TimerParser {
    data class Fields(
        val pkg: String,
        val ongoing: Boolean,
        val whenMs: Long,
        val showChronometer: Boolean,
        val countDown: Boolean,
        val title: String?,
        val text: String?,
        val actions: List<String>,
        val category: String?,
    )

    private val TIME = Regex("""\b(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\b""")
    private val AMPM = Regex("""(?i)\b(am|pm)\b|[午時]""")

    /** Why [parse] did or didn't read a timer from this notification (Look/Debug). */
    fun explain(f: Fields, now: Long): String {
        val t = parse(f, now)
        val head = "${f.pkg.substringAfterLast('.')}: \"${f.title ?: ""}\" / \"${f.text ?: ""}\" " +
            "(ongoing=${f.ongoing}, chrono=${f.showChronometer}, countdown=${f.countDown}, category=${f.category})"
        if (t != null) return "$head → ${t.state.name.lowercase()} " +
            formatCountdown(t.remainingMs(now)) + (t.label?.let { " \"$it\"" } ?: "")
        val all = listOfNotNull(f.title, f.text).joinToString(" ").lowercase()
        val why = when {
            f.pkg !in ClockApps.PACKAGES -> "not a clock app"
            "stopwatch" in all -> "stopwatch, not a timer"
            "alarm" in all && "timer" !in all -> "alarm notification, not a timer"
            !f.showChronometer && !TIME.containsMatchIn(all) -> "no countdown and no m:ss time in its text — unknown format, please share Debug log"
            f.showChronometer && !f.countDown -> "counts up (stopwatch-like)"
            else -> "not recognised — please share Debug log"
        }
        return "$head → skipped: $why"
    }

    fun parse(f: Fields, now: Long): RunningTimer? {
        if (f.pkg !in ClockApps.PACKAGES) return null
        val all = listOfNotNull(f.title, f.text).joinToString(" ")
        val lower = all.lowercase()
        // Never mistake alarm notifications ("Upcoming alarm 6:30 AM") or the stopwatch for a timer.
        if (f.category == android.app.Notification.CATEGORY_ALARM && "timer" !in lower) return null
        if ("stopwatch" in lower) return null
        if ("alarm" in lower && "timer" !in lower) return null

        val label = f.title?.takeIf { it.isNotBlank() && !TIME.containsMatchIn(it) && it.lowercase() != "timer" }

        if ("time's up" in lower || "times up" in lower || "time is up" in lower) {
            return RunningTimer(RunningTimer.State.RINGING, label = label)
        }
        if (f.showChronometer && f.countDown && f.whenMs > now - 1000) {
            return RunningTimer(RunningTimer.State.RUNNING, endsAt = f.whenMs, label = label)
        }
        if (!f.ongoing || AMPM.containsMatchIn(all)) return null
        val m = TIME.find(all) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val ms = ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000
        val paused = f.actions.any { it.lowercase().let { a -> "resume" in a || "start" in a || "continue" in a } }
        return if (paused) RunningTimer(RunningTimer.State.PAUSED, pausedRemainingMs = ms, label = label)
        // Static time, not paused, no chronometer: assume running from now (updates when the notification does).
        else RunningTimer(RunningTimer.State.RUNNING, endsAt = now + ms, label = label)
    }
}

/**
 * A clock app's own alarm notification ("Upcoming alarm · Thu 7:00 AM",
 * "Snoozing until 7:10 AM") → the alarm time. Pure; unit-tested.
 */
object AlarmNotificationParser {
    private val TIME = Regex("""(?i)\b(\d{1,2}):(\d{2})\s*([ap]\.?\s?m\.?)?""")

    fun parse(pkg: String, title: String?, text: String?, now: Long, is24h: Boolean): NextAlarm? {
        if (pkg !in ClockApps.PACKAGES) return null
        val all = listOfNotNull(title, text).joinToString(" ")
        val lower = all.lowercase()
        if ("alarm" !in lower && "snooz" !in lower) return null
        if ("timer" in lower) return null
        val m = TIME.find(all) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val ampm = m.groupValues[3].lowercase()
        if (ampm.startsWith("p") && h < 12) h += 12
        if (ampm.startsWith("a") && h == 12) h = 0
        if (ampm.isEmpty() && !is24h && h > 23) return null
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return NextAlarm(cal.timeInMillis, pkg)
    }
}

/** "4:59", "1:02:03". */
fun formatCountdown(ms: Long): String {
    val s = (ms + 999) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** "in 6 h 49 m", "in 12 m". */
fun formatIn(ms: Long): String {
    val m = (ms + 59_999) / 60_000
    return if (m >= 60) "in ${m / 60} h ${m % 60} m" else "in $m m"
}
