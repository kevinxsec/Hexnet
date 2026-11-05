package net.hexnet.app.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

data class BinaryPacket(
    val version: Byte = PROTOCOL_VERSION,
    val type: PacketType,
    val flags: Byte = PacketFlags.NONE,
    val ttl: Byte = DEFAULT_TTL,
    val hopCount: Byte = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val packetId: Long = kotlin.random.Random.nextLong(),
    val sourceHex: Long,
    val destinationHex: Long = BROADCAST_DESTINATION,
    val payload: ByteArray
) {
    companion object {
        const val MAGIC_BYTE: Byte = 0x48.toByte() // ASCII 'H'
        const val PROTOCOL_VERSION: Byte = 0x01
        const val DEFAULT_TTL: Byte = 5
        const val MAX_TTL: Byte = 7
        const val BROADCAST_DESTINATION: Long = -1L // 0xFFFFFFFFFFFFFFFFL

        const val HEADER_SIZE_BYTES = 40
        const val CHECKSUM_SIZE_BYTES = 4
        const val TOTAL_FRAMING_OVERHEAD = HEADER_SIZE_BYTES + CHECKSUM_SIZE_BYTES // 44 bytes
        const val MAX_PAYLOAD_SIZE = 65535
        const val MAX_SAFE_PACKET_PAYLOAD = 8192 // 8 KB safety limit to prevent radio and buffer exhaustion
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BinaryPacket
        if (packetId != other.packetId) return false
        if (version != other.version) return false
        if (type != other.type) return false
        if (flags != other.flags) return false
        if (ttl != other.ttl) return false
        if (hopCount != other.hopCount) return false
        if (timestamp != other.timestamp) return false
        if (sourceHex != other.sourceHex) return false
        if (destinationHex != other.destinationHex) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packetId.hashCode()
        result = 31 * result + version
        result = 31 * result + type.hashCode()
        result = 31 * result + flags
        result = 31 * result + ttl
        result = 31 * result + hopCount
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sourceHex.hashCode()
        result = 31 * result + destinationHex.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * High-performance, zero-copy binary serializer and deserializer for Hexnet wire packets.
 */
object PacketSerializer {

    /**
     * Serializes a BinaryPacket into a raw byte array with big-endian framing and CRC32 checksum trailer.
     */
    fun serialize(packet: BinaryPacket): ByteArray {
        val payloadLen = packet.payload.size
        if (payloadLen > BinaryPacket.MAX_SAFE_PACKET_PAYLOAD) {
            throw IllegalArgumentException("Payload size $payloadLen exceeds maximum safe limit ${BinaryPacket.MAX_SAFE_PACKET_PAYLOAD}")
        }

        val totalSize = BinaryPacket.TOTAL_FRAMING_OVERHEAD + payloadLen
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // Write 40-byte Header
        buffer.put(BinaryPacket.MAGIC_BYTE)
        buffer.put(packet.version)
        buffer.put(packet.type.code)
        buffer.put(packet.flags)
        buffer.put(packet.ttl.coerceIn(0, BinaryPacket.MAX_TTL))
        buffer.put(packet.hopCount.coerceAtLeast(0))
        buffer.putLong(packet.timestamp)
        buffer.putLong(packet.packetId)
        buffer.putLong(packet.sourceHex)
        buffer.putLong(packet.destinationHex)
        buffer.putShort(payloadLen.toShort())

        // Write Variable Length Payload
        buffer.put(packet.payload)

        // Calculate and Append CRC32 over Header + Payload
        val bytesBeforeCrc = buffer.array()
        val crc = CRC32()
        crc.update(bytesBeforeCrc, 0, BinaryPacket.HEADER_SIZE_BYTES + payloadLen)
        val checksumValue = crc.value

        buffer.putInt(checksumValue.toInt())

        return buffer.array()
    }

    /**
     * Deserializes a raw byte array into a verified BinaryPacket.
     * Validates magic byte, protocol version, payload length, and CRC32 checksum.
     */
    fun deserialize(bytes: ByteArray): Result<BinaryPacket> {
        if (bytes.size < BinaryPacket.TOTAL_FRAMING_OVERHEAD) {
            return Result.failure(IllegalArgumentException("Byte array too small: ${bytes.size} bytes (minimum is ${BinaryPacket.TOTAL_FRAMING_OVERHEAD})"))
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // 1. Verify Magic Byte
        val magic = buffer.get()
        if (magic != BinaryPacket.MAGIC_BYTE) {
            return Result.failure(IllegalArgumentException("Invalid magic byte: 0x%02X (expected 0x%02X)".format(magic, BinaryPacket.MAGIC_BYTE)))
        }

        // 2. Verify Protocol Version
        val version = buffer.get()
        if (version != BinaryPacket.PROTOCOL_VERSION) {
            return Result.failure(IllegalArgumentException("Unsupported protocol version: $version"))
        }

        // 3. Parse Header Fields
        val typeCode = buffer.get()
        val type = PacketType.fromCode(typeCode)
            ?: return Result.failure(IllegalArgumentException("Unknown packet type code: 0x%02X".format(typeCode)))

        val flags = buffer.get()
        val ttl = buffer.get()
        val hopCount = buffer.get()

        // Defensive validation against TTL/hopCount manipulation
        if (ttl < 0) {
            return Result.failure(IllegalArgumentException("Negative TTL rejected: $ttl"))
        }
        if (hopCount < 0) {
            return Result.failure(IllegalArgumentException("Negative hop count rejected: $hopCount"))
        }
        val clampedTtl = ttl.coerceAtMost(BinaryPacket.MAX_TTL)

        val timestamp = buffer.getLong()
        val packetId = buffer.getLong()
        val sourceHex = buffer.getLong()
        val destinationHex = buffer.getLong()

        // 4. Parse Payload Length
        val payloadLen = buffer.short.toInt() and 0xFFFF
        if (payloadLen > BinaryPacket.MAX_SAFE_PACKET_PAYLOAD) {
            return Result.failure(IllegalArgumentException("Payload length $payloadLen exceeds safe limit ${BinaryPacket.MAX_SAFE_PACKET_PAYLOAD}"))
        }

        val expectedTotalSize = BinaryPacket.TOTAL_FRAMING_OVERHEAD + payloadLen
        if (bytes.size != expectedTotalSize) {
            return Result.failure(IllegalArgumentException("Packet size mismatch: actual ${bytes.size} bytes vs expected $expectedTotalSize bytes"))
        }

        // 5. Read Payload
        val payload = ByteArray(payloadLen)
        buffer.get(payload)

        // 6. Verify CRC32 Checksum Trailer
        val expectedCrcInt = buffer.getInt()
        val expectedCrc = expectedCrcInt.toLong() and 0xFFFFFFFFL

        val crc = CRC32()
        crc.update(bytes, 0, BinaryPacket.HEADER_SIZE_BYTES + payloadLen)
        val calculatedCrc = crc.value

        if (calculatedCrc != expectedCrc) {
            return Result.failure(SecurityException("CRC32 checksum mismatch: calculated 0x%08X vs expected 0x%08X (data corrupted in transit)".format(calculatedCrc, expectedCrc)))
        }

        return Result.success(
            BinaryPacket(
                version = version,
                type = type,
                flags = flags,
                ttl = clampedTtl,
                hopCount = hopCount,
                timestamp = timestamp,
                packetId = packetId,
                sourceHex = sourceHex,
                destinationHex = destinationHex,
                payload = payload
            )
        )
    }
}
