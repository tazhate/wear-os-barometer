package com.example.wearbarometer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrownAccelerationTest {

    /** A gesture of [events] equal events [stepMs] apart, each [axis] units. Returns total meters. */
    private fun gesture(crown: CrownAcceleration, startMs: Long, events: Int, stepMs: Long, axis: Float): Double =
        (0 until events).sumOf { crown.meters(axis, startMs + it * stepMs) }

    @Test
    fun slowTurnIsFine() {
        // Measured: 2.7 s, 152 events, 0.9 units in total.
        val meters = gesture(CrownAcceleration(), 0L, 152, 18L, -0.9f / 152)
        assertEquals(10.0, meters, 2.0)
    }

    @Test
    fun fastFlickCoversHundredsOfMeters() {
        // Measured: 0.6 s, 46 events, 2.1 units in total.
        val meters = gesture(CrownAcceleration(), 0L, 46, 13L, -2.1f / 46)
        assertTrue("flick gave $meters m", meters in 150.0..400.0)
    }

    @Test
    fun clockwiseIncreasesCounterClockwiseDecreases() {
        assertTrue(CrownAcceleration().meters(-0.01f, 0L) > 0)
        assertTrue(CrownAcceleration().meters(0.01f, 0L) < 0)
    }

    @Test
    fun pauseResetsTheSpeed() {
        val crown = CrownAcceleration()
        gesture(crown, 0L, 46, 13L, -0.046f)
        val afterPause = crown.meters(-0.01f, 10_000L)
        assertEquals(0.1, afterPause, 1e-6)
    }
}
