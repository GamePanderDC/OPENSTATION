package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreensaverIdlePolicyTest {
    @Test fun defaultAndDisabledTimeouts() {
        assertEquals(600_000L, ScreensaverIdlePolicy.delayMillis(ScreensaverIdlePolicy.DEFAULT_MINUTES)!!)
        assertNull(ScreensaverIdlePolicy.delayMillis(0))
        assertNull(ScreensaverIdlePolicy.delayMillis(-1))
    }

    @Test fun optionsWrapAndInvalidSettingsRecover() {
        assertEquals(15, ScreensaverIdlePolicy.nextMinutes(10))
        assertEquals(0, ScreensaverIdlePolicy.nextMinutes(60))
        assertEquals(10, ScreensaverIdlePolicy.nextMinutes(123))
    }
}
