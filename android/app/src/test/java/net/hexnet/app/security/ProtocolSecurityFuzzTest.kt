package net.hexnet.app.security

import net.hexnet.app.core.protocol.*
import net.hexnet.app.core.protocol.fragmentation.FragmentSerializer
import net.hexnet.app.core.protocol.fragmentation.PacketFragment
import net.hexnet.app.core.protocol.fragmentation.PacketFragmenter
import net.hexnet.app.core.protocol.fragmentation.PacketReassembler
import net.hexnet.app.core.routing.NeighborTable
import net.hexnet.app.core.routing.RouteTable
import net.hexnet.app.core.routing.RoutingDecision
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * Hostile Protocol Security & Fuzzing Test Suite for Hexnet.
 *
 * Simulates adversarial, malformed, out-of-order, oversized, and resource-exhausting
 * inputs from untrusted peers to verify that Hexnet is robust and immune to crashes,
 * unbounded memory consumption, route poisoning, and spoofing attacks.
 */
class ProtocolSecurityFuzzTest {

    private val deterministicRandom = Random(13374858L)

    // =========================================================================
    // 1. PROPERTY-BASED SERIALIZATION & DESERIALIZATION TESTS
    // =========================================================================

    @Test
    fun testPropertyBasedSerializationRoundtrip() {
        val types = PacketType.values()

        // Test 100 random valid packets with diverse payload sizes
        for (i in 0 until 100) {
            val payloadSize = deterministicRandom.nextInt(BinaryPacket.MAX_SAFE_PACKET_PAYLOAD + 1)
            val payload = ByteArray(payloadSize)
            deterministicRandom.nextBytes(payload)

            val packet = BinaryPacket(
                version = BinaryPacket.PROTOCOL_VERSION,
                type = types[deterministicRandom.nextInt(types.size)],
                flags = deterministicRandom.nextInt(16).toByte(),
                ttl = (1 + deterministicRandom.nextInt(BinaryPacket.MAX_TTL.toInt())).toByte(),
                hopCount = deterministicRandom.nextInt(BinaryPacket.MAX_TTL.toInt()).toByte(),
                timestamp = System.currentTimeMillis() + deterministicRandom.nextInt(10000),
                packetId = deterministicRandom.nextLong(),
                sourceHex = deterministicRandom.nextLong(),
                destinationHex = deterministicRandom.nextLong(),
                payload = payload
            )

            val serialized = PacketSerializer.serialize(packet)
            val deserializedResult = PacketSerializer.deserialize(serialized)

            assertTrue("Failed to deserialize valid packet $i", deserializedResult.isSuccess)
            val restored = deserializedResult.getOrThrow()

            assertEquals(packet.packetId, restored.packetId)
            assertEquals(packet.type, restored.type)
            assertEquals(packet.flags, restored.flags)
            assertEquals(packet.ttl, restored.ttl)
            assertEquals(packet.hopCount, restored.hopCount)
            assertEquals(packet.sourceHex, restored.sourceHex)
            assertEquals(packet.destinationHex, restored.destinationHex)
            assertArrayEquals(packet.payload, restored.payload)
        }
    }

    // =========================================================================
    // 2. FUZZ TESTS FOR PACKET PARSING & MALFORMED INPUT
    // =========================================================================

    @Test
    fun testRandomByteFuzzingNeverCrashesPacketSerializer() {
        // Feed 500 arbitrary random byte buffers of length 0 to 10,000 bytes
        for (i in 0 until 500) {
            val length = deterministicRandom.nextInt(10_000)
            val randomBytes = ByteArray(length)
            deterministicRandom.nextBytes(randomBytes)

            val result = PacketSerializer.deserialize(randomBytes)
            // Invariant: Must return Result.failure and NEVER throw uncaught runtime exceptions
            if (result.isSuccess) {
                // Highly unlikely with CRC32 and magic checks, but if valid, verify non-null
                assertNotNull(result.getOrNull())
            } else {
                assertNotNull(result.exceptionOrNull())
            }
        }
    }

    @Test
    fun testMutatedHeaderBitFlipFuzzingRejectsCorruptPackets() {
        val validPacket = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            flags = PacketFlags.NONE,
            ttl = 5,
            hopCount = 0,
            packetId = 12345678L,
            sourceHex = 0xAA01L,
            destinationHex = 0xBB02L,
            payload = "Sensitive authenticated payload".toByteArray()
        )
        val validBytes = PacketSerializer.serialize(validPacket)

