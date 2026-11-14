package com.openstation.launcher

internal object SetupAddress {
    fun isUsableIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        val bytes = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return bytes[0] in 1..223 && bytes[0] != 127 && !(bytes[0] == 169 && bytes[1] == 254)
    }

    fun webUrl(ip: String): String? = if (isUsableIpv4(ip)) "http://$ip:8080" else null

    fun wizardUrl(ip: String): String? = webUrl(ip)?.plus("/#wizard")
}
