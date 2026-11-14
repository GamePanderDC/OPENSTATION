package com.openstation.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EqBandSelectionTest {
    @Test fun savedBandsTakePriorityEvenForNamedPreset() {
        val saved = FloatArray(10) { it.toFloat() - 5f }
        assertArrayEquals(saved, EqBandSelection.resolve(saved) { FloatArray(10) }, 0f)
    }

    @Test fun missingOrInvalidBandsUsePreset() {
        val preset = FloatArray(10) { 1f }
        assertArrayEquals(preset, EqBandSelection.resolve(null) { preset }, 0f)
        assertArrayEquals(preset, EqBandSelection.resolve(floatArrayOf(1f)) { preset }, 0f)
        assertArrayEquals(preset, EqBandSelection.resolve(FloatArray(10) { Float.NaN }) { preset }, 0f)
    }

    @Test fun outOfRangeValuesAreLimited() {
        val result = EqBandSelection.resolve(FloatArray(10) { if (it % 2 == 0) 40f else -40f }) { FloatArray(10) }
        assertArrayEquals(FloatArray(10) { if (it % 2 == 0) 12f else -12f }, result, 0f)
    }

    @Test fun mapsActualHardwareBandFrequencyOntoTheCurve() {
        val frequencies = floatArrayOf(100f, 400f, 1600f)
        val gains = floatArrayOf(-4f, 2f, 6f)
        assertEquals(-1f, EqBandSelection.gainAtFrequency(frequencies, gains, 200f), 0.0001f)
        assertEquals(2f, EqBandSelection.gainAtFrequency(frequencies, gains, 400f), 0.0001f)
        assertEquals(6f, EqBandSelection.gainAtFrequency(frequencies, gains, 2000f), 0.0001f)
    }
}
