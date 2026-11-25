package com.nightstand.photos

/**
 * Where to draw a photo inside the right-hand panel at time [t] (ms since it
 * appeared). The user asked for "pan the image from original to fill":
 *
 *  1. zoom phase ([zoomMs]): from the whole original photo fitted in the panel
 *     (letterboxed) to the panel filled edge-to-edge, centred;
 *  2. pan phase (until [totalMs]): slowly slide across the cropped-off part,
 *     from the centre to one side ([panDir] = -1 or +1), so the parts the fill
 *     cut off still get shown.
 *
 * Pure maths; unit-tested.
 */
object KenBurns {
    data class Frame(val scale: Float, val left: Float, val top: Float)

    fun frame(
        imgW: Int, imgH: Int, panelW: Float, panelH: Float,
        t: Long, zoomMs: Long, totalMs: Long, panDir: Int,
    ): Frame {
        val fit = minOf(panelW / imgW, panelH / imgH)
        val fill = maxOf(panelW / imgW, panelH / imgH)
        val z = ease((t.toFloat() / zoomMs).coerceIn(0f, 1f))
        val scale = fit + (fill - fit) * z
        val w = imgW * scale; val h = imgH * scale
        var left = (panelW - w) / 2
        var top = (panelH - h) / 2
        if (t > zoomMs && totalMs > zoomMs) {
            val pan = ease(((t - zoomMs).toFloat() / (totalMs - zoomMs)).coerceIn(0f, 1f))
            // Overflow on each axis at full fill; slide half of it (centre → edge).
            val ox = (w - panelW).coerceAtLeast(0f) / 2
            val oy = (h - panelH).coerceAtLeast(0f) / 2
            left += panDir * ox * pan
            top += panDir * oy * pan
        }
        return Frame(scale, left, top)
    }

    private fun ease(x: Float) = x * x * (3 - 2 * x) // smoothstep
}
