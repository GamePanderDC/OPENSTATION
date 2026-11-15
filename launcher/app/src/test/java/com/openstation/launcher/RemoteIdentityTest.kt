package com.openstation.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RemoteIdentityTest {
    @Test fun recognizesKnownNamesWithoutAcceptingGenericHidDevices() {
        assertFalse(RemoteIdentity.isYandexRemoteName("YNDX-0006"))
        assertTrue(RemoteIdentity.isYandexRemoteName("Yandex Remote 2"))
        assertTrue(RemoteIdentity.isYandexRemoteName("Yandex Remote"))
        assertFalse(RemoteIdentity.isYandexRemoteName("Wireless Remote"))
        assertFalse(RemoteIdentity.isYandexRemoteName("Yandex Station Max"))
        assertFalse(RemoteIdentity.isYandexRemoteName("Keyboard"))
        assertFalse(RemoteIdentity.isYandexRemoteName(null))
    }

    @Test fun candidateFilterAcceptsRemoteUuidsAndNamesWhileRejectingStation() {
        assertTrue(
            RemoteIdentity.isBleRemoteCandidate(
                null,
                listOf(RemoteIdentity.UUID_OMNI_VOICE_SERVICE)
            )
        )
        assertTrue(
            RemoteIdentity.isBleRemoteCandidate(
                "BLE Device",
                listOf(RemoteIdentity.UUID_HID_SERVICE)
            )
        )
        assertTrue(RemoteIdentity.isBleRemoteCandidate("YNDX-0006 Пульт", null))
        assertTrue(RemoteIdentity.isBleRemoteCandidate("Yandex RCU", null))
        assertFalse(
            RemoteIdentity.isBleRemoteCandidate(
                "Yandex Station Max",
                listOf(RemoteIdentity.UUID_HID_SERVICE)
            )
        )
        assertFalse(
            RemoteIdentity.isBleRemoteCandidate(
                "Станция Макс",
                listOf(RemoteIdentity.UUID_OMNI_VOICE_SERVICE)
            )
        )
        assertFalse(
            RemoteIdentity.isBleRemoteCandidate(
                "Random Speaker",
                listOf(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))
            )
        )
    }
}
