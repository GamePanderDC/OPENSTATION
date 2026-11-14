package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantPersonalitiesTest {
    @Test fun selectedPersonalityIsRecoveredFromSavedPrompt() {
        assertEquals(3, AssistantPersonalities.presets.size)
        for ((key, _, prompt) in AssistantPersonalities.presets) {
            assertEquals(key, AssistantPersonalities.keyForPrompt(prompt))
        }
        assertEquals("", AssistantPersonalities.keyForPrompt("Свой характер"))
    }
}
