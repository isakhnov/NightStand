package com.nightstand.smart

import com.nightstand.Prefs
import com.nightstand.photos.smart.BurstGrouper
import com.nightstand.photos.smart.LookAhead
import com.nightstand.photos.smart.OnlineHead
import com.nightstand.photos.smart.Preprocess
import com.nightstand.photos.smart.Scoring
import com.nightstand.photos.smart.Vec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/** Fixtures shared by the smart-photos tests: unit vectors with a chosen cosine. */
object V {
    const val DIM = 512
    fun rand(seed: Int): FloatArray { val r = Random(seed); return Vec.normalize(FloatArray(DIM) { r.nextFloat() * 2 - 1 }) }
    /** A unit vector with cosine exactly [cos] to unit vector [v] (direction from [seed]). */
    fun near(v: FloatArray, cos: Float, seed: Int): FloatArray {
        val o = rand(seed); val d = Vec.dot(o, v)
        val orth = Vec.normalize(FloatArray(DIM) { o[it] - d * v[it] })
        val s = sqrt(1 - cos * cos)
        return FloatArray(DIM) { cos * v[it] + s * orth[it] }
    }
}

class SmartPureTest {
    // --- vectors ---
    @Test fun fp16_roundTripAndCosine() {
        val v = V.rand(1)
        val back = Vec.fromBlob(Vec.toBlob(v))
        assertEquals(1024, Vec.toBlob(v).size)
        assertTrue(Vec.cosine(v, back) > 0.99999f)
        for (x in floatArrayOf(0f, 1f, -2.5f, 65504f, 6.1e-5f, 3e-7f, -0.33333f)) assertEquals(x, Vec.fromHalf(Vec.toHalf(x)), maxOf(1e-7f, kotlin.math.abs(x) * 1e-3f))
        assertEquals(0.92f, Vec.dot(V.rand(2), V.near(V.rand(2), 0.92f, 3)), 1e-4f)
    }

    // --- look-alike radius ---
    @Test fun radius_settingsOrderedAndJustified() {
        val (strict, normal, wide) = Prefs.LookAlike.values().map { it.cos }
        assertEquals(0.95f, strict); assertEquals(0.92f, normal); assertEquals(0.88f, wide)
        // Validation (tflite/validation.json): edits of one shot 0.975–0.999, different scenes ≤ 0.50.
        // Every setting must catch all near-dupes and none of the different scenes.
        for (r in listOf(strict, normal, wide)) { assertTrue(r <= 0.975f); assertTrue("margin to other scenes", r - 0.50f >= 0.3f) }
    }

    @Test fun suppression_edges() {
        val swiped = V.rand(10)
        val hidden = listOf(swiped)
        for ((radius, below, at) in listOf(Triple(0.92f, 0.919f, 0.921f), Triple(0.95f, 0.949f, 0.951f), Triple(0.88f, 0.879f, 0.881f))) {
            val nb = Scoring.nearest(V.near(swiped, below, 11), hidden)
            val na = Scoring.nearest(V.near(swiped, at, 12), hidden)
            assertFalse("$radius below", Scoring.suppressed(nb, radius))
            assertTrue("$radius at", Scoring.suppressed(na, radius))
        }
        assertEquals(-1f, Scoring.nearest(swiped, emptyList()), 0f)
        // soft band: 0 at radius-0.12, 2 at the radius, linear between
        assertEquals(0f, Scoring.softPenalty(0.80f, 0.92f), 1e-5f)
        assertEquals(1f, Scoring.softPenalty(0.86f, 0.92f), 1e-4f)
        assertEquals(2f, Scoring.softPenalty(0.93f, 0.92f), 0f)
        assertEquals(0f, Scoring.softPenalty(0.3f, 0.92f), 0f)
    }

    // --- bursts ---
    private fun item(id: Long, t: Long, e: FloatArray) = BurstGrouper.Item(id, t, e)

