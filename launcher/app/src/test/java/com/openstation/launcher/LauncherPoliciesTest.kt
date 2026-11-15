package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherPoliciesTest {
    @Test
    fun stopScanResetsSearchingStateOnly() {
        assertEquals("idle" to "", resolveStoppedScanState("searching", connected = false))
        assertEquals("connected" to "Пульт подключён", resolveStoppedScanState("searching", connected = true))
        assertNull(resolveStoppedScanState("idle", connected = false))
        assertNull(resolveStoppedScanState("pairing", connected = false))
        assertNull(resolveStoppedScanState("connected", connected = true))
    }

    @Test
    fun duckStateReflectsBothAssistantAndScrub() {
        assertFalse(combineDuckState(assistantDucked = false, scrubDucked = false))
        assertTrue(combineDuckState(assistantDucked = true, scrubDucked = false))
        assertTrue(combineDuckState(assistantDucked = false, scrubDucked = true))
        assertTrue(combineDuckState(assistantDucked = true, scrubDucked = true))
    }

    @Test
    fun wallpaperThemesContainUniqueIdsAndScenicNebulaPack() {
        val themes = LiquidGlassKit.THEMES
        val ids = themes.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        val nebulaIds = listOf(
            "nebula_space",
            "nebula_forest",
            "nebula_desert",
            "nebula_field",
            "nebula_stage"
        )
        for (id in nebulaIds) {
            val found = themes.firstOrNull { it.id == id }
            assertTrue("Missing theme $id", found != null)
            assertTrue("Expected drawableRes for $id", found!!.drawableRes != 0)
        }
    }

    @Test
    fun nativeFullHdWallpaperDimensionsAndSwatchSampling() {
        assertEquals(1920, LiquidGlassKit.SHARP_WIDTH)
        assertEquals(1080, LiquidGlassKit.SHARP_HEIGHT)
        assertEquals(8, LiquidGlassKit.computeSampleSizeForTarget(1920, 1080, 96))
        assertEquals(1, LiquidGlassKit.computeSampleSizeForTarget(1920, 1080, 1080))
    }

    @Test
    fun spatialGlassMathConvertsTopLeftRectToGlCenterAndHalfSize() {
        val r = SpatialGlassMath.toGlLensRect(
            leftPx = 100f,
            topPx = 80f,
            rightPx = 300f,
            bottomPx = 180f,
            screenHeightPx = 1080f
        )
        assertEquals(200f, r[0], 0.001f)
        assertEquals(950f, r[1], 0.001f)
        assertEquals(100f, r[2], 0.001f)
        assertEquals(50f, r[3], 0.001f)
        assertEquals(1.055f, SpatialGlassMath.computeSpringScale(focused = true, pressed = false), 0.0001f)
        assertEquals(0.965f, SpatialGlassMath.computeSpringScale(focused = true, pressed = true), 0.0001f)
        assertEquals(1.0f, SpatialGlassMath.computeSpringScale(focused = false, pressed = false), 0.0001f)
    }

    @Test
    fun controlCenterLabelsMapKnownModesAndFallBackSafely() {
        assertEquals("Радуга", ControlCenterLabels.ringLabel("rainbow"))
        assertEquals("Выкл", ControlCenterLabels.ringLabel("off"))
        assertEquals("custom_ring", ControlCenterLabels.ringLabel("custom_ring"))
        assertEquals("Колонка", ControlCenterLabels.routeLabel("speakers"))
        assertEquals("HDMI", ControlCenterLabels.routeLabel("hdmi"))
        assertEquals("Авто", ControlCenterLabels.routeLabel("unknown"))
        assertEquals("Бас+", ControlCenterLabels.eqLabel("bass_boost"))
        assertEquals("Баланс", ControlCenterLabels.eqLabel("balanced"))
    }
}
