package com.nightstand

import kotlin.math.sqrt

/**
 * "Motion to wake": a short jolt of the nightstand (the phone isn't picked up,
 * just bumped) relative to a slow gravity estimate. Pure; unit-tested.
 * Threshold/cooldown tuned to ignore sensor noise and a tap on the table.
 */
class NudgeDetector(
    private val threshold: Float = 0.45f,   // m/s² away from gravity
    private val cooldownMs: Long = 2000,
) {
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var primed = false
    private var lastNudge = Long.MIN_VALUE / 2

    /** Feed one accelerometer sample; true when it counts as a nudge. */
    fun feed(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        if (!primed) { gx = x; gy = y; gz = z; primed = true; return false }
        val dx = x - gx; val dy = y - gy; val dz = z - gz
        val jolt = sqrt(dx * dx + dy * dy + dz * dz)
        val a = 0.1f
        gx += a * dx; gy += a * dy; gz += a * dz
        if (jolt > threshold && nowMs - lastNudge > cooldownMs) { lastNudge = nowMs; return true }
        return false
    }

    fun reset() { primed = false }
}
