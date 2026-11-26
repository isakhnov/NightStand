package com.nightstand.photos.smart

import kotlin.math.sqrt

/** Small vector helpers for 512-d unit embeddings. Pure; unit-tested. */
object Vec {
    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Cosine of two vectors (no assumption they are normalised). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        var ab = 0f; var aa = 0f; var bb = 0f
        for (i in a.indices) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa == 0f || bb == 0f) 0f else ab / sqrt(aa * bb)
    }

    fun normalize(a: FloatArray): FloatArray {
        var n = 0f
        for (v in a) n += v * v
        n = sqrt(n)
        return if (n == 0f) a.copyOf() else FloatArray(a.size) { a[it] / n }
    }

    // --- float16 storage (1 KB per 512-d embedding) ----------------------------

    fun toHalf(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        val mant = bits and 0x7FFFFF
        if (exp == 0xFF) return (sign or 0x7C00 or (if (mant != 0) 0x200 else 0)).toShort() // inf / nan
        val e = exp - 127 + 15
        if (e >= 0x1F) return (sign or 0x7C00).toShort() // overflow → inf
        if (e <= 0) {
            if (e < -10) return sign.toShort() // underflow → ±0
            val m = mant or 0x800000
            val shift = 14 - e
            var h = m ushr shift
            if ((m ushr (shift - 1)) and 1 != 0) h += 1 // round half up
            return (sign or h).toShort()
        }
        var h = sign or (e shl 10) or (mant ushr 13)
        if (mant and 0x1000 != 0) h += 1 // round half up (carries into exponent correctly)
        return h.toShort()
    }

    fun fromHalf(h: Short): Float {
        val x = h.toInt() and 0xFFFF
        val sign = (x and 0x8000) shl 16
        val exp = (x ushr 10) and 0x1F
        val mant = x and 0x3FF
        val bits = when {
            exp == 0 && mant == 0 -> sign
            exp == 0 -> { // subnormal
                var m = mant; var e = -14
                while (m and 0x400 == 0) { m = m shl 1; e-- }
                sign or ((e + 127) shl 23) or ((m and 0x3FF) shl 13)
            }
            exp == 0x1F -> sign or 0x7F800000 or (mant shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }

    /** Little-endian float16 blob. */
    fun toBlob(v: FloatArray): ByteArray {
        val out = ByteArray(v.size * 2)
        for (i in v.indices) {
            val h = toHalf(v[i]).toInt()
            out[2 * i] = (h and 0xFF).toByte(); out[2 * i + 1] = ((h ushr 8) and 0xFF).toByte()
        }
        return out
    }

    fun fromBlob(b: ByteArray): FloatArray = FloatArray(b.size / 2) {
        fromHalf(((b[2 * it].toInt() and 0xFF) or ((b[2 * it + 1].toInt() and 0xFF) shl 8)).toShort())
    }

    /** Raw float32 blob (head weights: precision matters more than size there). */
    fun toF32Blob(v: FloatArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putFloat(it) }
        return bb.array()
    }

    fun fromF32Blob(b: ByteArray): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.getFloat() }
    }
}
