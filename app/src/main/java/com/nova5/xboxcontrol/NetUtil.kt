package com.nova5.xboxcontrol

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** One IPv4 subnet the phone is on, and the Android Network to reach it through (null = default routing). */
data class LocalNet(val network: Network?, val addr: Inet4Address, val prefix: Int)

fun ipToInt(a: InetAddress): Int = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
fun intToIp(v: Int): String = "${v ushr 24 and 0xFF}.${v ushr 16 and 0xFF}.${v ushr 8 and 0xFF}.${v and 0xFF}"

/**
 * Subnets on Wi-Fi / Ethernet networks (via ConnectivityManager, so sockets can be bound to
 * them even when they have no internet), plus any other up interface such as the phone's own
 * hotspot or USB tethering (default routing).
 */
@Suppress("DEPRECATION")
fun localNets(ctx: Context): List<LocalNet> {
    val out = ArrayList<LocalNet>()
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    for (net in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(net) ?: continue
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
        val lp = cm.getLinkProperties(net) ?: continue
        for (la in lp.linkAddresses) {
            val a = la.address
            if (a is Inet4Address && !a.isLoopbackAddress) out.add(LocalNet(net, a, la.prefixLength))
        }
    }
    try {
        for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!ni.isUp || ni.isLoopback) continue
            val name = ni.name.lowercase()
            // Skip mobile data and VPN interfaces: the robot is never there.
            if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") ||
                name.startsWith("dummy") || name.startsWith("v4-")) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address
                if (a is Inet4Address && out.none { it.addr == a }) out.add(LocalNet(null, a, ia.networkPrefixLength.toInt()))
            }
        }
    } catch (_: Exception) {}
    return out
}

private fun LocalNet.contains(ip: Int): Boolean {
    val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
    return (ipToInt(addr) and mask) == (ip and mask)
}

/** Network to use for [ip]: the one whose subnet contains it, else the first Wi-Fi/Ethernet one. */
fun networkFor(ctx: Context, ip: String, nets: List<LocalNet> = localNets(ctx)): Network? {
    val target = try { ipToInt(InetAddress.getByName(ip)) } catch (_: Exception) { return null }
    nets.firstOrNull { it.contains(target) }?.let { return it.network }
    return nets.firstOrNull { it.network != null }?.network
}

/** Host addresses to scan for one subnet (at most a /22; larger subnets scan the phone's own /24). */
fun hostsOf(n: LocalNet): List<String> {
    val p = if (n.prefix in 22..30) n.prefix else 24
    val mask = -1 shl (32 - p)
    val self = ipToInt(n.addr)
    val base = self and mask
    val bcast = base or mask.inv()
    return ((base + 1) until bcast).filter { it != self }.map(::intToIp)
}

fun openSocket(ctx: Context, ip: String, port: Int, connectTimeoutMs: Int = 3000, readTimeoutMs: Int = 400): Socket {
    val s = networkFor(ctx, ip)?.socketFactory?.createSocket() ?: Socket()
    try {
        s.tcpNoDelay = true
        s.soTimeout = readTimeoutMs
        s.connect(InetSocketAddress(ip, port), connectTimeoutMs)
    } catch (e: Exception) {
        try { s.close() } catch (_: Exception) {}
        throw e
    }
    return s
}
