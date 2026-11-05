package net.hexnet.app.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.hexnet.app.core.protocol.*
import net.hexnet.app.core.protocol.compression.PacketCompression
import net.hexnet.app.core.protocol.fragmentation.FragmentSerializer
import net.hexnet.app.core.protocol.fragmentation.PacketFragment
import net.hexnet.app.core.protocol.fragmentation.PacketFragmenter
import net.hexnet.app.core.protocol.fragmentation.PacketReassembler
import net.hexnet.app.core.transport.ble.BleConstants
import net.hexnet.app.core.transport.ble.GattOperation
import net.hexnet.app.core.transport.ble.GattOperationQueue
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BleConstantsTest {
    @Test
    fun testUuidValidity() {
        assertNotNull(BleConstants.HEXNET_SERVICE_UUID)
        assertNotNull(BleConstants.HEXNET_WRITE_CHAR_UUID)
        assertNotNull(BleConstants.HEXNET_NOTIFY_CHAR_UUID)
        assertNotNull(BleConstants.CCCD_DESCRIPTOR_UUID)

        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", BleConstants.HEXNET_SERVICE_UUID.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", BleConstants.CCCD_DESCRIPTOR_UUID.toString())
    }

    @Test
    fun testMtuCalculations() {
        assertEquals(20, BleConstants.DEFAULT_PAYLOAD_MTU)
        assertEquals(514, BleConstants.TARGET_PAYLOAD_MTU)
        assertTrue(BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS in 4..10)
    }
}

class PacketSerializationTest {

    @Test
    fun testRoundTripSerializationAllTypes() {
        val payload = "Hello decentralized mesh world!".toByteArray(Charsets.UTF_8)

        for (type in PacketType.values()) {
            val packet = BinaryPacket(
                version = 1,
                type = type,
                flags = PacketFlags.setFlag(PacketFlags.NONE, PacketFlags.ACK_REQUESTED),
                ttl = 5,
                hopCount = 1,
                timestamp = 1710000000000L,
                packetId = 0x1234567890ABCDEFL,
                sourceHex = 0x8F4C21B9A45E7D12uL.toLong(),
                destinationHex = 0x3E11B7D5A89C0042L,
                payload = payload
            )

            val serialized = PacketSerializer.serialize(packet)
            val expectedSize = BinaryPacket.TOTAL_FRAMING_OVERHEAD + payload.size
            assertEquals(expectedSize, serialized.size)

            val result = PacketSerializer.deserialize(serialized)
            assertTrue("Deserialization should succeed for $type", result.isSuccess)

            val deserialized = result.getOrThrow()
            assertEquals(packet, deserialized)
            assertEquals(type, deserialized.type)
            assertTrue(PacketFlags.hasFlag(deserialized.flags, PacketFlags.ACK_REQUESTED))
        }
    }

    @Test
    fun testEmptyPayloadSerialization() {
        val emptyPacket = BinaryPacket(
            type = PacketType.LINK_ACK,
            sourceHex = 12345L,
            payload = ByteArray(0)
        )

        val serialized = PacketSerializer.serialize(emptyPacket)
        assertEquals(BinaryPacket.TOTAL_FRAMING_OVERHEAD, serialized.size)

        val result = PacketSerializer.deserialize(serialized)
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().payload.size)
    }

    @Test
    fun testCorruptPayloadChecksumDetection() {
        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = 12345L,
            payload = "Sensitive message data".toByteArray()
        )

        val serialized = PacketSerializer.serialize(packet)

        // Corrupt single byte in payload
        serialized[BinaryPacket.HEADER_SIZE_BYTES + 3] = (serialized[BinaryPacket.HEADER_SIZE_BYTES + 3] + 1).toByte()

        val result = PacketSerializer.deserialize(serialized)
        assertTrue("Checksum mismatch must produce failure", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun testCorruptMagicByteDetection() {
        val packet = BinaryPacket(
            type = PacketType.PEER_ANNOUNCE,
            sourceHex = 12345L,
            payload = ByteArray(10)
        )

        val serialized = PacketSerializer.serialize(packet)
        serialized[0] = 0x00 // Destroy magic 'H' (0x48)

        val result = PacketSerializer.deserialize(serialized)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Invalid magic byte") == true)
    }
}

