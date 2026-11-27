package com.nightstand

import com.nightstand.clock.Alarms
import com.nightstand.clock.NextAlarm
import com.nightstand.clock.RunningTimer.State
import com.nightstand.clock.TimerParser
import com.nightstand.clock.formatCountdown
import com.nightstand.clock.formatIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmsTimersTest {
    private val now = 1_000_000_000L
    private val samsung = "com.sec.android.app.clockpackage"

    @Test fun alarm_clockAppsOnly_within24h() {
        assertNotNull(Alarms.filter(NextAlarm(now + 6 * 3_600_000, samsung), now))
        assertNull(Alarms.filter(NextAlarm(now + 6 * 3_600_000, "com.samsung.android.calendar"), now))
        // Ownerless (Google Clock on the S26 reports none): shown unless that time is hidden.
        val ownerless = NextAlarm(now + 6 * 3_600_000, null)
        assertNotNull(Alarms.filter(ownerless, now))
        assertNull(Alarms.filter(ownerless, now, setOf(Alarms.timeKey(ownerless.triggerAt))))
        assertNull(Alarms.filter(NextAlarm(now + 25 * 3_600_000, samsung), now))
        assertNull(Alarms.filter(NextAlarm(now - 1000, samsung), now))
    }

    @Test fun formats() {
        assertEquals("4:59", formatCountdown(298_100))
        assertEquals("1:02:03", formatCountdown(3_723_000))
        assertEquals("in 6 h 49 m", formatIn((6 * 60 + 49) * 60_000L))
        assertEquals("in 12 m", formatIn(12 * 60_000L))
    }
}

/** Android 17 MetricStyle timers (Google Clock on the S26). */
class MetricStyleTimerTest {
    private val now = 1_795_648_379_845L
    private fun f(ends: Long?, paused: Long?, label: String? = "Timer", pkg: String = "com.google.android.deskclock") =
        TimerParser.Fields(pkg, metricTimer = true, metricEndsAt = ends, metricPausedMs = paused, metricLabel = label)

    @Test fun running() {
        val t = TimerParser.parse(f(now + 210_000, null), now)!!
        assertEquals(State.RUNNING, t.state)
        assertEquals(210_000, t.remainingMs(now))
        assertNull(t.label) // "Timer" isn't a useful label
    }

    @Test fun paused() {
        val t = TimerParser.parse(f(null, 95_000, "Pasta"), now)!!
        assertEquals(State.PAUSED, t.state)
        assertEquals(95_000, t.pausedRemainingMs)
        assertEquals("Pasta", t.label)
    }

    @Test fun ringing() {
        assertEquals(State.RINGING, TimerParser.parse(f(now - 5_000, null), now)!!.state)
    }

    @Test fun notTimers() {
        assertNull(TimerParser.parse(TimerParser.Fields("com.google.android.deskclock"), now)) // alarm/stopwatch: no metric
        assertNull(TimerParser.parse(f(now + 5_000, null, pkg = "com.whatsapp"), now))
    }
}
