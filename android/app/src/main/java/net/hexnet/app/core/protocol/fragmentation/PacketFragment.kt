package net.hexnet.app.core.protocol.fragmentation

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PacketFragment(
    val packetId: Long,
    val totalFragments: Short,
    val fragmentIndex: Short,
    val data: ByteArray
) {
    companion object {
        const val FRAGMENT_MAGIC: Byte = 0x46.toByte() // ASCII 'F'
        const val HEADER_SIZE = 13 // Magic(1) + PacketId(8) + Total(2) + Index(2)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PacketFragment
        if (packetId != other.packetId) return false
        if (totalFragments != other.totalFragments) return false
        if (fragmentIndex != other.fragmentIndex) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packetId.hashCode()
        result = 31 * result + totalFragments
        result = 31 * result + fragmentIndex
        result = 31 * result + data.contentHashCode()
        return result
    }
}

object FragmentSerializer {

    fun serialize(fragment: PacketFragment): ByteArray {
        val totalSize = PacketFragment.HEADER_SIZE + fragment.data.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(PacketFragment.FRAGMENT_MAGIC)
        buffer.putLong(fragment.packetId)
        buffer.putShort(fragment.totalFragments)
        buffer.putShort(fragment.fragmentIndex)
        buffer.put(fragment.data)

        return buffer.array()
    }

    fun deserialize(bytes: ByteArray): Result<PacketFragment> {
        if (bytes.size < PacketFragment.HEADER_SIZE) {
            return Result.failure(IllegalArgumentException("Fragment too small: ${bytes.size} bytes"))
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.get()
        if (magic != PacketFragment.FRAGMENT_MAGIC) {
            return Result.failure(IllegalArgumentException("Invalid fragment magic: 0x%02X".format(magic)))
        }

        val packetId = buffer.getLong()
        val total = buffer.short
        val index = buffer.short

        if (total <= 0 || index < 0 || index >= total) {
            return Result.failure(IllegalArgumentException("Invalid fragment range: index $index of $total"))
        }

        val dataSize = bytes.size - PacketFragment.HEADER_SIZE
        if (dataSize > 1024) {
            return Result.failure(IllegalArgumentException("Fragment data size $dataSize exceeds maximum 1024 bytes"))
        }
        val data = ByteArray(dataSize)
        buffer.get(data)

        return Result.success(PacketFragment(packetId, total, index, data))
    }
}
