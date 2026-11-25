package com.nightstand

import android.graphics.Typeface

/**
 * Colours for one frame. Day mimics iOS StandBy (white on black, orange
 * accent); night is ONE bright orange-red on black. The first device test
 * showed a dim maroon (#B3241B at 1% brightness) reads as muddy and too dark
 * next to an iPhone, whose night red is close to #FF3B1F, so the hue stays
 * bright and only window brightness (Prefs.nightBrightness) dims it.
 * Second device test: #FF3B1F was too orange → pure red.
 */
data class Palette(val night: Boolean, val red: Int = NightColor.PURE.argb) {
    val background = 0xFF000000.toInt()
    val primary = if (night) red else 0xFFF5F5F7.toInt()
    /** Same hue, lower alpha: hierarchy without introducing a second colour. */
    val secondary = if (night) (red and 0x00FFFFFF) or 0xA6000000.toInt() else 0xFF8E8E93.toInt()
    val accent = if (night) red else 0xFFFF9F0A.toInt()
    /** Analog second hand. */
    val second = if (night) red else 0xFFFF9F0A.toInt()
    /** [red] at a given alpha (0..255), for tracks and placeholders. */
    fun redAlpha(a: Int) = (red and 0x00FFFFFF) or (a shl 24)

    /**
     * Photos/album art at night: luminance mapped onto the night colour only,
     * so images stay recognisable but add no other hue.
     */
    fun nightImageFilter(alpha: Float): android.graphics.ColorMatrixColorFilter {
        val r = ((red shr 16) and 0xFF) / 255f
        val g = ((red shr 8) and 0xFF) / 255f
        val b = (red and 0xFF) / 255f
        return android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
            0.299f * r, 0.587f * r, 0.114f * r, 0f, 0f,
            0.299f * g, 0.587f * g, 0.114f * g, 0f, 0f,
            0.299f * b, 0.587f * b, 0.114f * b, 0f, 0f,
            0f, 0f, 0f, alpha, 0f,
        )))
    }

    companion object {
        val DAY = Palette(false)
        fun night(c: NightColor = NightColor.PURE) = Palette(true, c.argb)
    }
}

/**
 * Night colours offered in Look. Apple publishes no StandBy value; its filter
 * appears to keep only the red channel, hence PURE as default. 0.2's #FF3B1F
 * read as orange next to the iPhone; AMBER is there for those who prefer it.
 */
enum class NightColor(val argb: Int, val label: String) {
    PURE(0xFFFF0000.toInt(), "Pure red"),
    DEEP(0xFFD00000.toInt(), "Deep"),
    WARM(0xFFFF2200.toInt(), "Warm"),
    AMBER(0xFFFF6A00.toInt(), "Amber"),
}

/** Heavy, rounded-feeling weights closest to iOS StandBy's SF Pro Rounded. */
object Fonts {
    val heavy: Typeface = Typeface.create(Typeface.DEFAULT, 800, false)
    val bold: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    val semibold: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
}

object BurnIn {
    /**
     * OLED burn-in protection: walk a small square, one step per minute, so
     * no pixel draws the same static edge all night.
     */
    fun shiftFor(minute: Int, amplitude: Float): Pair<Float, Float> {
        val steps = arrayOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
        val (a, b) = steps[minute % steps.size]
        return a * amplitude to b * amplitude
    }
}
