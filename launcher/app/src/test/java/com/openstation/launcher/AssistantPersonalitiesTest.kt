package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPersonalitiesTest {
    @Test fun selectedPersonalityIsRecoveredFromSavedPrompt() {
        assertEquals(3, AssistantPersonalities.presets.size)
        for ((key, _, prompt) in AssistantPersonalities.presets) {
            assertEquals(key, AssistantPersonalities.keyForPrompt(prompt))
        }
        val legacyUniversal =
            "Ты — голосовой ассистент Алиса на умной колонке Яндекс Станция Макс с открытой прошивкой OpenStation Max (полный root-доступ и живой доступ в интернет). Отвечай по-русски живо."
        assertEquals("universal", AssistantPersonalities.keyForPrompt(legacyUniversal))
        assertEquals("", AssistantPersonalities.keyForPrompt("Свой характер"))
        assertEquals("", AssistantPersonalities.keyForPrompt(""))
    }

    @Test fun presetAndCustomNamesResolveVoicesAndGender() {
        assertFalse(AssistantPersonalities.isLikelyMaleName("Алиса"))
        assertFalse(AssistantPersonalities.isLikelyMaleName("Пятница"))
        assertFalse(AssistantPersonalities.isLikelyMaleName("Вега"))
        assertFalse(AssistantPersonalities.isLikelyMaleName("Алёна"))

        assertTrue(AssistantPersonalities.isLikelyMaleName("Джарвис"))
        assertTrue(AssistantPersonalities.isLikelyMaleName("Макс"))
        assertTrue(AssistantPersonalities.isLikelyMaleName("Артём"))
        assertTrue(AssistantPersonalities.isLikelyMaleName("Orion"))

        assertEquals("Puck", AssistantPersonalities.defaultVoiceForName("Джарвис"))
        assertEquals("Aoede", AssistantPersonalities.defaultVoiceForName("Вега"))
        assertTrue(AssistantPersonalities.isMaleVoice("Charon"))
        assertFalse(AssistantPersonalities.isMaleVoice("Aoede"))
    }
}
