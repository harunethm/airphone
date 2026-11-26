package com.scylla.airphone

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Gyro -> cursor counts. Horizontal = rotation around the real-world vertical (gyro · gravity),
 * so turning left/right works the same whether the phone is held flat or tilted up.
 * Vertical = rotation around the phone's X axis (stays horizontal in portrait).
 */
class AirMotion(var gain: Float = DEFAULT_GAIN) {
    private val up = floatArrayOf(0f, 0f, 1f)
    private var lastNs = 0L
    private var accX = 0f
    private var accY = 0f

    fun reset() {
        lastNs = 0L
        accX = 0f
        accY = 0f
    }

    /** Gravity or raw accelerometer sample (m/s², device frame). Low-passed so the accelerometer fallback is usable. */
    fun gravity(v: FloatArray) {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (n < 1f) return
        for (i in 0..2) up[i] = up[i] * 0.8f + v[i] / n * 0.2f
        val m = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2])
        for (i in 0..2) up[i] /= m
    }

    /** Gyro sample (rad/s) at [timeNs]; returns whole cursor counts to send (dx, dy). */
    fun gyro(w: FloatArray, timeNs: Long): Pair<Int, Int> {
        val dt = (timeNs - lastNs) / 1e9f
        lastNs = timeNs
        if (dt <= 0f || dt > 0.1f) return 0 to 0 // first sample or a gap
        val yaw = w[0] * up[0] + w[1] * up[1] + w[2] * up[2]
        accX -= deadZone(yaw) * gain * dt
        accY -= deadZone(w[0]) * gain * dt
        val dx = accX.toInt()
        val dy = accY.toInt()
        accX -= dx // keep the fraction so slow motion isn't lost
        accY -= dy
        return dx.coerceIn(-127, 127) to dy.coerceIn(-127, 127)
    }

    // ponytail: fixed dead zone kills hand tremor/gyro bias; expose as a setting if some phone drifts more.
    private fun deadZone(r: Float) = if (abs(r) < DEAD_ZONE) 0f else r - sign(r) * DEAD_ZONE

    companion object {
        const val DEFAULT_GAIN = 1800f // cursor counts per radian
        const val DEAD_ZONE = 0.03f // rad/s
    }
}
