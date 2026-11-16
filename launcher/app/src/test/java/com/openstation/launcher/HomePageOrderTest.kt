package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePageOrderTest {
    @Test fun navigationKeepsHomeBetweenMusicAndStation() {
        assertEquals(0, HomePageOrder.position(1))
        assertEquals(1, HomePageOrder.position(0))
        assertEquals(2, HomePageOrder.position(2))
        assertEquals(1, HomePageOrder.adjacent(0, -1))
        assertEquals(2, HomePageOrder.adjacent(0, 1))
        assertEquals(0, HomePageOrder.adjacent(1, 1))
        assertEquals(0, HomePageOrder.adjacent(2, -1))
        assertNull(HomePageOrder.adjacent(1, -1))
        assertNull(HomePageOrder.adjacent(2, 1))
    }
}
