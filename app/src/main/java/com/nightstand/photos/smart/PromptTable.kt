package com.nightstand.photos.smart

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln

/**
 * The zero-shot prompt vectors (assets/smart/prompts_v2.bin, made by
 * docs/photos-intelligence/step-a/tflite/make_prompts.py from MobileCLIP-S0's
 * text encoder). Keep = memories (people, pets, places, cars, firearms…);
 * everything else is junk — the user's "no grey areas" rule.
 */
class PromptTable(val ids: List<String>, val keep: BooleanArray, val vectors: Array<FloatArray>, val logitScale: Float = 100f) {
    val size get() = ids.size

    data class ZeroShot(val margin: Float, val top: String, val topKeep: Boolean)

    /**
     * margin = log P(keep) − log P(junk) under the softmax over all prompts at
     * [logitScale] (= LSE over keep logits − LSE over junk logits). > 0 means keep.
     */
    fun zeroShot(e: FloatArray): ZeroShot {
        var maxK = Float.NEGATIVE_INFINITY; var maxJ = Float.NEGATIVE_INFINITY
        val z = FloatArray(size)
        var best = 0
        for (i in 0 until size) {
            z[i] = logitScale * Vec.dot(e, vectors[i])
            if (keep[i]) maxK = maxOf(maxK, z[i]) else maxJ = maxOf(maxJ, z[i])
            if (z[i] > z[best]) best = i
        }
        var sK = 0.0; var sJ = 0.0
        for (i in 0 until size) if (keep[i]) sK += exp((z[i] - maxK).toDouble()) else sJ += exp((z[i] - maxJ).toDouble())
        val m = (maxK + ln(sK)) - (maxJ + ln(sJ))
        return ZeroShot(m.toFloat(), ids[best], keep[best])
    }

    companion object {
        const val ASSET = "smart/prompts_v2.bin"

        fun parse(bytes: ByteArray): PromptTable {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "NSPT") { "not a prompt table" }
            val version = b.short.toInt(); require(version == 2) { "prompt table v$version" }
            val n = b.short.toInt() and 0xFFFF
            val dim = b.short.toInt() and 0xFFFF
            val scale = b.float
            val ids = ArrayList<String>(n); val keep = BooleanArray(n)
            val vecs = Array(n) { i ->
                keep[i] = b.get().toInt() == 1
                val len = b.get().toInt() and 0xFF
                ids += String(ByteArray(len).also { b.get(it) }, Charsets.US_ASCII)
                FloatArray(dim) { Vec.fromHalf(b.short) }
            }
            return PromptTable(ids, keep, vecs, scale)
        }

        fun load(c: android.content.Context): PromptTable = parse(c.assets.open(ASSET).use { it.readBytes() })
    }
}
