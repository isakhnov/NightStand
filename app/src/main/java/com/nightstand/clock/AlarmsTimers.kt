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

/**
 * Next alarm = what Android reports (getNextAlarmClock), filtered; plus
 * [AlarmRecheck] right after a hidden entry passes. (v1.2 removed the
 * notification-based memory and the learned "expected" alarms.)
 */
object Alarms {
    const val WINDOW_MS = 24 * 60 * 60_000L

    /** Next alarm to show: accepted owner, not a hidden time, within 24 h. */
    fun next(c: Context, now: Long = System.currentTimeMillis()): NextAlarm? {
        if (!Prefs.showAlarm(c)) return null
        val info = c.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        return filter(NextAlarm(info.triggerTime, info.showIntent?.creatorPackage), now, Prefs.hiddenAlarmTimes(c))
    }

    /**
     * Whose alarms count. On the S26, Google Clock's own alarms carry NO owner
     * (AlarmClockInfo.showIntent == null) — exactly like Samsung Routines — so
     * owner alone can't separate them. Rule: known clock apps always; ownerless
     * entries unless the user hid that time of day ("Not my alarm" in Look);
     * other named apps never.
     */
    fun accepted(a: NextAlarm, hidden: Set<String>): Boolean = when (a.sourcePkg) {
        in ClockApps.PACKAGES -> true
        null -> timeKey(a.triggerAt) !in hidden
        else -> false
    }

    /** "HH:mm" in local time — how a hidden alarm time is stored. */
    fun timeKey(t: Long): String = java.util.Calendar.getInstance().apply { timeInMillis = t }.let {
        "%02d:%02d".format(it.get(java.util.Calendar.HOUR_OF_DAY), it.get(java.util.Calendar.MINUTE))
    }

    /** The slot holder if it's an entry we hide (Routine/Bedtime), else null. */
    fun internalHolder(c: Context, now: Long = System.currentTimeMillis()): NextAlarm? {
        val info = c.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        val a = NextAlarm(info.triggerTime, info.showIntent?.creatorPackage)
        return if (!accepted(a, Prefs.hiddenAlarmTimes(c)) && a.triggerAt > now) a else null
    }

    /** What Android reports, and why it is or isn't shown — for Look and Debug. */
    fun explain(c: Context, now: Long = System.currentTimeMillis()): String {
        val info = c.getSystemService(AlarmManager::class.java).nextAlarmClock
            ?: return "Android reports no upcoming alarm"
        val pkg = info.showIntent?.creatorPackage
        val app = pkg?.let { p ->
            runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() ?: p
        } ?: "unknown app"
        val time = android.text.format.DateFormat.format(
            if (android.text.format.DateFormat.is24HourFormat(c)) "EEE HH:mm" else "EEE h:mm a", info.triggerTime)
        val sys = NextAlarm(info.triggerTime, pkg)
        val why = when {
            pkg == null && !accepted(sys, Prefs.hiddenAlarmTimes(c)) ->
                "hidden: you marked alarms at ${timeKey(sys.triggerAt)} as not yours — your next alarm shows once it has passed"
            pkg !in ClockApps.PACKAGES && pkg != null -> "hidden: not a clock app ($pkg)"
            info.triggerTime - now > WINDOW_MS -> "hidden: more than 24 h away"
            !Prefs.showAlarm(c) -> "hidden: Next alarm is off"
            pkg == null -> "shown (owner not reported — Google Clock does this; if it's a Routine, hide this time)"
            else -> "shown"
        }
        return "$time from $app · $why"
    }

    /** Pure rule, unit-tested. */
    fun filter(a: NextAlarm?, now: Long, hidden: Set<String> = emptySet()): NextAlarm? {
        if (a == null) return null
        if (!accepted(a, hidden)) return null
        if (a.triggerAt < now || a.triggerAt - now > WINDOW_MS) return null
        return a
    }
}

/**
 * Re-reads the next alarm right after a hidden entry (e.g. a 9:30 PM Routine)
 * passes, so the real clock alarm shows immediately. Uses a plain inexact
 * wake-up — NOT setAlarmClock, which would itself take the slot.
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
        com.nightstand.WatcherService.log("recheck scheduled after hidden alarm at ${java.util.Date(holder.triggerAt)}")
    }
}

class AlarmRecheckReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: android.content.Intent) {
        com.nightstand.WatcherService.log("hidden alarm passed; next alarm now: " + Alarms.explain(c))
        com.nightstand.StandByState.notifyAlarmChanged()
        AlarmRecheck.schedule(c) // a later hidden entry may hold the slot next
    }
}

/**
 * Running timers from a clock app's ongoing notification — Android 17
 * MetricStyle only (Google Clock on the S26: empty title/text, a timer metric
 * with zero time or paused duration). Pure; NotificationFeed reads the metric.
 * (v1.2 removed the unverified chronometer/custom-layout/text readers.)
 */
object TimerParser {
    data class Fields(
        val pkg: String,
        val metricTimer: Boolean = false,
        val metricEndsAt: Long? = null,
        val metricPausedMs: Long? = null,
        val metricLabel: String? = null,
        /** For diagnostics only. */
        val template: String? = null,
    )

    fun parse(f: Fields, now: Long): RunningTimer? {
        if (f.pkg !in ClockApps.PACKAGES || !f.metricTimer) return null
        val label = f.metricLabel?.takeIf { it.isNotBlank() && it.lowercase() != "timer" }
        return when {
            f.metricPausedMs != null -> RunningTimer(RunningTimer.State.PAUSED, pausedRemainingMs = f.metricPausedMs, label = label)
            f.metricEndsAt == null -> null
            f.metricEndsAt <= now - 1000 -> RunningTimer(RunningTimer.State.RINGING, label = label)
            else -> RunningTimer(RunningTimer.State.RUNNING, endsAt = f.metricEndsAt, label = label)
        }
    }

    /** Why [parse] did or didn't read a timer from this notification (Look/Debug). */
    fun explain(f: Fields, now: Long): String {
        val head = "${f.pkg.substringAfterLast('.')} (${f.template?.substringAfterLast('$') ?: "no style"})"
        val t = parse(f, now)
        if (t != null) return "$head → ${t.state.name.lowercase()} " + formatCountdown(t.remainingMs(now)) +
            (t.label?.let { " \"$it\"" } ?: "")
        val why = when {
            f.pkg !in ClockApps.PACKAGES -> "not a clock app"
            !f.metricTimer -> "not an Android 17 timer notification (alarm, stopwatch or other)"
            else -> "timer with no time"
        }
        return "$head → skipped: $why"
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
