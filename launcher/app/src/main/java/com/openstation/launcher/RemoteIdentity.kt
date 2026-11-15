package com.openstation.launcher

import java.util.Locale
import java.util.UUID

internal object RemoteIdentity {
    val UUID_HID_SERVICE: UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
    val UUID_OMNI_VOICE_SERVICE: UUID = UUID.fromString("65fa9513-e8ca-4efe-b5ba-67b7c44101dc")

    fun isYandexRemoteName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = name.uppercase(Locale.ROOT)
        val vendor = normalized.contains("YNDX") || normalized.contains("YDX") || normalized.contains("YANDEX")
        return vendor && normalized.contains("REMOTE")
    }

    /**
     * Recognizes a BLE remote advertising packet or bonded device either by Yandex remote name,
     * OmniRCU Voice GATT service, or standard BLE HID-over-GATT service (0x1812) while rejecting
     * non-remote Yandex speakers/headsets.
     */
    fun isBleRemoteCandidate(name: String?, serviceUuids: List<UUID>?): Boolean {
        if (isYandexRemoteName(name)) return true
        val upper = name?.uppercase(Locale.ROOT).orEmpty()
        if (upper.contains("STATION") || upper.contains("СТАНЦИЯ")) return false
        val hasHidUuid = serviceUuids?.any { it == UUID_HID_SERVICE || it == UUID_OMNI_VOICE_SERVICE } == true
        if (hasHidUuid) return true
        if (upper.contains("YNDX") || upper.contains("YANDEX")) {
            return upper.contains("RCU") || upper.contains("REMOTE") || upper.contains("ПУЛЬТ")
        }
        return false
    }
}
