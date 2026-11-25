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
data class Palette(val night: Boolean) {
    val background = 0xFF000000.toInt()
    val primary = if (night) RED else 0xFFF5F5F7.toInt()
    val secondary = if (night) RED_SOFT else 0xFF8E8E93.toInt()
    val accent = if (night) RED else 0xFFFF9F0A.toInt()
    /** Analog second hand. */
    val second = if (night) RED else 0xFFFF9F0A.toInt()

    companion object {
        /**
         * Pure red. Apple publishes no value; StandBy's night filter appears to
         * keep only the red channel. 0.2 used #FF3B1F, which read as orange
         * next to the iPhone (phone cameras exaggerate this, so judge by eye).
         */
        const val RED = 0xFFFF0000.toInt()
        /** Same hue, lower alpha: hierarchy without introducing a second colour. */
        const val RED_SOFT = 0xA6FF0000.toInt()
        val DAY = Palette(false)
        val NIGHT = Palette(true)
    }
}

/** Heavy, rounded-feeling weights closest to iOS StandBy's SF Pro Rounded. */
object Fonts {
    val heavy: Typeface = Typeface.create(Typeface.DEFAULT, 800, false)
    val bold: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    val semibold: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    val regular: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
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
