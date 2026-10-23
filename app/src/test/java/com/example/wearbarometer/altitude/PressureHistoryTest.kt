package com.example.wearbarometer.altitude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PressureHistoryTest {

    @Test
    fun returnsSampleClosestToFixTime() {
        val history = PressureHistory()
        history.add(1_000L, 1000.0f)
        history.add(2_000L, 999.9f)
        history.add(3_000L, 999.8f)
        assertEquals(999.9f, history.at(2_200L))
    }

    @Test
    fun climbAfterTheFixDoesNotLeakIntoIt() {
        val history = PressureHistory()
        history.add(0L, 1000.0f)
        // 90 s later the user is ~30 m higher.
        history.add(90_000L, 996.4f)
        assertEquals(1000.0f, history.at(1_000L))
    }

    @Test
    fun noSampleNearTheFixMeansNoAnchor() {
        val history = PressureHistory(maxGapMs = 5_000L)
        history.add(0L, 1000.0f)
        assertNull(history.at(60_000L))
        assertEquals(1000.0f, history.at(60_000L, gapMs = 60_000L))
    }

    @Test
    fun oldSamplesAreDropped() {
        val history = PressureHistory(maxAgeMs = 10_000L)
        history.add(0L, 1000.0f)
        history.add(20_000L, 999.0f)
        assertNull(history.at(0L))
    }

    @Test
    fun clockGoingBackResetsHistory() {
        val history = PressureHistory()
        history.add(50_000L, 1000.0f)
        history.add(1_000L, 990.0f)
        assertEquals(990.0f, history.at(1_000L))
        assertNull(history.at(50_000L))
    }
}
