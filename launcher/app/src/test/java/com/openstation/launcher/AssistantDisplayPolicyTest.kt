package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantDisplayPolicyTest {
    @Test fun replyDisappearsAfterIdleTimeout() {
        assertEquals("Ответ", AssistantDisplayPolicy.text("idle", "", "Ответ", "", "", 1000, 15_999))
        assertEquals("", AssistantDisplayPolicy.text("idle", "", "Ответ", "Старый вопрос", "", 1000, 16_000))
    }

    @Test fun activeResponseAndErrorsRemainVisible() {
        assertEquals("Ответ", AssistantDisplayPolicy.text("speaking", "Ответ", "", "", "", 0, 90_000))
        assertEquals("Ошибка: сеть", AssistantDisplayPolicy.text("error", "", "", "", "сеть", 0, 90_000))
        assertEquals("", AssistantDisplayPolicy.text("idle", "", "Старый ответ", "", "", 0, 90_000))
    }
}
