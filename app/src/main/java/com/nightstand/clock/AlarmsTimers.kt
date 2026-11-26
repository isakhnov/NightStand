package com.nightstand.clock

import android.app.AlarmManager
import android.content.Context
import com.nightstand.Prefs

/** The single next alarm Android reports (see docs/views/05-alarms-timers). */
data class NextAlarm(
    val triggerAt: Long,
    val sourcePkg: String?,
    /** Learned from earlier nights, not confirmed by Android right now (drawn dimmed, "~"). */
    val expected: Boolean = false,
)

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
        val hidden = Prefs.hiddenAlarmTimes(c)
        val patterns = observe(c, system, now)
        val chosen = choose(system, AlarmMemory.get(c, now), now, hidden)
        if (chosen != null || !Prefs.rememberAlarms(c)) return chosen
        // Slot held by an internal entry (Routines/Bedtime): show the learned alarm as "expected".
        val holder = system?.takeIf { !accepted(it, hidden) }?.triggerAt
        return AlarmPatterns.expected(patterns, now, WINDOW_MS, holder)
    }

    /** Learn from what Android reports and drop contradicted patterns. */
    fun observe(c: Context, system: NextAlarm?, now: Long = System.currentTimeMillis()): List<AlarmPatterns.Pattern> {
        var list = AlarmPatterns.load(c)
        if (system != null && accepted(system, Prefs.hiddenAlarmTimes(c)) && system.triggerAt > now) {
            list = AlarmPatterns.record(list, system, now)
        }
        val pruned = AlarmPatterns.prune(list, system?.triggerAt, now)
        if (pruned != AlarmPatterns.load(c)) AlarmPatterns.save(c, pruned)
        return pruned
    }

    /**
     * Android exposes only ONE next alarm. Internal holders (seen on the S26:
     * the 9:30 PM Bedtime action, no owner) are filtered out; then the alarm
     * remembered from the clock app's own "Upcoming alarm" notification is
     * used. A real clock alarm reported by Android always wins. Pure; tested.
     */
    fun choose(system: NextAlarm?, remembered: NextAlarm?, now: Long, hidden: Set<String> = emptySet()): NextAlarm? =
        filter(system, now, hidden) ?: filter(remembered, now, hidden)

    /**
     * Whose alarms count. On the S26, Google Clock's own alarms carry NO owner
     * (AlarmClockInfo.showIntent == null) — exactly like Samsung Routines — so
     * owner alone can't separate them (0.7.x hid the real 7:10 alarm). Rule:
     * known clock apps always; ownerless entries unless the user hid that time
     * of day ("Hide alarms at 9:30 PM" in Look); other named apps never.
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

    /** The slot holder if it's an internal alarm we hide (bedtime etc.), else null. */
    fun internalHolder(c: Context, now: Long = System.currentTimeMillis()): NextAlarm? {
        val info = c.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        val a = NextAlarm(info.triggerTime, info.showIntent?.creatorPackage)
        return if (!accepted(a, Prefs.hiddenAlarmTimes(c)) && a.triggerAt > now) a else null
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
        val hidden = Prefs.hiddenAlarmTimes(c)
        val sys = NextAlarm(info.triggerTime, pkg)
        val ok = accepted(sys, hidden)
        val fallback = filter(AlarmMemory.get(c, now), now, hidden)
        val shown = next(c, now)
        val why = when {
            !ok && fallback == null && shown?.expected == true ->
                "hidden (${if (pkg == null) "you hid ${timeKey(sys.triggerAt)}" else pkg}); showing your usual ${alarmLabel(c, shown)} as expected"
            !ok && fallback != null ->
                "hidden (${if (pkg == null) "you hid ${timeKey(sys.triggerAt)}" else pkg}); showing ${alarmLabel(c, fallback)} from the clock's notification"
            pkg == null && !ok -> "hidden: you marked alarms at ${timeKey(sys.triggerAt)} as not yours (Routine/Bedtime)"
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
        Alarms.next(c) // learn the real alarm now visible
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
        /**
         * From a custom notification layout (Google Clock / AOSP DeskClock put
         * the countdown in a Chronometer inside a custom view, leaving title and
         * text empty): wall-clock time the countdown reaches zero, as of when
         * the notification was posted. null if there is no such Chronometer.
         */
        val customEndsAt: Long? = null,
        val customCountDown: Boolean = false,
        /** Visible texts of the custom layout (label, "Timer", etc.). */
        val customTexts: List<String> = emptyList(),
        /**
         * Android 17 MetricStyle (Google Clock on the S26): a timer metric's
         * zero time (running) or paused remainder, plus its label.
         */
        val metricTimer: Boolean = false,
        val metricEndsAt: Long? = null,
        val metricPausedMs: Long? = null,
        val metricLabel: String? = null,
    )

    private val TIME = Regex("""\b(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\b""")
    private val AMPM = Regex("""(?i)\b(am|pm)\b|[午時]""")

    /** Why [parse] did or didn't read a timer from this notification (Look/Debug). */
    fun explain(f: Fields, now: Long): String {
        val t = parse(f, now)
        val head = "${f.pkg.substringAfterLast('.')}: \"${f.title ?: ""}\" / \"${f.text ?: ""}\" " +
            "(ongoing=${f.ongoing}, chrono=${f.showChronometer}, countdown=${f.countDown}, category=${f.category}, " +
            "metric=${if (f.metricTimer) "timer " + (f.metricPausedMs?.let { "paused " + formatCountdown(it) } ?: f.metricEndsAt?.let { "ends in " + formatCountdown(it - now) }) else "none"}, " +
            "custom=${f.customEndsAt?.let { "ends in " + formatCountdown(it - now) } ?: "none"}${if (f.customCountDown) " ↓" else ""}" +
            "${if (f.customTexts.isNotEmpty()) " " + f.customTexts else ""}, actions=${f.actions})"
        if (t != null) return "$head → ${t.state.name.lowercase()} " +
            formatCountdown(t.remainingMs(now)) + (t.label?.let { " \"$it\"" } ?: "")
        val all = listOfNotNull(f.title, f.text).joinToString(" ").lowercase()
        val why = when {
            f.pkg !in ClockApps.PACKAGES -> "not a clock app"
            "stopwatch" in all -> "stopwatch, not a timer"
            "alarm" in all && "timer" !in all -> "alarm notification, not a timer"
            f.customEndsAt != null && !f.customCountDown -> "custom layout counts up (stopwatch-like)"
            !f.showChronometer && f.customEndsAt == null && !TIME.containsMatchIn(all) ->
                "no countdown in fields or custom layout — unknown format, please share Debug log"
            f.showChronometer && !f.countDown -> "counts up (stopwatch-like)"
            else -> "not recognised — please share Debug log"
        }
        return "$head → skipped: $why"
    }

    private fun parseMetric(f: Fields, now: Long): RunningTimer? {
        val ml = f.metricLabel?.takeIf { it.isNotBlank() && it.lowercase() != "timer" }
        return when {
            f.metricPausedMs != null -> RunningTimer(RunningTimer.State.PAUSED, pausedRemainingMs = f.metricPausedMs, label = ml)
            f.metricEndsAt == null -> null
            f.metricEndsAt <= now - 1000 -> RunningTimer(RunningTimer.State.RINGING, label = ml)
            else -> RunningTimer(RunningTimer.State.RUNNING, endsAt = f.metricEndsAt, label = ml)
        }
    }

    fun parse(f: Fields, now: Long): RunningTimer? {
        if (f.pkg !in ClockApps.PACKAGES) return null
        val all = listOfNotNull(f.title, f.text).joinToString(" ")
        val lower = all.lowercase()
        if (f.metricTimer) return parseMetric(f, now)
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
        // Custom-layout countdown (Google Clock). Paused = a Resume/Start action.
        if (f.customEndsAt != null && f.customCountDown) {
            val customLabel = label ?: f.customTexts.firstOrNull {
                it.isNotBlank() && !TIME.containsMatchIn(it) && it.lowercase() !in setOf("timer", "pause", "resume", "reset", "+1:00")
            }
            val paused = f.actions.any { it.lowercase().let { a -> "resume" in a || "start" in a || "continue" in a } }
            return if (paused) RunningTimer(RunningTimer.State.PAUSED,
                pausedRemainingMs = (f.customEndsAt - now).coerceAtLeast(0), label = customLabel)
            else if (f.customEndsAt <= now - 1000) RunningTimer(RunningTimer.State.RINGING, label = customLabel)
            else RunningTimer(RunningTimer.State.RUNNING, endsAt = f.customEndsAt, label = customLabel)
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