    @Test fun burst_chainsWithinGapAndCosine() {
        val a = V.rand(20)
        val g = BurstGrouper.group(listOf(
            item(1, 10_000, a), item(2, 11_000, V.near(a, 0.97f, 1)), item(3, 13_500, V.near(a, 0.95f, 2)),
            item(4, 16_400, V.near(a, 0.96f, 3)),              // 2.9 s after #3 → still the same burst (chain)
            item(5, 19_500, V.near(a, 0.99f, 4)),              // 3.1 s gap → new group
            item(6, 20_000, V.rand(99)),                        // 0.5 s later but a different scene → new group
        ).shuffled(Random(1)))
        assertEquals(listOf(listOf(1L, 2L, 3L, 4L), listOf(5L), listOf(6L)), g.map { grp -> grp.map { it.id } })
        // exactly at the edges: 3 s and cos 0.90 join
        val b = V.rand(30)
        assertEquals(1, BurstGrouper.group(listOf(item(1, 0, b), item(2, 3_000, V.near(b, 0.9001f, 5)))).size)
        assertEquals(2, BurstGrouper.group(listOf(item(1, 0, b), item(2, 3_000, V.near(b, 0.899f, 5)))).size)
    }

    @Test fun burst_bestByScore() {
        val a = V.rand(40)
        val grp = listOf(item(1, 0, a), item(2, 500, a), item(3, 900, a))
        assertEquals(2L, BurstGrouper.best(grp) { mapOf(1L to 0.1f, 2L to 0.9f, 3L to 0.5f)[it.id]!! }.id)
        assertEquals(1L, BurstGrouper.best(grp) { 0f }.id) // ties → earliest
    }

    // --- personal head ---
    @Test fun head_learnsSeparableSplit_andGatesOnThreeSwipes() {
        val junkDir = V.rand(50); val keepDir = V.rand(51)
        val neg = (0 until 6).map { OnlineHead.Example(V.near(junkDir, 0.8f, 100 + it), 0f) }
        val pos = (0 until 6).map { OnlineHead.Example(V.near(keepDir, 0.8f, 200 + it), 1f) }
        val h = OnlineHead(V.DIM)
        h.fit(neg.take(2) + pos)
        assertEquals("off below 3 swipes", 0f, h.logit(V.near(junkDir, 0.8f, 300)), 0f)
        h.fit(neg + pos)
        val newJunk = V.near(junkDir, 0.8f, 301); val newKeep = V.near(keepDir, 0.8f, 302)
        assertTrue("junk ${h.logit(newJunk)} < 0", h.logit(newJunk) < 0f)
        assertTrue("keep ${h.logit(newKeep)} > 0", h.logit(newKeep) > 0f)
        assertTrue(kotlin.math.abs(h.logit(newJunk)) <= OnlineHead.CLAMP)
        // online step moves the prediction in the right direction
        val before = Vec.dot(h.w, newJunk) + h.b
        h.step(OnlineHead.Example(newJunk, 0f))
        assertTrue(Vec.dot(h.w, newJunk) + h.b < before)
        // blob round trip
        val back = OnlineHead.fromBlob(V.DIM, h.toBlob())
        assertEquals(h.logit(newKeep), back.logit(newKeep), 1e-6f)
        assertEquals(0f, OnlineHead.fromBlob(V.DIM, null).logit(newKeep), 0f)
    }

    @Test fun head_oneSidedSwipesWithAnchorsDoNotCollapse() {
        val junkDir = V.rand(60)
        val neg = (0 until 10).map { OnlineHead.Example(V.near(junkDir, 0.85f, 400 + it), 0f, 1f, 1.5f) }
        val anchors = (0 until 50).map { OnlineHead.Example(V.rand(500 + it), 1f, 0.5f * 10 / 50, 1.5f) }
        val h = OnlineHead(V.DIM); h.fit(neg + anchors)
        val meanAnchorP = anchors.map { OnlineHead.sigmoid(it.offset + h.logit(it.x)) }.average()
        assertTrue("anchors stay shown ($meanAnchorP)", meanAnchorP >= 0.5)
        assertTrue(OnlineHead.sigmoid(1.5f + h.logit(V.near(junkDir, 0.85f, 999))) < 0.5f)
    }

