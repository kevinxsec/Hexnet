package net.hexnet.app.core.routing

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe directed routing table for multi-hop mesh packet dispatching.
 * Implements reverse-path routing table with expiration and link failure invalidation.
 */
class RouteTable(
    private val defaultRouteTtlMs: Long = 300_000 // 5 minutes
) {
    companion object {
        const val MAX_ROUTES = 256
        const val MAX_VALID_HOP_DISTANCE = 7
    }

    private val routes = ConcurrentHashMap<Long, RouteEntry>()

    /**
     * Learns or updates a route to destinationHex via nextHopAddress with hopDistance.
     * Enforces route poisoning defenses (validates 1 <= hopDistance <= 7) and table capacity limits.
     */
    @Synchronized
    fun updateRoute(
        destinationHex: Long,
        nextHopAddress: String,
        hopDistance: Int,
        qualityMetric: Int = 100,
        customTtlMs: Long = defaultRouteTtlMs
    ): Boolean {
        // Defensive check: Reject route poisoning with 0, negative, or absurd hop counts
        if (hopDistance < 1 || hopDistance > MAX_VALID_HOP_DISTANCE) {
            return false
        }

        val now = System.currentTimeMillis()
        val expiresEpoch = now + customTtlMs

        // Enforce capacity bounds
        if (routes.size >= MAX_ROUTES && !routes.containsKey(destinationHex)) {
            cleanExpiredRoutes()
            if (routes.size >= MAX_ROUTES) {
                val oldestKey = routes.minByOrNull { it.value.expiresEpochMs }?.key
                if (oldestKey != null) routes.remove(oldestKey)
            }
        }

        val existing = routes[destinationHex]

        if (existing == null || existing.isExpired(now)) {
            routes[destinationHex] = RouteEntry(
                destinationHex = destinationHex,
                nextHopAddress = nextHopAddress,
                hopDistance = hopDistance,
                expiresEpochMs = expiresEpoch,
                qualityMetric = qualityMetric
            )
            return true
        }

        // If from the same next hop, refresh expiration and update distance
        if (existing.nextHopAddress == nextHopAddress) {
            routes[destinationHex] = existing.copy(
                hopDistance = hopDistance,
                expiresEpochMs = expiresEpoch,
                qualityMetric = qualityMetric
            )
            return true
        }

        // If via a different next hop, only replace if distance is strictly shorter or significantly better metric
        if (hopDistance < existing.hopDistance) {
            routes[destinationHex] = RouteEntry(
                destinationHex = destinationHex,
                nextHopAddress = nextHopAddress,
                hopDistance = hopDistance,
                expiresEpochMs = expiresEpoch,
                qualityMetric = qualityMetric
            )
            return true
        }

        return false
    }

    /**
     * Retrieves an active route to the given destination hex address.
     * Automatically evicts the route if it has expired.
     */
    fun getRoute(destinationHex: Long): RouteEntry? {
        val entry = routes[destinationHex] ?: return null
        if (entry.isExpired()) {
            routes.remove(destinationHex)
            return null
        }
        return entry
    }

    /**
     * Invalidates all routes that traverse the given next-hop device address.
     * Called when a physical BLE link breaks or disconnects.
     */
    @Synchronized
    fun invalidateRoutesVia(nextHopAddress: String): Int {
        var count = 0
        val iterator = routes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.nextHopAddress == nextHopAddress) {
                iterator.remove()
                count++
            }
        }
        return count
    }

    /**
     * Cleans up expired routes to avoid memory creep.
     */
    @Synchronized
    fun cleanExpiredRoutes(): Int {
        val now = System.currentTimeMillis()
        var count = 0
        val iterator = routes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired(now)) {
                iterator.remove()
                count++
            }
        }
        return count
    }

    fun getAllActiveRoutes(): List<RouteEntry> {
        val now = System.currentTimeMillis()
        return routes.values.filter { !it.isExpired(now) }
    }

    fun clear() {
        routes.clear()
    }

    fun size(): Int = routes.size
}