class PacketFragmentationTest {

    @Test
    fun testFragmentSerializationRoundtrip() {
        val data = ByteArray(40) { it.toByte() }
        val fragment = PacketFragment(
            packetId = 987654321L,
            totalFragments = 5,
            fragmentIndex = 2,
            data = data
        )

        val serialized = FragmentSerializer.serialize(fragment)
        assertEquals(PacketFragment.HEADER_SIZE + 40, serialized.size)

        val deserializedResult = FragmentSerializer.deserialize(serialized)
        assertTrue(deserializedResult.isSuccess)

        val deserialized = deserializedResult.getOrThrow()
        assertEquals(fragment, deserialized)
    }

    @Test
    fun testSplitAndInOrderReassembly() {
        val originalMessage = ("Hexnet Mesh Packet Layer: " + "A".repeat(1200)).toByteArray()
        val packetId = 4455667788L

        val fragments = PacketFragmenter.fragment(
            serializedPacket = originalMessage,
            packetId = packetId,
            maxFragmentPayload = 250 // split into ~5 fragments
        )

        assertTrue(fragments.size >= 5)

        val reassembler = PacketReassembler(timeoutMs = 5000L)
        var completedPacket: ByteArray? = null

        for (fragBytes in fragments) {
            val result = reassembler.processFragmentBytes(fragBytes)
            if (result != null) {
                completedPacket = result
            }
        }

        assertNotNull("Reassembly must produce completed byte array", completedPacket)
        assertArrayEquals(originalMessage, completedPacket)
        assertEquals(0, reassembler.activeBufferCount())
    }

    @Test
    fun testOutOfOrderReassembly() {
        val originalMessage = "Out-of-order decentralized packet transport testing".toByteArray()
        val packetId = 1122334455L

        val fragments = PacketFragmenter.fragment(
            serializedPacket = originalMessage,
            packetId = packetId,
            maxFragmentPayload = 10 // split into small fragments
        )

        val shuffledFragments = fragments.shuffled()

        val reassembler = PacketReassembler(timeoutMs = 5000L)
        var completedPacket: ByteArray? = null

        for (fragBytes in shuffledFragments) {
            val result = reassembler.processFragmentBytes(fragBytes)
            if (result != null) {
                completedPacket = result
            }
        }

        assertNotNull("Reassembly must work regardless of arrival order", completedPacket)
        assertArrayEquals(originalMessage, completedPacket)
    }

    @Test
    fun testDuplicateFragmentIgnored() {
        val originalMessage = "Duplicate test payload".toByteArray()
        val packetId = 998877L

        val fragments = PacketFragmenter.fragment(
            serializedPacket = originalMessage,
            packetId = packetId,
            maxFragmentPayload = 10
        )

        val reassembler = PacketReassembler(timeoutMs = 5000L)

        // Feed first fragment twice
        reassembler.processFragmentBytes(fragments[0])
        val dupResult = reassembler.processFragmentBytes(fragments[0])
        assertNull("Duplicate fragment must not complete packet prematurely", dupResult)
    }

    @Test
    fun testBufferTimeoutPruning() {
        val reassembler = PacketReassembler(timeoutMs = 10L) // 10ms timeout
        val frag = PacketFragment(packetId = 111L, totalFragments = 3, fragmentIndex = 0, data = ByteArray(10))

        reassembler.processFragment(frag)
        assertEquals(1, reassembler.activeBufferCount())

        Thread.sleep(25L)
        reassembler.pruneExpiredBuffers()

        assertEquals(0, reassembler.activeBufferCount())
    }
}

class PacketCompressionTest {
    @Test
    fun testCompressionRoundtrip() {
        val text = "Hexnet offline mesh payload compression verification. ".repeat(10)
        val originalBytes = text.toByteArray(Charsets.UTF_8)

        assertTrue(PacketCompression.shouldCompress(originalBytes.size))

        val compressed = PacketCompression.compress(originalBytes)
        assertTrue(compressed.size < originalBytes.size)

        val decompressed = PacketCompression.decompress(compressed)
        assertArrayEquals(originalBytes, decompressed)
    }
}
