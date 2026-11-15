package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexMusicAudioQualityTest {
    @Test fun normalizesLosslessAndUnknownQualitiesToNormal() {
        assertEquals("normal", YandexMusicApi.normalizeAudioQuality("lossless"))
        assertEquals("normal", YandexMusicApi.normalizeAudioQuality("flac"))
        assertEquals("normal", YandexMusicApi.normalizeAudioQuality("normal"))
        assertEquals("normal", YandexMusicApi.normalizeAudioQuality(null))
        assertEquals("normal", YandexMusicApi.normalizeAudioQuality(""))
    }

    @Test fun migratesStoredLosslessPreferenceAndAvoidsUnnecessaryWrites() {
        var persisted: String? = null
        var writes = 0

        assertEquals("normal", YandexMusicApi.migrateQualityPreference("lossless") {
            persisted = it
            writes++
        })
        assertEquals("normal", persisted)
        assertEquals(1, writes)

        writes = 0
        persisted = "normal"
        assertEquals("normal", YandexMusicApi.migrateQualityPreference("normal") {
            persisted = it
            writes++
        })
        assertEquals(0, writes)

        assertEquals("normal", YandexMusicApi.migrateQualityPreference(null) {
            persisted = it
            writes++
        })
        assertEquals(0, writes)
    }

    @Test fun codecsNeverRequestFlacEvenIfLosslessWasPassed() {
        val codecs = YandexMusicApi.codecsForAudioQuality("lossless")
        assertTrue(codecs.contains("mp3"))
        assertTrue(codecs.contains("aac"))
        assertFalse(codecs.any { it.contains("flac", ignoreCase = true) })
    }
}
