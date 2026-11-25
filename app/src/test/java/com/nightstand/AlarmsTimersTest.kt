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
        assertNull(Alarms.filter(NextAlarm(now + 6 * 3_600_000, null), now))
        assertNull(Alarms.filter(NextAlarm(now + 25 * 3_600_000, samsung), now))
        assertNull(Alarms.filter(NextAlarm(now - 1000, samsung), now))
    }

    private fun f(
        title: String? = "Timer", text: String? = null, chrono: Boolean = false, down: Boolean = false,
        whenMs: Long = 0, actions: List<String> = emptyList(), pkg: String = samsung, ongoing: Boolean = true,
        category: String? = null,
    ) = TimerParser.Fields(pkg, ongoing, whenMs, chrono, down, title, text, actions, category)

    @Test fun chronometerCountdown_isRunning() {
        val t = TimerParser.parse(f(chrono = true, down = true, whenMs = now + 299_000), now)!!
        assertEquals(State.RUNNING, t.state)
        assertEquals(299_000, t.remainingMs(now))
    }

    @Test fun staticTimeWithResume_isPaused() {
        val t = TimerParser.parse(f(text = "04:59", actions = listOf("Resume", "Cancel")), now)!!
        assertEquals(State.PAUSED, t.state)
        assertEquals(299_000, t.pausedRemainingMs)
    }

    @Test fun timesUp_isRinging() {
        assertEquals(State.RINGING, TimerParser.parse(f(text = "Time's up"), now)!!.state)
    }

    @Test fun notTimers() {
        assertNull(TimerParser.parse(f(title = "Upcoming alarm", text = "6:30 AM"), now))
        assertNull(TimerParser.parse(f(title = "Stopwatch", text = "01:23", chrono = true), now))
        assertNull(TimerParser.parse(f(pkg = "com.whatsapp", chrono = true, down = true, whenMs = now + 5000), now))
        assertNull(TimerParser.parse(f(title = "Alarm", text = "Snoozed until 6:40"), now))
    }

    @Test fun labelKept() {
        assertEquals("Pasta", TimerParser.parse(f(title = "Pasta", chrono = true, down = true, whenMs = now + 60_000), now)!!.label)
        assertNull(TimerParser.parse(f(title = "Timer", chrono = true, down = true, whenMs = now + 60_000), now)!!.label)
    }

    @Test fun formats() {
        assertEquals("4:59", formatCountdown(298_100))
        assertEquals("1:02:03", formatCountdown(3_723_000))
        assertEquals("in 6 h 49 m", formatIn((6 * 60 + 49) * 60_000L))
        assertEquals("in 12 m", formatIn(12 * 60_000L))
    }
}

class AlarmNotificationParserTest {
    private val google = "com.google.android.deskclock"
    // 2026-11-25 17:30 local
    private val now = java.util.Calendar.getInstance().apply { set(2026, 10, 25, 17, 30, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis

    private fun hm(t: Long) = java.util.Calendar.getInstance().apply { timeInMillis = t }.let {
        it.get(java.util.Calendar.HOUR_OF_DAY) to it.get(java.util.Calendar.MINUTE)
    }

    @org.junit.Test fun upcomingAlarm_tomorrowMorning() {
        val a = com.nightstand.clock.AlarmNotificationParser.parse(google, "Upcoming alarm", "Thu 7:00 AM", now, false)!!
        org.junit.Assert.assertEquals(7 to 0, hm(a.triggerAt))
        org.junit.Assert.assertTrue(a.triggerAt > now)
    }

    @org.junit.Test fun snoozed_pm() {
        val a = com.nightstand.clock.AlarmNotificationParser.parse(google, "Alarm", "Snoozing until 9:40 PM", now, false)!!
        org.junit.Assert.assertEquals(21 to 40, hm(a.triggerAt))
    }

    @org.junit.Test fun ignoresTimersAndOtherApps() {
        org.junit.Assert.assertNull(com.nightstand.clock.AlarmNotificationParser.parse(google, "Timer", "4:59", now, false))
        org.junit.Assert.assertNull(com.nightstand.clock.AlarmNotificationParser.parse("com.whatsapp", "Alarm", "7:00 AM", now, false))
    }
}

class AlarmChooseTest {
    private val now = 1_000_000_000L
    private val google = "com.google.android.deskclock"
    private val bedtime = com.nightstand.clock.NextAlarm(now + 4 * 3_600_000, null)          // 9:30 PM, no owner
    private val real = com.nightstand.clock.NextAlarm(now + 14 * 3_600_000, google)          // 7:30 AM

    @org.junit.Test fun internalHolderFilteredAndRememberedUsed() {
        org.junit.Assert.assertEquals(real, com.nightstand.clock.Alarms.choose(bedtime, real, now))
    }

    @org.junit.Test fun realSystemAlarmWins() {
        val soon = com.nightstand.clock.NextAlarm(now + 3_600_000, google)
        org.junit.Assert.assertEquals(soon, com.nightstand.clock.Alarms.choose(soon, real, now))
    }

    @org.junit.Test fun nothingWhenOnlyInternal() {
        org.junit.Assert.assertNull(com.nightstand.clock.Alarms.choose(bedtime, null, now))
    }
}
