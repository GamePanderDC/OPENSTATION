package com.openstation.launcher

object ScreensaverIdlePolicy {
    const val DEFAULT_MINUTES = 10
    private val options = intArrayOf(0, 5, 10, 15, 30, 60)

    fun delayMillis(minutes: Int): Long? =
        if (minutes in options && minutes > 0) minutes.toLong() * 60_000L else null

    fun nextMinutes(minutes: Int): Int {
        val current = options.indexOf(minutes)
        return if (current < 0) DEFAULT_MINUTES else options[(current + 1) % options.size]
    }
}
