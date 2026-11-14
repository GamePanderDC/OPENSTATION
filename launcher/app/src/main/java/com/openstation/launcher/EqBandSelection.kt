package com.openstation.launcher

import kotlin.math.ln

internal object EqBandSelection {
    fun resolve(savedBands: FloatArray?, presetBands: () -> FloatArray): FloatArray =
        if (savedBands != null && savedBands.size >= 10 && savedBands.take(10).all { it.isFinite() }) {
            FloatArray(10) { savedBands[it].coerceIn(-12f, 12f) }
        } else {
            presetBands()
        }

    fun gainAtFrequency(frequencies: FloatArray, gains: FloatArray, hz: Float): Float {
        if (frequencies.isEmpty() || gains.size < frequencies.size || !hz.isFinite()) return 0f
        if (hz <= frequencies.first()) return gains.first()
        if (hz >= frequencies.last()) return gains[frequencies.lastIndex]
        for (i in 1 until frequencies.size) {
            if (hz <= frequencies[i]) {
                val low = frequencies[i - 1].toDouble()
                val high = frequencies[i].toDouble()
                val position = (ln(hz.toDouble() / low) / ln(high / low)).toFloat()
                return gains[i - 1] + (gains[i] - gains[i - 1]) * position
            }
        }
        return gains[frequencies.lastIndex]
    }
}
