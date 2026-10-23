package com.example.wearbarometer.altitude

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class AnchorSource { NONE, MANUAL, GNSS, QNH, DEM }

/**
 * Filter state. The barometer gives altitude up to an unknown weather offset:
 * altitude = isaAltitude(pressure) - offsetM. Only the offset is estimated.
 */
data class FilterState(
    val offsetM: Double,
    val varianceM2: Double,
    val timeMs: Long,
    val source: AnchorSource,
    val anchorTimeMs: Long,
) {
    val sigmaM: Double get() = sqrt(varianceM2)

    companion object {
        fun initial(nowMs: Long) = FilterState(
            offsetM = 0.0,
            varianceM2 = AltitudeFilter.MAX_VARIANCE_M2,
            timeMs = nowMs,
            source = AnchorSource.NONE,
            anchorTimeMs = 0L,
        )
    }
}

/** One-dimensional Kalman filter on the weather offset. Time is wall clock, because the state survives reboots. */
class AltitudeFilter(var state: FilterState) {

    /** Grow the uncertainty for the weather drift since the last step. */
    fun predict(nowMs: Long) {
        val dtHours = max(0L, nowMs - state.timeMs) / HOUR_MS
        val variance = (state.varianceM2 + DRIFT_M2_PER_HOUR * dtHours).coerceAtMost(MAX_VARIANCE_M2)
        state = state.copy(varianceM2 = variance, timeMs = max(nowMs, state.timeMs))
    }

    /**
     * Apply one offset measurement [measuredOffsetM] with standard deviation [sigmaM].
     * Returns false when the measurement is rejected as an outlier.
     */
    fun update(measuredOffsetM: Double, sigmaM: Double, source: AnchorSource, nowMs: Long): Boolean {
        predict(nowMs)
        val r = sigmaM * sigmaM
        val innovation = measuredOffsetM - state.offsetM
        // A manual calibration always wins: the user knows where they stand.
        if (source != AnchorSource.MANUAL && abs(innovation) > GATE_SIGMAS * sqrt(state.varianceM2 + r)) {
            return false
        }
        val gain = state.varianceM2 / (state.varianceM2 + r)
        val floor = if (source == AnchorSource.MANUAL) MIN_VARIANCE_MANUAL_M2 else MIN_VARIANCE_M2
        // The label names the anchor that defines the value, not a weak one that barely moved it.
        val dominant = gain >= LABEL_MIN_GAIN || state.source == AnchorSource.NONE
        state = state.copy(
            offsetM = state.offsetM + gain * innovation,
            varianceM2 = max((1 - gain) * state.varianceM2, floor),
            source = if (dominant) source else state.source,
            anchorTimeMs = if (dominant) nowMs else state.anchorTimeMs,
        )
        return true
    }

    /** Hard reset to a known altitude, without the outlier gate. */
    fun calibrate(pressureHpa: Double, knownAltitudeM: Double, nowMs: Long) {
        state = state.copy(varianceM2 = MAX_VARIANCE_M2)
        update(Atmosphere.offsetFromAltitude(pressureHpa, knownAltitudeM), MANUAL_SIGMA_M, AnchorSource.MANUAL, nowMs)
    }

    fun altitude(pressureHpa: Double): Double = Atmosphere.isaAltitude(pressureHpa) - state.offsetM

    companion object {
        const val HOUR_MS = 3_600_000.0

        /** Weather drift of the offset: about 3 m per sqrt(hour), i.e. ~0.35 hPa. Tunable. */
        const val DRIFT_M2_PER_HOUR = 9.0

        /** No anchor at all: the ISA offset can be ~100 m off (±12 hPa of weather). */
        const val MAX_VARIANCE_M2 = 100.0 * 100.0

        /** Anchor errors are correlated in time, so never trust the fused value below this. */
        const val MIN_VARIANCE_M2 = 2.0 * 2.0
        const val MIN_VARIANCE_MANUAL_M2 = 1.0 * 1.0

        const val MANUAL_SIGMA_M = 1.0
        const val GATE_SIGMAS = 5.0
        const val LABEL_MIN_GAIN = 0.3
    }
}
