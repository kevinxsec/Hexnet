package net.hexnet.app.core.routing

import net.hexnet.app.core.protocol.BinaryPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents a direct BLE neighbor within one-hop radio range.
 */
data class NeighborNode(
    val deviceAddress: String,
    val hexAddress: Long,
    var rssiDbm: Int,
    var isDirect: Boolean = true,
    var lastSeenEpochMs: Long = System.currentTimeMillis(),
    var mtu: Int = 517
)

/**
 * Route table entry learned via reverse-path forwarding or route discovery.
 */
data class RouteEntry(
    val destinationHex: Long,
    val nextHopAddress: String,
    val hopDistance: Int,
    var expiresEpochMs: Long,
    var qualityMetric: Int = 100 // Higher is better (e.g. based on RSSI and hop count)
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs > expiresEpochMs
}

/**
 * Routing decision produced by the MeshRoutingEngine for an ingested packet.
 */
sealed class RoutingDecision {
    /**
     * Packet reached its destination (or broadcast) and is accepted by the local node.
     */
    data class AcceptLocally(
        val packet: BinaryPacket,
        val isDirectDestination: Boolean,
        val decryptedPayload: ByteArray? = null
    ) : RoutingDecision()

    /**
     * Unicast forward to a known next-hop neighbor device address.
     */
    data class ForwardUnicast(
        val packet: BinaryPacket,
        val nextHopAddress: String
    ) : RoutingDecision()

    /**
     * Controlled flood broadcast to all connected neighbors except the arrival port.
     */
    data class ForwardBroadcast(
        val packet: BinaryPacket,
        val exceptDeviceAddress: String?
    ) : RoutingDecision()

    /**
     * Packet dropped because it has already been processed (deduplication).
     */
    data class DropDuplicate(val packetId: Long) : RoutingDecision()

    /**
     * Packet dropped because TTL has reached zero or 1 (cannot be forwarded further).
     */
    data class DropExpiredTtl(val packetId: Long, val currentTtl: Byte) : RoutingDecision()

    /**
     * Packet dropped because it originated from this node (loop reflection).
     */
    data class DropSelfOrigin(val packetId: Long) : RoutingDecision()

    /**
     * Packet dropped due to corrupt framing, invalid checksum, or parsing failure.
     */
    data class DropInvalidChecksum(val reason: String) : RoutingDecision()
}

/**
 * Delivery Acknowledgement payload structure (for both Link-level hop ACKs and End-to-End receipts).
 */
data class DeliveryAck(
    val originalPacketId: Long,
    val isEndToEnd: Boolean,
    val ackSenderHex: Long,
    val originalSenderHex: Long,
    val timestampEpochMs: Long = System.currentTimeMillis()
) {
    companion object {
        const val ACK_PAYLOAD_SIZE = 34 // Magic(1) + PacketId(8) + Type(1) + Sender(8) + Dest(8) + Timestamp(8) = 34 bytes

        fun serialize(ack: DeliveryAck): ByteArray {
            val buffer = ByteBuffer.allocate(ACK_PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN)
            buffer.put(0x41.toByte()) // 'A'
            buffer.putLong(ack.originalPacketId)
            buffer.put(if (ack.isEndToEnd) 0x01.toByte() else 0x00.toByte())
            buffer.putLong(ack.ackSenderHex)
            buffer.putLong(ack.originalSenderHex)
            buffer.putLong(ack.timestampEpochMs)
            return buffer.array()
        }

        fun deserialize(bytes: ByteArray): Result<DeliveryAck> {
            if (bytes.size < ACK_PAYLOAD_SIZE) {
                return Result.failure(IllegalArgumentException("ACK payload too small: ${bytes.size} bytes (expected $ACK_PAYLOAD_SIZE)"))
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = buffer.get()
            if (magic != 0x41.toByte()) {
                return Result.failure(IllegalArgumentException("Invalid ACK magic: 0x%02X".format(magic)))
            }
            val packetId = buffer.getLong()
            val isE2e = buffer.get() == 0x01.toByte()
            val ackSender = buffer.getLong()
            val origSender = buffer.getLong()
            val timestamp = buffer.getLong()

            return Result.success(
                DeliveryAck(
                    originalPacketId = packetId,
                    isEndToEnd = isE2e,
                    ackSenderHex = ackSender,
                    originalSenderHex = origSender,
                    timestampEpochMs = timestamp
                )
            )
        }
    }
}
