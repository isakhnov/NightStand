package com.nightstand

import com.nightstand.clock.AlarmPatterns
import com.nightstand.clock.NextAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AlarmPatternsTest {
    private val google = "com.google.android.deskclock"
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        Calendar.getInstance().apply { set(y, mo - 1, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    // Thu 2026-11-26 04:35: routine passed, Android shows the 7:30 Google alarm → learn it.
    private val learnedAt = at(2026, 11, 26, 4, 35)
    private val thu730 = NextAlarm(at(2026, 11, 26, 7, 30), google)

    @Test fun learnedAlarmShownNextNightWhileRoutineHoldsSlot() {
        val list = AlarmPatterns.record(emptyList(), thu730, learnedAt)
        // Wed-style evening the following week: Thu 2026-12-03 is a week later; check Wed 12-02 21:40
        val evening = at(2026, 12, 2, 21, 40)
        val holder = at(2026, 12, 3, 4, 30) // 4:30 AM routine holds the slot
        val e = AlarmPatterns.expected(AlarmPatterns.prune(list, holder, evening), evening, 24 * 3_600_000L, holder)
        assertNotNull(e)
        assertEquals(at(2026, 12, 3, 7, 30), e!!.triggerAt)
        assertTrue(e.expected)
    }

    @Test fun onlyOnLearnedWeekday() {
        val list = AlarmPatterns.record(emptyList(), thu730, learnedAt)
        val fridayEve = at(2026, 11, 26, 22, 0) // Thu evening → next 7:30 would be Fri: not learned
        assertNull(AlarmPatterns.expected(list, fridayEve, 24 * 3_600_000L, null))
    }

    @Test fun contradictedPatternDropped() {
        val list = AlarmPatterns.record(emptyList(), thu730, learnedAt)
        // Next Wed night Android reports a real 8:00 Thu alarm as earliest → the 7:30 one is gone.
        val evening = at(2026, 12, 2, 23, 0)
        val pruned = AlarmPatterns.prune(list, at(2026, 12, 3, 8, 0), evening)
        assertTrue(pruned.isEmpty())
    }

    @Test fun samsungEarlyReportIsNotAContradiction() {
        val list = AlarmPatterns.record(emptyList(), thu730, learnedAt)
        val evening = at(2026, 12, 2, 23, 0)
        assertEquals(1, AlarmPatterns.prune(list, at(2026, 12, 3, 7, 25), evening).size)
    }

    @Test fun staleAfterEightDays() {
        val list = AlarmPatterns.record(emptyList(), thu730, learnedAt)
        assertTrue(AlarmPatterns.prune(list, null, learnedAt + 9 * 24 * 3_600_000L).isEmpty())
    }
}
