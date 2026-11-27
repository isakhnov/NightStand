package com.nightstand.photos.smart

import android.graphics.Bitmap
import android.os.SystemClock

/** Turns a photo thumbnail into a unit 512-d embedding. Real: [TfLiteEmbedder]; tests inject a fake. */
interface Embedder : AutoCloseable {
    fun embed(thumb: Bitmap): FloatArray
    override fun close()
}

/**
 * MobileCLIP preprocessing (matches the Python validation): RGB, shortest side
 * to 256 (bilinear), centre crop 256×256, x/255, no mean/std.
 */
object Preprocess {
    const val SIZE = 256

    fun crop256(input: Bitmap): Bitmap {
        // Thumbnails may be HARDWARE bitmaps (no pixel access): copy to software first.
        val src = if (input.config == Bitmap.Config.HARDWARE) input.copy(Bitmap.Config.ARGB_8888, false) else input
        val s = SIZE.toFloat() / minOf(src.width, src.height)
        val w = maxOf(SIZE, Math.round(src.width * s)); val h = maxOf(SIZE, Math.round(src.height * s))
        val scaled = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        return Bitmap.createBitmap(scaled, (w - SIZE) / 2, (h - SIZE) / 2, SIZE, SIZE)
    }

    /** ARGB pixels (row-major, [SIZE]²) → float tensor, NHWC or NCHW. Pure. */
    fun toTensor(px: IntArray, nhwc: Boolean, size: Int = SIZE): FloatArray {
        val n = size * size
        val out = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = px[i]
            val r = ((p shr 16) and 0xFF) / 255f; val g = ((p shr 8) and 0xFF) / 255f; val b = (p and 0xFF) / 255f
            if (nhwc) { out[3 * i] = r; out[3 * i + 1] = g; out[3 * i + 2] = b }
            else { out[i] = r; out[n + i] = g; out[2 * n + i] = b }
        }
        return out
    }

    fun pixels(b: Bitmap): IntArray = IntArray(SIZE * SIZE).also { crop256(b).getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
}

/**
 * Owns the interpreter's lifetime (user requirement: memory). The model is
 * loaded only when something unscored needs embedding, and closed after
 * [idleMs] without use, when the Photos page hides, the screen goes off, or on
 * onTrimMemory. Photos already in the DB never touch it.
 *
 * Not thread-safe by itself: used only from SmartPhotos' single worker thread.
 */
class EmbedderHost(
    private val factory: () -> Embedder?,
    private val worker: Worker,
    private val idleMs: Long = 30_000L,
) {
    enum class State { UNLOADED, LOADED, FAILED }

    @Volatile var state = State.UNLOADED; private set
    @Volatile var lastLatencyMs = -1L; private set
    @Volatile var loads = 0; private set
    @Volatile var lastError: String? = null; private set
    private var embedder: Embedder? = null
    private val idleClose = Runnable { close("idle") }

    fun embed(thumb: Bitmap): FloatArray? {
        if (state == State.FAILED) return null
        val e = embedder ?: runCatching { factory() }.onFailure { lastError = it.toString() }.getOrNull().also {
            if (it == null) { state = State.FAILED; return null }
            embedder = it; loads++; state = State.LOADED
        }!!
        worker.remove(idleClose)
        val t0 = SystemClock.elapsedRealtime()
        val v = runCatching { Vec.normalize(e.embed(thumb)) }.onFailure { lastError = it.toString() }.getOrNull()
        lastLatencyMs = SystemClock.elapsedRealtime() - t0
        worker.postDelayed(idleClose, idleMs)
        return v
    }

    /** Release the interpreter (and its ~tens of MB). Safe to call any time on the worker. */
    fun close(@Suppress("UNUSED_PARAMETER") why: String = "") {
        worker.remove(idleClose)
        embedder?.let { runCatching { it.close() } }
        embedder = null
        if (state == State.LOADED) state = State.UNLOADED
    }

    /** Allow another load attempt (e.g. after the model was downloaded). */
    fun clearFailure() { if (state == State.FAILED) state = State.UNLOADED }
}

/** The single background thread SmartPhotos runs on (a HandlerThread in the app; synchronous in tests). */
interface Worker {
    fun post(r: Runnable)
    fun postDelayed(r: Runnable, ms: Long)
    fun remove(r: Runnable)
}
