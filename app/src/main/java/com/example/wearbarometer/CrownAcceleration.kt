package com.example.wearbarometer

import kotlin.math.abs
import kotlin.math.min

/**
 * Maps crown rotation to meters for calibration. The Pixel Watch 4 crown is smooth, without detents: it sends
 * an AXIS_SCROLL event every 12-16 ms with values in steps of 0.002. Measured gestures: a slow turn runs at
 * ~0.3 axis units/s, a fast flick at ~3.5 units/s and covers ~2 units.
 *
 * Meters per unit grow with the square of the speed: a slow turn moves ~10 m per unit for fine tuning,
 * a fast flick ~280 m, so any altitude is a few flicks away.
 */
class CrownAcceleration {
    private var lastEventMs = -1L
    private var speedUnitsPerS = 0.0

    /** Altitude change in meters for one event. [scrollAxis] is AXIS_SCROLL as reported, clockwise is negative. */
    fun meters(scrollAxis: Float, eventTimeMs: Long): Double {
        val units = -scrollAxis.toDouble()
        val dtMs = eventTimeMs - lastEventMs
        lastEventMs = eventTimeMs
        if (dtMs !in 1..GESTURE_GAP_MS) {
            // New gesture: no speed history, start fine.
            speedUnitsPerS = 0.0
        } else {
            // Events are noisy one by one, so smooth the speed over the last few.
            val instant = abs(units) * 1000.0 / dtMs
            speedUnitsPerS += SMOOTHING * (instant - speedUnitsPerS)
        }
        val relative = speedUnitsPerS / REFERENCE_SPEED_UNITS_PER_S
        val metersPerUnit = min(BASE_METERS_PER_UNIT * (1.0 + relative * relative), MAX_METERS_PER_UNIT)
        return units * metersPerUnit
    }

    companion object {
        const val BASE_METERS_PER_UNIT = 10.0
        const val REFERENCE_SPEED_UNITS_PER_S = 1.0
        const val MAX_METERS_PER_UNIT = 300.0
        const val GESTURE_GAP_MS = 150L
        const val SMOOTHING = 0.3
    }
}
