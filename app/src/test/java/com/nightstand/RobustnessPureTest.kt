package com.nightstand

import com.nightstand.clock.RunningTimer
import com.nightstand.clock.TimerParser
import com.nightstand.clock.formatCountdown
import com.nightstand.photos.KenBurns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Pure-logic edge cases: nothing here may throw or produce NaN. */
class RobustnessPureTest {

    @Test fun kenBurns_extremeShapesStayFinite() {
        val shapes = listOf(10000 to 1000, 1000 to 10000, 1 to 1, 4000 to 3000, 3 to 50000)
        for ((w, h) in shapes) for (t in listOf(0L, 1L, 12_000L, 30_000L, 45_000L, 10_000_000L)) for (dir in listOf(-1, 1)) {
            val f = KenBurns.frame(w, h, 500f, 400f, t, 12_000, 45_000, dir)
            assertTrue("$w×$h t=$t", f.scale.isFinite() && f.left.isFinite() && f.top.isFinite() && f.scale > 0)
        }
    }

    @Test fun kenBurns_degenerateDurations() {
        val f = KenBurns.frame(4000, 3000, 500f, 400f, 5_000, 12_000, 12_000, 1) // total == zoom
        assertTrue(f.scale.isFinite())
    }

    @Test fun timerParser_neverThrowsOnRandomInput() {
        val r = Random(7)
        repeat(2000) {
            val f = TimerParser.Fields(
                pkg = if (r.nextBoolean()) "com.google.android.deskclock" else "x",
                metricTimer = r.nextBoolean(), metricEndsAt = if (r.nextBoolean()) r.nextLong() else null,
                metricPausedMs = if (r.nextBoolean()) r.nextLong(0, 10_000_000) else null,
                metricLabel = listOf(null, "", "Timer", "Pasta", "午後").random(r),
            )
            TimerParser.parse(f, 1_000_000_000L)?.let { formatCountdown(it.remainingMs(1_000_000_000L)) }
            TimerParser.explain(f, 1_000_000_000L)
        }
    }

    @Test fun countdownFormatting_bounds() {
        assertEquals("0:00", formatCountdown(0)); assertEquals("0:01", formatCountdown(1))
        assertEquals("100:00:00", formatCountdown(360_000_000))
        assertEquals(0, RunningTimer(RunningTimer.State.RUNNING, endsAt = 5).remainingMs(10))
    }

    @Test fun posture_noisyLandscapeDoesNotFlicker() {
        val d = PostureDetector()
        val r = Random(3)
        var t = 0L
        repeat(20) { d.feed(9.3f, 0.3f, 3f, t); t += 200 }
        var flips = 0; var last = d.stable
        repeat(3000) { // 10 minutes of small table vibrations
            val p = d.feed(9.3f + r.nextFloat() * 0.4f - 0.2f, 0.3f + r.nextFloat() * 0.4f - 0.2f, 3f + r.nextFloat() * 0.4f - 0.2f, t)
            if (p != last) flips++; last = p; t += 200
        }
        assertEquals(PostureDetector.Posture.LANDSCAPE, d.stable)
        assertEquals(0, flips)
    }

    @Test fun triggerPolicy_randomWalkInvariants() {
        val r = Random(11)
        repeat(200) {
            val p = TriggerPolicy()
            repeat(200) {
                val shown = when (r.nextInt(6)) {
                    0 -> p.onCharging(r.nextBoolean())
                    1 -> p.onLandscape(r.nextBoolean())
                    2 -> p.onLocked(r.nextBoolean())
                    3 -> p.onScreenOff()
                    4 -> { p.onVisible(r.nextBoolean()); false }
                    else -> { p.onLaunched(); false }
                }
                if (shown) {
                    assertTrue(p.charging); assertTrue(p.landscape); assertTrue(p.locked); assertFalse(p.visible)
                }
            }
        }
    }
}
