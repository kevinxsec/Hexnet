package net.hexnet.app.core.routing

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe neighbor registry maintaining active 1-hop BLE connections.
 */
class NeighborTable {
    private val neighborsByAddress = ConcurrentHashMap<String, NeighborNode>()
    private val hexToAddressMap = ConcurrentHashMap<Long, String>()

    companion object {
        const val MAX_NEIGHBORS = 64
    }

    /**
     * Adds or refreshes an active neighbor in radio range.
     * Enforces MAX_NEIGHBORS capacity limit to prevent memory exhaustion.
     */
    fun addOrUpdateNeighbor(
        deviceAddress: String,
        hexAddress: Long,
        rssiDbm: Int,
        isDirect: Boolean = true,
        mtu: Int = 517
    ): NeighborNode {
        val existing = neighborsByAddress[deviceAddress]
        if (existing != null) {
            existing.rssiDbm = rssiDbm
            existing.isDirect = isDirect
            existing.lastSeenEpochMs = System.currentTimeMillis()
            existing.mtu = mtu
            hexToAddressMap[hexAddress] = deviceAddress
            return existing
        } else {
            // Evict oldest if capacity is reached
            if (neighborsByAddress.size >= MAX_NEIGHBORS) {
                val oldestAddr = neighborsByAddress.minByOrNull { it.value.lastSeenEpochMs }?.key
                if (oldestAddr != null) removeNeighbor(oldestAddr)
            }

            val node = NeighborNode(
                deviceAddress = deviceAddress,
                hexAddress = hexAddress,
                rssiDbm = rssiDbm,
                isDirect = isDirect,
                lastSeenEpochMs = System.currentTimeMillis(),
                mtu = mtu
            )
            neighborsByAddress[deviceAddress] = node
            hexToAddressMap[hexAddress] = deviceAddress
            return node
        }
    }

    fun getNeighbor(deviceAddress: String): NeighborNode? = neighborsByAddress[deviceAddress]

    fun getNeighborByHex(hexAddress: Long): NeighborNode? {
        val addr = hexToAddressMap[hexAddress] ?: return null
        return neighborsByAddress[addr]
    }

    fun removeNeighbor(deviceAddress: String): NeighborNode? {
        val removed = neighborsByAddress.remove(deviceAddress)
        if (removed != null) {
            hexToAddressMap.remove(removed.hexAddress)
        }
        return removed
    }

    fun getActiveNeighbors(maxAgeMs: Long = 120_000): List<NeighborNode> {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        return neighborsByAddress.values.filter { it.lastSeenEpochMs >= cutoff }
    }

    fun getAllConnectedAddresses(): List<String> = neighborsByAddress.keys().toList()

    fun isConnected(deviceAddress: String): Boolean = neighborsByAddress.containsKey(deviceAddress)

    fun isHexConnected(hexAddress: Long): Boolean = hexToAddressMap.containsKey(hexAddress)

    fun clear() {
        neighborsByAddress.clear()
        hexToAddressMap.clear()
    }

    fun size(): Int = neighborsByAddress.size
}
