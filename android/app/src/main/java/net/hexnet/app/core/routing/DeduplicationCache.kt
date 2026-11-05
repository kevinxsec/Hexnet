package net.hexnet.app.core.routing

/**
 * Thread-safe bounded LRU deduplication cache for mesh packets.
 * Suppresses rebroadcasts, loops, and duplicate packet processing.
 */
class DeduplicationCache(
    private val maxCapacity: Int = 2048,
    private val retentionMs: Long = 600_000 // 10 minutes
) {
    private val cache = object : java.util.LinkedHashMap<Long, Long>(maxCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean {
            return size > maxCapacity
        }
    }

    /**
     * Checks if a packet has already been seen.
     * If not seen, records it with the current timestamp and returns true.
     * If already seen, returns false (duplicate).
     */
    @Synchronized
    fun recordPacket(packetId: Long): Boolean {
        val now = System.currentTimeMillis()
        val existing = cache[packetId]
        if (existing != null && (now - existing) < retentionMs) {
            return false // Duplicate!
        }
        cache[packetId] = now
        return true
    }

    /**
     * Checks if a packetId is present without recording it.
     */
    @Synchronized
    fun isDuplicate(packetId: Long): Boolean {
        val now = System.currentTimeMillis()
        val existing = cache[packetId] ?: return false
        return (now - existing) < retentionMs
    }

    /**
     * Prunes expired entries based on retention window.
     */
    @Synchronized
    fun pruneExpired(): Int {
        val now = System.currentTimeMillis()
        var pruned = 0
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if ((now - entry.value) >= retentionMs) {
                iterator.remove()
                pruned++
            }
        }
        return pruned
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    fun size(): Int = cache.size
}
