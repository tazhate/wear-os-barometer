package com.example.wearbarometer.altitude

import kotlin.math.abs

/**
 * Recent pressure samples on the elapsedRealtime clock. An anchor altitude belongs to the moment of the fix,
 * so it must be paired with the pressure of that moment: during a climb the current pressure is already different.
 */
class PressureHistory(
    private val maxAgeMs: Long = 10 * 60_000L,
    private val maxGapMs: Long = 5_000L,
) {
    private val samples = ArrayDeque<Pair<Long, Float>>()

    @Synchronized
    fun add(timeMs: Long, pressureHpa: Float) {
        val last = samples.lastOrNull()
        if (last != null && timeMs < last.first) samples.clear()
        samples.addLast(timeMs to pressureHpa)
        while (samples.isNotEmpty() && samples.first().first < timeMs - maxAgeMs) samples.removeFirst()
    }

    /** Pressure closest to [timeMs], or null when there is no sample within [gapMs]. */
    @Synchronized
    fun at(timeMs: Long, gapMs: Long = maxGapMs): Float? =
        samples.minByOrNull { abs(it.first - timeMs) }
            ?.takeIf { abs(it.first - timeMs) <= gapMs }
            ?.second
}
