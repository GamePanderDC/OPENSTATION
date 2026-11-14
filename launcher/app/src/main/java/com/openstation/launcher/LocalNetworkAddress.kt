package com.openstation.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

internal object LocalNetworkAddress {
    fun find(context: Context): String {
        try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (manager != null) {
                val networks = LinkedHashSet<Network>()
                manager.activeNetwork?.let { networks.add(it) }
                networks.addAll(manager.allNetworks)
                for (network in networks) {
                    val caps = manager.getNetworkCapabilities(network) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
                    val addresses = manager.getLinkProperties(network)?.linkAddresses ?: continue
                    for (link in addresses) {
                        val address = link.address
                        if (address is Inet4Address) {
                            val ip = address.hostAddress ?: continue
                            if (SetupAddress.isUsableIpv4(ip)) return ip
                        }
                    }
                }
                return ""
            }
        } catch (_: Exception) { }
        // Fall back only if the Android connectivity service is unavailable.
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback ||
                    !(iface.name.startsWith("wlan") || iface.name.startsWith("eth"))) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address) {
                        val ip = address.hostAddress ?: continue
                        if (SetupAddress.isUsableIpv4(ip)) return ip
                    }
                }
            }
        } catch (_: Exception) { }
        return ""
    }
}