        // Mutate single and multiple bytes across header and payload (100 iterations)
        for (i in 0 until 100) {
            val mutated = validBytes.clone()
            val byteToMutate = deterministicRandom.nextInt(mutated.size)
            mutated[byteToMutate] = (mutated[byteToMutate].toInt() xor (1 + deterministicRandom.nextInt(255))).toByte()

            val result = PacketSerializer.deserialize(mutated)
            // CRC32 or header validation MUST reject mutated packets
            assertTrue("Mutated packet $i should have been rejected", result.isFailure)
        }
    }

    // =========================================================================
    // 3. BOUNDARY VALUE TESTS (PACKET LENGTHS, TTL, HOP COUNT)
    // =========================================================================

    @Test
    fun testBoundaryPayloadLengths() {
        // 0-byte payload (empty payload boundary)
        val emptyPacket = BinaryPacket(type = PacketType.DISCOVERY_BEACON, sourceHex = 1L, destinationHex = 2L, payload = ByteArray(0))
        val emptySerialized = PacketSerializer.serialize(emptyPacket)
        assertEquals(BinaryPacket.TOTAL_FRAMING_OVERHEAD, emptySerialized.size) // Exactly 44 bytes
        assertTrue(PacketSerializer.deserialize(emptySerialized).isSuccess)

        // 8192-byte payload (MAX_SAFE_PACKET_PAYLOAD boundary)
        val maxSafePacket = BinaryPacket(type = PacketType.DIRECT_MESSAGE, sourceHex = 1L, destinationHex = 2L, payload = ByteArray(BinaryPacket.MAX_SAFE_PACKET_PAYLOAD))
        val maxSerialized = PacketSerializer.serialize(maxSafePacket)
        assertEquals(BinaryPacket.TOTAL_FRAMING_OVERHEAD + BinaryPacket.MAX_SAFE_PACKET_PAYLOAD, maxSerialized.size)
        assertTrue(PacketSerializer.deserialize(maxSerialized).isSuccess)

        // 8193-byte payload (Exceeding max safe payload by 1 byte)
        try {
            val oversizePacket = BinaryPacket(type = PacketType.DIRECT_MESSAGE, sourceHex = 1L, destinationHex = 2L, payload = ByteArray(BinaryPacket.MAX_SAFE_PACKET_PAYLOAD + 1))
            PacketSerializer.serialize(oversizePacket)
            fail("Expected IllegalArgumentException for oversized payload")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exceeds maximum safe limit"))
        }
    }

    @Test
    fun testBoundaryTtlAndHopCountDefenses() {
        // Negative TTL
        val invalidTtlBytes = PacketSerializer.serialize(
            BinaryPacket(type = PacketType.DIRECT_MESSAGE, sourceHex = 1L, destinationHex = 2L, payload = ByteArray(4), ttl = 5)
        )
        invalidTtlBytes[4] = -1 // Override TTL byte with -1
        assertFalse(PacketSerializer.deserialize(invalidTtlBytes).isSuccess)

        // Over-inflated TTL (e.g. 127) is clamped to MAX_TTL (7)
        val inflatedTtlPacket = BinaryPacket(type = PacketType.DIRECT_MESSAGE, sourceHex = 1L, destinationHex = 2L, payload = ByteArray(4), ttl = 127)
        val serializedInflated = PacketSerializer.serialize(inflatedTtlPacket)
        val deserializedInflated = PacketSerializer.deserialize(serializedInflated).getOrThrow()
        assertEquals(BinaryPacket.MAX_TTL, deserializedInflated.ttl)

        // Negative Hop Count
        val invalidHopBytes = PacketSerializer.serialize(
            BinaryPacket(type = PacketType.DIRECT_MESSAGE, sourceHex = 1L, destinationHex = 2L, payload = ByteArray(4))
        )
        invalidHopBytes[5] = -2 // Override hop count byte with -2
        assertFalse(PacketSerializer.deserialize(invalidHopBytes).isSuccess)
    }

    // =========================================================================
    // 4. FRAGMENTATION & REASSEMBLY FUZZING & BOUNDARIES
    // =========================================================================

    @Test
    fun testFragmentReassemblyWithOutOfOrderAndDuplicates() {
        val reassembler = PacketReassembler(timeoutMs = 5000L)
        val fullData = ByteArray(600) { it.toByte() }
        val packetId = 889900L

        val fragments = PacketFragmenter.fragment(fullData, packetId, maxFragmentPayload = 100)
        assertEquals(6, fragments.size) // 6 fragments

        // Feed fragments out of order with duplicates: [2, 0, 0 (duplicate), 4, 1, 3, 5]
        assertNull(reassembler.processFragmentBytes(fragments[2]))
        assertNull(reassembler.processFragmentBytes(fragments[0]))
        assertNull(reassembler.processFragmentBytes(fragments[0])) // Duplicate ignored
        assertNull(reassembler.processFragmentBytes(fragments[4]))
        assertNull(reassembler.processFragmentBytes(fragments[1]))
        assertNull(reassembler.processFragmentBytes(fragments[3]))

        // Final completing fragment (5)
        val reconstructed = reassembler.processFragmentBytes(fragments[5])
        assertNotNull(reconstructed)
        assertArrayEquals(fullData, reconstructed)
    }

    @Test
    fun testMalformedFragmentDeserializationRejection() {
        // Less than fragment header (13 bytes)
        assertFalse(FragmentSerializer.deserialize(ByteArray(12)).isSuccess)

        // Invalid magic byte
        val badMagic = ByteArray(20) { 0x00 }
        assertFalse(FragmentSerializer.deserialize(badMagic).isSuccess)

        // Negative or zero total fragments
        val zeroTotal = ByteArray(20).apply {
            this[0] = PacketFragment.FRAGMENT_MAGIC
            this[9] = 0 // total = 0
            this[10] = 0
        }
        assertFalse(FragmentSerializer.deserialize(zeroTotal).isSuccess)

        // Fragment index >= total
        val outOfBoundsIndex = ByteArray(20).apply {
            this[0] = PacketFragment.FRAGMENT_MAGIC
            this[9] = 0; this[10] = 5 // total = 5
            this[11] = 0; this[12] = 5 // index = 5 (invalid, valid is 0..4)
        }
        assertFalse(FragmentSerializer.deserialize(outOfBoundsIndex).isSuccess)

        // Fragment payload > 1024 bytes rejected
        val oversizeFragment = ByteArray(PacketFragment.HEADER_SIZE + 1025).apply {
            this[0] = PacketFragment.FRAGMENT_MAGIC
            this[9] = 0; this[10] = 10
            this[11] = 0; this[12] = 1
        }
        assertFalse(FragmentSerializer.deserialize(oversizeFragment).isSuccess)
    }

    @Test
    fun testReassemblyBufferDoSExhaustionDefense() {
        val reassembler = PacketReassembler(timeoutMs = 10000L)

        // Flood 1000 distinct incomplete packet fragments
        for (i in 1..1000) {
            val fragment = PacketFragment(
                packetId = i.toLong(),
                totalFragments = 5,
                fragmentIndex = 0,
                data = ByteArray(20)
            )
            reassembler.processFragment(fragment)
        }

        // Must be strictly capped at MAX_IN_FLIGHT_BUFFERS = 128
        assertEquals(PacketReassembler.MAX_IN_FLIGHT_BUFFERS, reassembler.activeBufferCount())
    }

    // =========================================================================
    // 5. RESOURCE LIMIT & CAPACITY EXHAUSTION TESTS
    // =========================================================================

    @Test
    fun testRouteTablePoisoningAndCapacityExhaustion() {
        val routeTable = RouteTable()

        // Attempt 1: Route poisoning with hopDistance = 0
        assertFalse(routeTable.updateRoute(destinationHex = 0x11L, nextHopAddress = "DEV_01", hopDistance = 0))

        // Attempt 2: Route poisoning with negative hopDistance
        assertFalse(routeTable.updateRoute(destinationHex = 0x11L, nextHopAddress = "DEV_01", hopDistance = -3))

        // Attempt 3: Route poisoning with hopDistance > MAX_TTL (7)
        assertFalse(routeTable.updateRoute(destinationHex = 0x11L, nextHopAddress = "DEV_01", hopDistance = 8))

        // Valid route: Accepted
        assertTrue(routeTable.updateRoute(destinationHex = 0x11L, nextHopAddress = "DEV_01", hopDistance = 2))

        // Flood 500 routes: Enforces MAX_ROUTES = 256
        for (i in 1..500) {
            routeTable.updateRoute(destinationHex = (1000 + i).toLong(), nextHopAddress = "DEV_$i", hopDistance = 2)
        }
        assertTrue(routeTable.size() <= RouteTable.MAX_ROUTES)
    }

    @Test
    fun testNeighborTableCapacityExhaustion() {
        val neighborTable = NeighborTable()

        // Flood 100 distinct neighbor device MACs
        for (i in 1..100) {
            neighborTable.addOrUpdateNeighbor(
                deviceAddress = "AA:BB:CC:DD:%02X:%02X".format(i / 256, i % 256),
                hexAddress = i.toLong(),
                rssiDbm = -60
            )
        }

        // Must be capped at MAX_NEIGHBORS = 64
        assertEquals(NeighborTable.MAX_NEIGHBORS, neighborTable.size())
    }

    // =========================================================================
    // 6. BROADCAST STORM & FLOODING RATE LIMITING
    // =========================================================================

    @Test
    fun testBroadcastStormRateLimitingProtection() {
        val engine = net.hexnet.app.core.routing.MeshRoutingEngine(localHexAddress = 0xAAAA01L)

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = 0xBBBB02L,
            destinationHex = 0xAAAA01L,
            packetId = 1L,
            payload = "Ping".toByteArray()
        )
        val raw = PacketSerializer.serialize(packet)

        var acceptedCount = 0
        var rateLimitedCount = 0

        // Simulate a rogue peer blasting 50 packets rapidly within a single burst
        for (i in 1..50) {
            val decision = engine.processIncomingPacket("ROGUE_DEV_01", raw)
            if (decision is RoutingDecision.DropInvalidChecksum && decision.reason.contains("Rate limit exceeded")) {
                rateLimitedCount++
            } else {
                acceptedCount++
            }
        }

        // Rate limiter allows up to 30 packets/second, then drops subsequent flooding packets
        assertEquals(30, acceptedCount)
        assertEquals(20, rateLimitedCount)

        engine.stopQueueWorker()
    }
}
