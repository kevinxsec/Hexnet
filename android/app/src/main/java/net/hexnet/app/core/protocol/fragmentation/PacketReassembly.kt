package net.hexnet.app.core.protocol.fragmentation

import net.hexnet.app.core.protocol.BinaryPacket
import net.hexnet.app.core.protocol.PacketSerializer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Splits serialized BinaryPackets into transmission fragments sized to fit within negotiated BLE ATT MTU.
 */
object PacketFragmenter {

    /**
     * Splits a serialized packet into fragment byte arrays ready for over-the-air transmission.
     * @param serializedPacket Full serialized BinaryPacket with framing and checksum.
     * @param packetId The 64-bit identifier of the packet.
     * @param maxFragmentPayload Usable MTU payload size (negotiated MTU - ATT overhead - Fragment header).
     */
    fun fragment(
        serializedPacket: ByteArray,
        packetId: Long,
        maxFragmentPayload: Int
    ): List<ByteArray> {
        require(maxFragmentPayload > 0) { "maxFragmentPayload must be > 0" }

        val totalSize = serializedPacket.size
        val totalFragments = ceil(totalSize.toDouble() / maxFragmentPayload).toInt().toShort()

        val fragments = ArrayList<ByteArray>(totalFragments.toInt())

        for (i in 0 until totalFragments) {
            val offset = i * maxFragmentPayload
            val length = kotlin.math.min(maxFragmentPayload, totalSize - offset)
            val chunk = serializedPacket.copyOfRange(offset, offset + length)

            val fragment = PacketFragment(
                packetId = packetId,
                totalFragments = totalFragments,
                fragmentIndex = i.toShort(),
                data = chunk
            )
            fragments.add(FragmentSerializer.serialize(fragment))
        }

        return fragments
    }
}

/**
 * Reassembles incoming fragments into full packets.
 * Thread-safe with automatic buffer eviction on timeout to prevent memory exhaustion.
 */
class PacketReassembler(
    private val timeoutMs: Long = 10000L
) {
    private class InFlightBuffer(
        val packetId: Long,
        val totalFragments: Short,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val fragments = Array<ByteArray?>(totalFragments.toInt()) { null }
        var receivedCount = 0

        fun add(index: Short, data: ByteArray): Boolean {
            val idx = index.toInt()
            if (idx in fragments.indices && fragments[idx] == null) {
                fragments[idx] = data
                receivedCount++
            }
            return receivedCount == totalFragments.toInt()
        }

        fun reassemble(): ByteArray {
            val stream = ByteArrayOutputStream()
            for (chunk in fragments) {
                if (chunk != null) {
                    stream.write(chunk)
                }
            }
            return stream.toByteArray()
        }
    }

    companion object {
        const val MAX_IN_FLIGHT_BUFFERS = 128
        const val MAX_ALLOWED_FRAGMENTS = 64
        const val MAX_FRAGMENT_PAYLOAD_SIZE = 1024
    }

    private val buffers = ConcurrentHashMap<Long, InFlightBuffer>()

    /**
     * Processes an incoming raw fragment byte array.
     * Returns the fully reconstructed packet byte array if this fragment completes the packet, or null if more fragments are pending.
     */
    fun processFragmentBytes(fragmentBytes: ByteArray): ByteArray? {
        val fragmentResult = FragmentSerializer.deserialize(fragmentBytes)
        if (fragmentResult.isFailure) {
            return null
        }
        val fragment = fragmentResult.getOrThrow()
        return processFragment(fragment)
    }

    /**
     * Ingests a deserialized PacketFragment.
     */
    @Synchronized
    fun processFragment(fragment: PacketFragment): ByteArray? {
        // Enforce maximum fragment limit and valid index bounds
        if (fragment.totalFragments <= 0 || fragment.totalFragments > MAX_ALLOWED_FRAGMENTS) {
            return null
        }
        if (fragment.fragmentIndex < 0 || fragment.fragmentIndex >= fragment.totalFragments) {
            return null
        }
        if (fragment.data.size > MAX_FRAGMENT_PAYLOAD_SIZE) {
            return null
        }

        pruneExpiredBuffers()

        // Enforce maximum buffer capacity to prevent memory exhaustion
        if (buffers.size >= MAX_IN_FLIGHT_BUFFERS && !buffers.containsKey(fragment.packetId)) {
            // Evict oldest buffer
            val oldestKey = buffers.minByOrNull { it.value.createdAt }?.key
            if (oldestKey != null) {
                buffers.remove(oldestKey)
            }
        }

        val buffer = buffers.computeIfAbsent(fragment.packetId) {
            InFlightBuffer(fragment.packetId, fragment.totalFragments)
        }

        // Integrity check: fragment total must match buffer expectation
        if (buffer.totalFragments != fragment.totalFragments) {
            buffers.remove(fragment.packetId)
            return null
        }

        val isComplete = buffer.add(fragment.fragmentIndex, fragment.data)
        if (isComplete) {
            buffers.remove(fragment.packetId)
            return buffer.reassemble()
        }

        return null
    }

    /**
     * Prunes buffers that have timed out without receiving all fragments.
     */
    @Synchronized
    fun pruneExpiredBuffers() {
        val now = System.currentTimeMillis()
        val iterator = buffers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.createdAt > timeoutMs) {
                iterator.remove()
            }
        }
    }

    fun activeBufferCount(): Int = buffers.size

    fun clear() {
        buffers.clear()
    }
}
