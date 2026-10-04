package com.homecam.core.diagnostics

/**
 * Where a peer address sits, for a diagnostics line. Tailnet is 100.64.0.0/10, the range
 * Tailscale uses; lan is RFC 1918. Anything else, including an address that does not parse,
 * is other. The address itself is never written to the log.
 *
 * An IPv4 peer accepted on a dual-stack socket is reported as an IPv4-mapped IPv6 address.
 * That form is unwrapped, or a tailnet viewer would be logged as other.
 */
enum class PeerPath(val wire: String) {
    LAN("lan"),
    TAILNET("tailnet"),
    OTHER("other");

    companion object {
        fun classify(address: String?): PeerPath {
            val octets = ipv4Octets(address) ?: return OTHER
            return when {
                octets[0] == 100 && octets[1] in 64..127 -> TAILNET
                octets[0] == 10 -> LAN
                octets[0] == 172 && octets[1] in 16..31 -> LAN
                octets[0] == 192 && octets[1] == 168 -> LAN
                else -> OTHER
            }
        }

        private fun ipv4Octets(raw: String?): IntArray? {
            if (raw.isNullOrBlank()) return null
            var host = raw.trim().lowercase()
            if (host.startsWith("[")) {
                val end = host.indexOf(']')
                if (end <= 1) return null
                host = host.substring(1, end)
            }
            val zone = host.indexOf('%')
            if (zone >= 0) host = host.substring(0, zone)
            mappedDottedV4(host)?.let { return it }
            if (!host.contains(':')) return dottedV4(host)
            return mappedHexV4(host)
        }

        private fun mappedDottedV4(host: String): IntArray? {
            val prefix = "::ffff:"
            if (!host.startsWith(prefix)) return null
            val rest = host.removePrefix(prefix)
            if (rest.contains(':')) return null
            return dottedV4(rest)
        }

        private fun dottedV4(host: String): IntArray? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val octets = IntArray(4)
            for (i in 0..3) {
                val part = parts[i]
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
                val n = part.toInt()
                if (n !in 0..255) return null
                octets[i] = n
            }
            return octets
        }

        /** `::ffff:6440:1` and the uncompressed eight-group form of the same address. */
        private fun mappedHexV4(host: String): IntArray? {
            val groups = expandV6(host) ?: return null
            val mapped = groups[0] == 0 && groups[1] == 0 && groups[2] == 0 &&
                groups[3] == 0 && groups[4] == 0 && groups[5] == 0xffff
            if (!mapped) return null
            val hi = groups[6]
            val lo = groups[7]
            return intArrayOf(hi shr 8, hi and 0xff, lo shr 8, lo and 0xff)
        }

        private fun expandV6(host: String): IntArray? {
            if (host.isEmpty() || host.contains(":::")) return null
            val pieces = host.split("::")
            if (pieces.size > 2) return null
            val head = v6Groups(pieces[0]) ?: return null
            if (pieces.size == 1) return if (head.size == 8) head.toIntArray() else null
            val tail = v6Groups(pieces[1]) ?: return null
            val fill = 8 - head.size - tail.size
            if (fill < 1) return null
            return (head + List(fill) { 0 } + tail).toIntArray()
        }

        private fun v6Groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val groups = part.split(':')
            if (groups.any { it.isEmpty() || it.length > 4 }) return null
            return groups.map { group ->
                if (group.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
                group.toInt(16)
            }
        }
    }
}
