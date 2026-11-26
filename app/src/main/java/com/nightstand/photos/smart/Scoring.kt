package com.nightstand.photos.smart

import java.util.Calendar
import kotlin.math.abs

/**
 * How a scored photo is judged and ranked. Pure; unit-tested.
 *
 *   prior ℓ0 = A · clamp(zero-shot margin, ±12)       (margin > 0 ⇔ a "keep" prompt wins)
 *   ℓ        = ℓ0 + head(e) − soft look-alike penalty
 *   eligible ⇔ σ(ℓ) ≥ τ (0.5 by day, 0.7 at night) and not within the hide radius of a swiped photo
 *   rank     = ℓ + recency bonus − repeat/diversity penalties + small jitter
 */
object Scoring {
    const val PRIOR_A = 0.35f
    const val TAU_DAY = 0.5f
    const val TAU_NIGHT = 0.7f
    /** Soft penalty band below the hard radius (0 → 2 logits). */
    const val SOFT_BAND = 0.12f

    fun prior(zsMargin: Float) = PRIOR_A * zsMargin.coerceIn(-12f, 12f)

    /** Highest cosine between [e] and any hidden (swiped) embedding; −1 if none. */
    fun nearest(e: FloatArray, hidden: List<FloatArray>): Float {
        var best = -1f
        for (h in hidden) { val c = Vec.dot(e, h); if (c > best) best = c }
        return best
    }

    /** Hard suppression: a look-alike of something the user swiped away. */
    fun suppressed(nearest: Float, radius: Float) = nearest >= radius

    fun softPenalty(nearest: Float, radius: Float): Float {
        val lo = radius - SOFT_BAND
        return if (nearest <= lo || nearest >= radius) (if (nearest >= radius) 2f else 0f)
        else 2f * (nearest - lo) / SOFT_BAND
    }

    fun logit(zsMargin: Float, head: Float, nearest: Float, radius: Float) =
        prior(zsMargin) + head - softPenalty(nearest, radius)

    fun eligible(logit: Float, night: Boolean) =
        OnlineHead.sigmoid(logit) >= if (night) TAU_NIGHT else TAU_DAY

    /**
     * Rank among eligible look-ahead candidates: quality first, then a small
     * push towards recent / "on this day" photos, away from ones shown lately or
     * looking like the last few shown.
     */
    fun rank(
        logit: Float, e: FloatArray, takenMs: Long, lastShownMs: Long, recentShown: List<FloatArray>,
        nowMs: Long, jitter: Float = 0f,
    ): Float {
        var s = logit.coerceIn(-6f, 6f)
        val day = 86_400_000L
        if (takenMs > 0) {
            if (nowMs - takenMs in 0..30 * day) s += 0.3f
            else if (onThisDay(takenMs, nowMs)) s += 0.5f
        }
        if (lastShownMs > 0 && nowMs - lastShownMs < 3 * day) s -= 2f
        val sim = recentShown.maxOfOrNull { Vec.dot(e, it) } ?: 0f
        if (sim > 0.85f) s -= 1.5f
        return s + jitter
    }

    fun onThisDay(takenMs: Long, nowMs: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = takenMs }
        val b = Calendar.getInstance().apply { timeInMillis = nowMs }
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR)) return false
        val d = abs(a.get(Calendar.DAY_OF_YEAR) - b.get(Calendar.DAY_OF_YEAR))
        return minOf(d, 365 - d) <= 3
    }
}
