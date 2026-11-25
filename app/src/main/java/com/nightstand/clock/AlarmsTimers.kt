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
        val info = c.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        return filter(NextAlarm(info.triggerTime, info.showIntent?.creatorPackage), now)
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
        val why = when {
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
