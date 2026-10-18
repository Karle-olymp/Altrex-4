package com.google.ai.edge.gallery.security

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * [ALTREX] Protection against SSRF: an address chosen by the model (web_fetch, future connectors)
 * must be a public web site, never the phone itself, the local network, or a special address such
 * as the cloud metadata endpoint 169.254.169.254.
 */
object UrlGuard {
  class Refused(message: String) : IllegalArgumentException(message)

  /**
   * Checks [url] and returns its host in ASCII form. Throws [Refused] (message in French, shown to
   * the user and the model) when the address is not allowed.
   */
  fun checkUrl(url: String, resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName): String {
    val uri =
      try {
        URI(url.trim())
      } catch (e: Exception) {
        throw Refused("adresse invalide.")
      }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") {
      throw Refused("seules les adresses http et https sont acceptées.")
    }
    // "https://site.com@127.0.0.1/" reads like site.com but goes to 127.0.0.1.
    if (uri.rawUserInfo != null) throw Refused("adresse avec identifiants refusée.")
    val rawHost = uri.host ?: throw Refused("adresse invalide.")
    if (rawHost.contains('%') || rawHost.contains('\\')) throw Refused("adresse encodée refusée.")
    val host =
      try {
        IDN.toASCII(rawHost.trim('[', ']').trimEnd('.'), IDN.ALLOW_UNASSIGNED).lowercase()
      } catch (e: Exception) {
        throw Refused("nom de site invalide.")
      }
    if (host.isEmpty()) throw Refused("adresse invalide.")
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
      host.endsWith(".internal") || host.endsWith(".home.arpa")
    ) {
      throw Refused("adresse locale refusée par sécurité.")
    }
    // A numeric host in any form the system resolver accepts (decimal, hex, octal, shortened).
    literalIpv4(host)?.let { if (isNonPublic(it)) throw Refused("adresse locale ou privée refusée par sécurité.") }

    val addresses =
      try {
        resolve(host)
      } catch (e: UnknownHostException) {
        throw Refused("site introuvable.")
      }
    if (addresses.isEmpty()) throw Refused("site introuvable.")
    // Every address the name points to must be public (a name can point to several).
    for (a in addresses) {
      if (isNonPublic(a)) throw Refused("adresse locale ou privée refusée par sécurité.")
    }
    return host
  }

  /** Loopback, private, link-local (169.254.x, cloud metadata), CGNAT, multicast, reserved… */
  fun isNonPublic(a: InetAddress): Boolean {
    if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress ||
      a.isMulticastAddress
    ) {
      return true
    }
    val b = a.address.map { it.toInt() and 0xFF }
    if (a is Inet4Address) {
      val (x, y, z) = Triple(b[0], b[1], b[2])
      return x == 0 || // 0.0.0.0/8
        x >= 240 || // 240.0.0.0/4 reserved, 255.255.255.255
        (x == 100 && y in 64..127) || // 100.64.0.0/10 CGNAT
        (x == 192 && y == 0 && z == 0) || // 192.0.0.0/24 protocol assignments
        (x == 198 && (y == 18 || y == 19)) // 198.18.0.0/15 benchmarking
    }
    if (a is Inet6Address) {
      if ((b[0] and 0xFE) == 0xFC) return true // fc00::/7 unique local
      // IPv4 embedded in IPv6 (::ffff:a.b.c.d, 64:ff9b::a.b.c.d): check the IPv4 part.
      val mapped = b.subList(0, 10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF
      val nat64 = b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B && b.subList(4, 12).all { it == 0 }
      if (mapped || nat64) {
        return isNonPublic(InetAddress.getByAddress(a.address.copyOfRange(12, 16)))
      }
    }
    return false
  }

  /**
   * Parses [host] as an IPv4 address the way inet_aton does: 1 to 4 parts, each in decimal, hex
   * (0x…) or octal (leading 0), the last part filling the remaining bytes ("127.1", "2130706433").
   * Returns null when it is not a numeric address.
   */
  fun literalIpv4(host: String): InetAddress? {
    val parts = host.split('.')
    if (parts.isEmpty() || parts.size > 4 || parts.any { it.isEmpty() }) return null
    val values =
      parts.map { p ->
        val v =
          when {
            p.startsWith("0x", ignoreCase = true) -> p.substring(2).toLongOrNull(16)
            p.length > 1 && p.startsWith("0") -> p.substring(1).toLongOrNull(8)
            else -> p.toLongOrNull(10)
          }
        v ?: return null
      }
    var value = 0L
    for (i in 0 until values.size - 1) {
      if (values[i] > 255) return null
      value = value or (values[i] shl (8 * (3 - i)))
    }
    val lastBytes = 4 - (values.size - 1)
    val last = values.last()
    if (last >= (1L shl (8 * lastBytes))) return null
    value = value or last
    val bytes = ByteArray(4) { i -> ((value shr (8 * (3 - i))) and 0xFF).toByte() }
    return InetAddress.getByAddress(bytes)
  }
}
