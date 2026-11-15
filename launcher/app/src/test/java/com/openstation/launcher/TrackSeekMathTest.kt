package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSeekMathTest {
    @Test
    fun seekClampBoundsWorkForForwardAndBackward() {
        // Backward past 0 clamps cleanly to 0
        assertEquals(0L, computeClampedSeekTargetMs(2500L, 180000L, -5000L))
        // Forward past end preserves 800ms tail so completion event isn't accidentally bypassed
        assertEquals(179200L, computeClampedSeekTargetMs(175000L, 180000L, 12000L))
        // Mid-track relative jumps add precisely
        assertEquals(64200L, computeClampedSeekTargetMs(60000L, 180000L, 4200L))
        assertEquals(55800L, computeClampedSeekTargetMs(60000L, 180000L, -4200L))
        // Unknown duration still clamps non-negative
        assertEquals(8500L, computeClampedSeekTargetMs(5000L, 0L, 3500L))
        assertEquals(0L, computeClampedSeekTargetMs(2000L, 0L, -3500L))
    }

    @Test
    fun seekDeltaAndTimeFormattingMatchDisplay() {
        assertEquals("", formatSeekDelta(0))
        assertEquals("+0:08", formatSeekDelta(8400))
        assertEquals("-0:12", formatSeekDelta(-12000))
        assertEquals("+1:05", formatSeekDelta(65000))
        assertEquals("0:00", formatTimeMs(0L))
        assertEquals("3:24", formatTimeMs(204500L))
    }
}
