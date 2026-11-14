package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicAuthFailureTest {
    @Test fun missingOrRejectedTokenRequiresLogin() {
        assertEquals(MusicAuthFailure.LOGIN_REQUIRED, classifyMusicAuthFailure("", { "" }) { error("must not check") })
        assertEquals(MusicAuthFailure.LOGIN_REQUIRED, classifyMusicAuthFailure("token", { "token" }) { true })
    }

    @Test fun streamFailureAndOfflineAccountCheckPreserveLogin() {
        assertEquals(MusicAuthFailure.STREAM_ERROR, classifyMusicAuthFailure("token", { "token" }) { false })
        assertEquals(MusicAuthFailure.STREAM_ERROR, classifyMusicAuthFailure("token", { "token" }) { throw IllegalStateException("offline") })
    }

    @Test fun ignoresResultWhenUserChangesAccount() {
        assertEquals(MusicAuthFailure.STALE, classifyMusicAuthFailure("old", { "new" }) { true })
    }
}
