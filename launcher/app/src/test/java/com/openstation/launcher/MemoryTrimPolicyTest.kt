package com.openstation.launcher

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTrimPolicyTest {

    @Test
    fun uiHiddenTrimsCachesWithoutReleasingOrb() {
        val d = LiquidGlassKit.computeMemoryTrimDecision(
            level = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            allowReleaseOrb = true,
            assistantState = "idle"
        )
        assertTrue(d.clearStagePhoto)
        assertEquals(1024, d.cacheCapKb)
        assertFalse(d.releaseOrbSheets)
    }

    @Test
    fun runningCriticalEvictsAllAndReleasesIdleOrb() {
        val d = LiquidGlassKit.computeMemoryTrimDecision(
            level = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            allowReleaseOrb = true,
            assistantState = "idle"
        )
        assertTrue(d.clearStagePhoto)
        assertEquals(0, d.cacheCapKb)
        assertTrue(d.releaseOrbSheets)
    }

    @Test
    fun moderateDoesNotReleaseOrbWhileAssistantActiveOrForeground() {
        val activeAssistant = LiquidGlassKit.computeMemoryTrimDecision(
            level = ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            allowReleaseOrb = true,
            assistantState = "listening"
        )
        assertTrue(activeAssistant.clearStagePhoto)
        assertEquals(0, activeAssistant.cacheCapKb)
        assertFalse(activeAssistant.releaseOrbSheets)

        val foregroundUi = LiquidGlassKit.computeMemoryTrimDecision(
            level = ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            allowReleaseOrb = false,
            assistantState = "idle"
        )
        assertFalse(foregroundUi.releaseOrbSheets)
    }

    @Test
    fun gentleRunningModerateLeavesCachesIntact() {
        val d = LiquidGlassKit.computeMemoryTrimDecision(
            level = ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            allowReleaseOrb = true,
            assistantState = "idle"
        )
        assertFalse(d.clearStagePhoto)
        assertNull(d.cacheCapKb)
        assertFalse(d.releaseOrbSheets)
    }
}
