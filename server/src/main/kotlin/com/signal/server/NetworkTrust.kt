package com.signal.server

import java.net.InetAddress

/**
 * Network-trust login for the web admin (PLAN §5.7), re-implemented from Pulse, Quest and Trakt:
 * only LAN and Tailscale addresses are trusted, and a request that came through Cloudflare (it
 * carries Cloudflare's headers) never is, whatever address it appears to come from.
 */
object NetworkTrust {
    private val cloudflareHeaders = listOf("cf-connecting-ip", "cf-ray", "cf-ipcountry", "cdn-loop")

    fun isTrusted(remoteAddress: String, headerNames: Collection<String>): Boolean {
        if (headerNames.any { it.lowercase() in cloudflareHeaders }) return false
        return isTrustedAddress(remoteAddress)
    }

    fun isTrustedAddress(address: String): Boolean {
        // Only literal addresses: never resolve a name here.
        val literal = address.substringBefore('%').trim('[', ']')
        // The one name allowed: some engines (Ktor's test host) report loopback by name.
        if (literal == "localhost") return true
        if (literal.isEmpty() || !(literal.all { it.isDigit() || it == '.' } || ':' in literal)) return false
        val ip = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
        val b = ip.address.map { it.toInt() and 0xff }
        return when (b.size) {
            4 -> b[0] == 127 || b[0] == 10 ||
                (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) ||
                // Tailscale's CGNAT range, 100.64.0.0/10.
                (b[0] == 100 && b[1] in 64..127)
            16 -> ip.isLoopbackAddress ||
                // Unique-local addresses, which include Tailscale's fd7a:115c:a1e0::/48.
                (b[0] and 0xfe) == 0xfc ||
                ip.isLinkLocalAddress ||
                // An IPv4 address mapped into IPv6.
                (b.take(10).all { it == 0 } && b[10] == 0xff && b[11] == 0xff && isTrustedAddress(b.drop(12).joinToString(".")))
            else -> false
        }
    }
}
