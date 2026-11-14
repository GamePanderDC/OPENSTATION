package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupAddressTest {
    @Test fun buildsAddressForLocalNetwork() {
        assertEquals("http://192.168.0.20:8080", SetupAddress.webUrl("192.168.0.20"))
        assertEquals("http://192.168.0.20:8080/#wizard", SetupAddress.wizardUrl("192.168.0.20"))
    }
    @Test fun neverOffersInaccessibleOrMalformedAddresses() {
        for (ip in listOf("", "127.0.0.1", "169.254.0.10", "0.0.0.0", "224.0.0.1", "192.168.999.1", "not-an-ip")) {
            assertNull(SetupAddress.wizardUrl(ip))
            assertNull(SetupAddress.webUrl(ip))
        }
    }
}
