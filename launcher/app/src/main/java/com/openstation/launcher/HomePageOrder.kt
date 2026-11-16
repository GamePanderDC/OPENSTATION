package com.openstation.launcher

/** Logical page ids stay stable while their visual order is music, home, station. */
object HomePageOrder {
    private val pages = intArrayOf(1, 0, 2)

    fun position(page: Int): Int = pages.indexOf(page).also { require(it >= 0) }

    fun adjacent(page: Int, direction: Int): Int? {
        require(direction == -1 || direction == 1)
        return pages.getOrNull(position(page) + direction)
    }
}
