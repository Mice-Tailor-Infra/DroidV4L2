package com.cagedbird.droidv4l2

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    data class InterfaceInfo(val name: String, val ip: String)

    fun getAllIpAddresses(): List<InterfaceInfo> {
        val ips = mutableListOf<InterfaceInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue

                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (addr is Inet4Address) {
                        // Human readable name (e.g. wlan0, eth0)
                        ips.add(
                                InterfaceInfo(intf.displayName ?: intf.name, addr.hostAddress ?: "")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ips
    }
}
