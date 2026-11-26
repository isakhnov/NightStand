package com.nightstand.photos.smart

import kotlin.math.exp

/**
 * The personal head: L2-regularised logistic regression on the 512-d embedding,
 * trained as a *residual* on the zero-shot prior (each example carries the
 * prior logit as an offset), so it only learns what separates this user's
 * swipes from what the prior already gets right. Refitted after every swipe,
 * undo or restore (a few hundred rows × 513 weights: tens of ms). Pure Kotlin.
 *
 * Swipes are one-sided ("junk"), so the caller anchors the fit with positives:
 * restores, plus a sample of shown-and-kept photos (see [SmartPhotos]).
 */
class OnlineHead(val dim: Int) {
    var w = FloatArray(dim); private set
    var b = 0f; private set
    /** Number of swipe (negative) labels the current fit saw; below [MIN_NEGATIVES] the head is off. */
    var negatives = 0; private set
    var updates = 0; private set

    class Example(val x: FloatArray, val y: Float, val weight: Float = 1f, val offset: Float = 0f)

    /** Residual logit h(e), clamped to ±[CLAMP]; 0 until [MIN_NEGATIVES] swipes. */
    fun logit(e: FloatArray): Float {
        if (negatives < MIN_NEGATIVES) return 0f
        return (Vec.dot(w, e) + b).coerceIn(-CLAMP, CLAMP)
    }

    /** One online SGD step on a single example (used between refits). */
    fun step(ex: Example, lr: Float = 0.5f, lambda: Float = LAMBDA) {
        val z = Vec.dot(w, ex.x) + b + ex.offset
        val g = ex.weight * (sigmoid(z) - ex.y)
        for (i in 0 until dim) w[i] -= lr * (g * ex.x[i] + lambda * w[i])
        b -= lr * g
        if (ex.y < 0.5f) negatives++
        updates++
    }

    /**
     * Full refit by batch gradient descent on weighted log-loss + λ/2·|w|²
     * (normalised by total weight so λ means the same for 3 or 300 labels).
     */
    fun fit(data: List<Example>, iters: Int = 150, lr: Float = 2f, lambda: Float = LAMBDA) {
        w = FloatArray(dim); b = 0f
        negatives = data.count { it.y < 0.5f && it.weight > 0f }
        updates++
        if (data.isEmpty()) return
        val total = data.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1e-6f)
        val gw = FloatArray(dim)
        repeat(iters) {
            java.util.Arrays.fill(gw, 0f)
            var gb = 0f
            for (ex in data) {
                val z = Vec.dot(w, ex.x) + b + ex.offset
                val g = ex.weight * (sigmoid(z) - ex.y) / total
                if (g == 0f) continue
                for (i in 0 until dim) gw[i] += g * ex.x[i]
                gb += g
            }
            for (i in 0 until dim) w[i] -= lr * (gw[i] + lambda * w[i])
            b -= lr * gb
        }
    }

    fun reset() { w = FloatArray(dim); b = 0f; negatives = 0; updates = 0 }

    /** [w..., b, negatives, updates] as float32 LE. */
    fun toBlob(): ByteArray = Vec.toF32Blob(w + floatArrayOf(b, negatives.toFloat(), updates.toFloat()))

    companion object {
        const val MIN_NEGATIVES = 3
        const val CLAMP = 4f
        const val LAMBDA = 0.02f

        fun sigmoid(z: Float) = (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()

        fun fromBlob(dim: Int, blob: ByteArray?): OnlineHead {
            val h = OnlineHead(dim)
            val v = blob?.let { Vec.fromF32Blob(it) } ?: return h
            if (v.size != dim + 3) return h
            h.w = v.copyOfRange(0, dim); h.b = v[dim]; h.negatives = v[dim + 1].toInt(); h.updates = v[dim + 2].toInt()
            return h
        }
    }
}
