package com.nightstand

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class NudgeDetectorTest {
    private fun run(d: NudgeDetector, samples: List<Triple<Float, Float, Float>>, t0: Long = 0): Int {
        var n = 0; var t = t0
        samples.forEach { (x, y, z) -> if (d.feed(x, y, z, t)) n++; t += 200 }
        return n
    }

    @Test fun stillAndNoise_noNudge() {
        val r = Random(5)
        val noise = List(3000) { Triple(9.3f + r.nextFloat() * 0.1f - 0.05f, 0.3f + r.nextFloat() * 0.1f - 0.05f, 3f + r.nextFloat() * 0.1f - 0.05f) }
        assertEquals(0, run(NudgeDetector(), noise))
    }

    @Test fun bump_oneNudge_thenCooldown() {
        val still = List(20) { Triple(9.3f, 0.3f, 3f) }
        val bump = listOf(Triple(10.4f, 0.9f, 3.6f), Triple(8.6f, 0.0f, 2.4f), Triple(9.9f, 0.6f, 3.3f))
        assertEquals(1, run(NudgeDetector(), still + bump + still)) // 3 jolty samples within 2 s → 1 nudge
    }
}