    // --- selection scoring ---
    @Test fun scoring_priorThresholdsAndRank() {
        assertEquals(Scoring.PRIOR_A * 12f, Scoring.prior(40f), 0f) // clamped
        assertTrue(Scoring.eligible(Scoring.logit(4.5f, 0f, -1f, 0.92f), night = false)) // landscape-like margin
        assertFalse(Scoring.eligible(Scoring.logit(-6.6f, 0f, -1f, 0.92f), night = false)) // target-like
        // night is stricter: a weak keep passes by day, not at night
        val weak = Scoring.logit(1.5f, 0f, -1f, 0.92f)
        assertTrue(Scoring.eligible(weak, false)); assertFalse(Scoring.eligible(weak, true))
        // head can veto a "keep" the user swiped similar photos of
        assertFalse(Scoring.eligible(Scoring.logit(4.5f, -2f, -1f, 0.92f), false))
        val now = 1_800_000_000_000L; val day = 86_400_000L; val e = V.rand(70)
        val base = Scoring.rank(1f, e, now - 400 * day + 100 * day, 0, emptyList(), now)
        assertTrue(Scoring.rank(1f, e, now - 5 * day, 0, emptyList(), now) > base)              // recent
        assertTrue(Scoring.rank(1f, e, now - 365 * day, 0, emptyList(), now) > base)            // on this day
        assertTrue(Scoring.rank(1f, e, now - 400 * day + 100 * day, now - day, emptyList(), now) < base) // shown yesterday
        assertTrue(Scoring.rank(1f, e, now - 400 * day + 100 * day, 0, listOf(V.near(e, 0.9f, 1)), now) < base) // like last shown
        assertTrue(Scoring.rank(3f, e, 0, 0, emptyList(), now) > Scoring.rank(1f, e, 0, 0, emptyList(), now))
    }

    // --- look-ahead queue ---
    @Test fun lookAhead_fillsToDepthWithinTriesAndTakesBest() {
        val q = LookAhead<Int, Int>(depth = 3, maxTries = 5)
        var n = 0
        val tried = q.fill({ n++ }) { if (it % 2 == 0) it * 10 else null } // evens accepted
        assertEquals(5, tried); assertEquals(listOf(0, 20, 40), q.items)
        assertEquals(40, q.take { it.toFloat() })
        q.fill({ n++ }) { if (it % 2 == 0) it * 10 else null }
        assertEquals(3, q.size)
        assertEquals(0, q.take(stillOk = { it < 20 }) { it.toFloat() }) // drops stale ones first
        assertEquals(0, q.size)
        assertNull(q.take { 0f })
        assertEquals(0, LookAhead<Int, Int>(3, 20).fill({ null }) { it }) // source exhausted
    }

    @Test fun preprocess_layouts() {
        val px = IntArray(4) { 0xFF000000.toInt() or (it * 60 shl 16) or (10 shl 8) or 255 }
        val nhwc = Preprocess.toTensor(px, nhwc = true, size = 2)
        val nchw = Preprocess.toTensor(px, nhwc = false, size = 2)
        assertEquals(180 / 255f, nhwc[9], 1e-6f); assertEquals(180 / 255f, nchw[3], 1e-6f) // R of pixel 3
        assertEquals(10 / 255f, nhwc[1], 1e-6f); assertEquals(10 / 255f, nchw[4], 1e-6f)   // G of pixel 0
        assertEquals(1f, nhwc[2], 0f); assertEquals(1f, nchw[8], 0f)                       // B of pixel 0
    }
}
