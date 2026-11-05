package net.hexnet.app.routing

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.hexnet.app.core.crypto.KeyPairBytes
import net.hexnet.app.core.crypto.NoiseSessionManager
import net.hexnet.app.core.database.dao.OutboundQueueDao
import net.hexnet.app.core.database.dao.RelayQueueDao
import net.hexnet.app.core.database.entity.OutboundPacketEntity
import net.hexnet.app.core.database.entity.RelayPacketEntity
import net.hexnet.app.core.model.MessageDeliveryState
import net.hexnet.app.core.protocol.BinaryPacket
import net.hexnet.app.core.protocol.PacketFlags
import net.hexnet.app.core.protocol.PacketSerializer
import net.hexnet.app.core.protocol.PacketType
import net.hexnet.app.core.routing.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Automated test suite covering reliable message delivery, crash recovery,
 * persistent queues, cryptographic ACK authentication, retry backoff, duplicate suppression,
 * storage limits, and concurrent queue processing.
 */
class ReliableDeliveryTest {

    // Mock in-memory OutboundQueueDao for crash/recovery testing
    class MockOutboundQueueDao : OutboundQueueDao {
        val storage = ConcurrentHashMap<Long, OutboundPacketEntity>()
        var shouldFailWrites = false

        override suspend fun getAllPendingPackets(): List<OutboundPacketEntity> {
            return storage.values.filter { it.state != "DELIVERED" && it.state != "FAILED" }
        }

        override suspend fun getPacketsDueForRetry(nowEpochMs: Long): List<OutboundPacketEntity> {
            return storage.values.filter {
                it.state != "DELIVERED" && it.state != "FAILED" && it.nextRetryEpochMs <= nowEpochMs
            }
        }

        override suspend fun getPendingCount(): Int {
            return storage.values.count { it.state != "DELIVERED" && it.state != "FAILED" }
        }

        override suspend fun getPacket(packetId: Long): OutboundPacketEntity? = storage[packetId]

        override suspend fun insertPacket(packet: OutboundPacketEntity) {
            if (shouldFailWrites) {
                throw IllegalStateException("Simulated SQLite Disk I/O Crash Exception")
            }
            storage[packet.packetId] = packet
        }

        override suspend fun updatePacket(packet: OutboundPacketEntity) {
            storage[packet.packetId] = packet
        }

        override suspend fun updateRetryProgress(packetId: Long, state: String, retryCount: Int, nextRetryEpochMs: Long) {
            val existing = storage[packetId]
            if (existing != null) {
                storage[packetId] = existing.copy(
                    state = state,
                    retryCount = retryCount,
                    nextRetryEpochMs = nextRetryEpochMs
                )
            }
        }

        override suspend fun updateState(packetId: Long, state: String) {
            val existing = storage[packetId]
            if (existing != null) {
                storage[packetId] = existing.copy(state = state)
            }
        }

        override suspend fun markDelivered(packetId: Long) {
            updateState(packetId, "DELIVERED")
        }

        override suspend fun deletePacket(packetId: Long) {
            storage.remove(packetId)
        }

        override suspend fun pruneCompleted() {
            storage.entries.removeIf { it.value.state == "DELIVERED" || it.value.state == "FAILED" }
        }

        override suspend fun pruneOldPackets(olderThanEpochMs: Long): Int {
            val countBefore = storage.size
            storage.entries.removeIf { it.value.createdAtEpochMs < olderThanEpochMs }
            return countBefore - storage.size
        }
    }

    // Mock in-memory RelayQueueDao for store-and-forward storage testing
    class MockRelayQueueDao : RelayQueueDao {
        val storage = ConcurrentHashMap<Long, RelayPacketEntity>()

        override suspend fun getPacketsForDestination(destinationHex: Long, nowEpochMs: Long): List<RelayPacketEntity> {
            return storage.values.filter { it.destinationHex == destinationHex && it.expiresEpochMs > nowEpochMs }
        }

        override suspend fun getAllActiveRelayPackets(nowEpochMs: Long): List<RelayPacketEntity> {
            return storage.values.filter { it.expiresEpochMs > nowEpochMs }
        }

        override suspend fun getCount(): Int = storage.size

        override suspend fun getPacket(packetId: Long): RelayPacketEntity? = storage[packetId]

        override suspend fun insertRelayPacket(packet: RelayPacketEntity) {
            storage[packet.packetId] = packet
        }

        override suspend fun deleteRelayPacket(packetId: Long) {
            storage.remove(packetId)
        }

        override suspend fun pruneExpired(nowEpochMs: Long): Int {
            val countBefore = storage.size
            storage.entries.removeIf { it.value.expiresEpochMs <= nowEpochMs }
            return countBefore - storage.size
        }

        override suspend fun evictOldest(count: Int): Int {
            val sortedKeys = storage.values.sortedBy { it.expiresEpochMs }.take(count).map { it.packetId }
            sortedKeys.forEach { storage.remove(it) }
            return sortedKeys.size
        }
    }

    @Test
    fun testQueueRecoveryAfterProcessRestart() = runBlocking {
        val mockOutboundDao = MockOutboundQueueDao()
        val mockRelayDao = MockRelayQueueDao()

        // 1. Simulate active session before process crash
        val nodeA = MeshRoutingEngine(
            localHexAddress = 0xAAAA01L,
            outboundQueueDao = mockOutboundDao,
            relayQueueDao = mockRelayDao
        )

        // Enqueue 3 outbound messages
        nodeA.sendDirectMessage(destinationHex = 0xBBBB02L, plaintext = "Message 1".toByteArray(), packetId = 1001L)
        nodeA.sendDirectMessage(destinationHex = 0xBBBB02L, plaintext = "Message 2".toByteArray(), packetId = 1002L)
        nodeA.sendDirectMessage(destinationHex = 0xBBBB02L, plaintext = "Message 3".toByteArray(), packetId = 1003L)

        // Store 2 relay packets
        val relayPacket1 = BinaryPacket(type = PacketType.DIRECT_MESSAGE, sourceHex = 0xCCCC03L, destinationHex = 0xDDDD04L, payload = ByteArray(10), packetId = 2001L)
        val relayPacket2 = BinaryPacket(type = PacketType.DIRECT_MESSAGE, sourceHex = 0xCCCC03L, destinationHex = 0xDDDD04L, payload = ByteArray(10), packetId = 2002L)
        nodeA.storeForRelay(relayPacket1, ttlMs = 60_000)
        nodeA.storeForRelay(relayPacket2, ttlMs = 60_000)

        assertEquals(3, mockOutboundDao.getPendingCount())
        assertEquals(2, mockRelayDao.getCount())

        // 2. Simulate Process Crash & Resurrection
        nodeA.stopQueueWorker()

        // A new instance is created with the same database
        val resurrectedNodeA = MeshRoutingEngine(
            localHexAddress = 0xAAAA01L,
            outboundQueueDao = mockOutboundDao,
            relayQueueDao = mockRelayDao
        )

        // Perform recovery
        resurrectedNodeA.recoverQueues()

        // Verify that pending outbound and relay queues were recovered
        assertEquals(3, resurrectedNodeA.getPendingOutboundCount())
        assertEquals(2, resurrectedNodeA.getStoredRelayCount())
        assertEquals("PENDING", resurrectedNodeA.getOutboundPacketState(1001L))
        assertEquals("PENDING", resurrectedNodeA.getOutboundPacketState(1002L))
        assertEquals("PENDING", resurrectedNodeA.getOutboundPacketState(1003L))

        resurrectedNodeA.stopQueueWorker()
    }

    @Test
    fun testFailedPersistenceNeverMarksAsSent() = runBlocking {
        val mockOutboundDao = MockOutboundQueueDao().apply { shouldFailWrites = true }

        val node = MeshRoutingEngine(
            localHexAddress = 0xAAAA01L,
            outboundQueueDao = mockOutboundDao
        )

        var lastRecordedState: String? = null
        node.onDeliveryStateChanged = { _, state -> lastRecordedState = state }

        try {
            node.sendDirectMessage(destinationHex = 0xBBBB02L, plaintext = "Critical data".toByteArray(), packetId = 9999L)
            fail("Expected exception due to failed database persistence")
        } catch (e: IllegalStateException) {
            // Write failed as expected
            assertEquals("Simulated SQLite Disk I/O Crash Exception", e.message)
        }

        // Verify that state did NOT transition to SENDING or DELIVERED
        assertEquals("FAILED_PERSISTENCE", lastRecordedState)
        node.stopQueueWorker()
    }

    @Test
    fun testDuplicatePacketSuppression() {
        val node = MeshRoutingEngine(localHexAddress = 0xAAAA01L)

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            flags = PacketFlags.NONE,
            sourceHex = 0xBBBB02L,
            destinationHex = 0xAAAA01L,
            packetId = 5555L,
            payload = "Hello".toByteArray()
        )
        val raw = PacketSerializer.serialize(packet)

        // 1st receipt: Accepted locally
        val decision1 = node.processIncomingPacket("DEV_01", raw)
        assertTrue(decision1 is RoutingDecision.AcceptLocally)

        // 2nd receipt (duplicate across another mesh path): Dropped
        val decision2 = node.processIncomingPacket("DEV_02", raw)
        assertTrue(decision2 is RoutingDecision.DropDuplicate)
        assertEquals(5555L, (decision2 as RoutingDecision.DropDuplicate).packetId)

        node.stopQueueWorker()
    }

    @Test
    fun testAuthenticatedEndToEndAckDeliveryVerification() = runBlocking {
        // Setup two nodes with mutual Noise sessions
        val aliceStatic = KeyPairBytes(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 })
        val bobStatic = KeyPairBytes(ByteArray(32) { 0x03 }, ByteArray(32) { 0x04 })

        val aliceSm = NoiseSessionManager(aliceStatic)
        val bobSm = NoiseSessionManager(bobStatic)

        // Establish Noise Session between Alice (0x01) and Bob (0x02)
        val msg1 = aliceSm.startHandshake("2")
        val res1 = bobSm.processHandshakeMessage("1", msg1) as net.hexnet.app.core.crypto.HandshakeProcessResult.Reply
        val res2 = aliceSm.processHandshakeMessage("2", res1.messageBytes) as net.hexnet.app.core.crypto.HandshakeProcessResult.EstablishedWithReply
        bobSm.processHandshakeMessage("1", res2.messageBytes)

        assertTrue(aliceSm.hasActiveSession("2"))
        assertTrue(bobSm.hasActiveSession("1"))

        val aliceEngine = MeshRoutingEngine(localHexAddress = 0x01L, sessionManager = aliceSm)
        val bobEngine = MeshRoutingEngine(localHexAddress = 0x02L, sessionManager = bobSm)

        var lastDispatchedToBob: BinaryPacket? = null
        aliceEngine.transmitCallback = { _, p -> lastDispatchedToBob = p }

        var deliveredPacketId: Long? = null
        aliceEngine.onDeliveryStateChanged = { id, state ->
            if (state == "DELIVERED") deliveredPacketId = id
        }

        // Alice sends direct encrypted message to Bob
        val msgPacket = aliceEngine.sendDirectMessage(destinationHex = 0x02L, plaintext = "Secure Hello".toByteArray(), packetId = 7777L)
        assertTrue(PacketFlags.hasFlag(msgPacket.flags, PacketFlags.ENCRYPTED))

        // Bob receives Alice's message, decrypts it, and automatically generates an encrypted E2E DeliveryAck
        var ackFromBob: BinaryPacket? = null
        bobEngine.transmitCallback = { _, ackPacket -> ackFromBob = ackPacket }

        val bobRaw = PacketSerializer.serialize(msgPacket)
        val decision = bobEngine.processIncomingPacket("ALICE_DEV", bobRaw)
        assertTrue(decision is RoutingDecision.AcceptLocally)
        assertEquals("Secure Hello", String((decision as RoutingDecision.AcceptLocally).decryptedPayload!!))

        assertNotNull(ackFromBob)
        // Verify that Bob's E2E ACK is authenticated with ENCRYPTED flag
        assertTrue(PacketFlags.hasFlag(ackFromBob!!.flags, PacketFlags.ENCRYPTED))

        // Alice receives Bob's authenticated E2E DeliveryAck
        val ackRaw = PacketSerializer.serialize(ackFromBob!!)
        val aliceAckDecision = aliceEngine.processIncomingPacket("BOB_DEV", ackRaw)
        assertTrue(aliceAckDecision is RoutingDecision.AcceptLocally)

        // Alice's delivery state confirmed!
        assertEquals(7777L, deliveredPacketId)

        aliceEngine.stopQueueWorker()
        bobEngine.stopQueueWorker()
    }

    @Test
    fun testUnauthenticatedAckSpoofingRejected() = runBlocking {
        // Alice expects an encrypted direct message confirmation from Bob
        val aliceStatic = KeyPairBytes(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 })
        val aliceSm = NoiseSessionManager(aliceStatic)
        val bobStatic = KeyPairBytes(ByteArray(32) { 0x03 }, ByteArray(32) { 0x04 })
        val bobSm = NoiseSessionManager(bobStatic)

        // Handshake
        val msg1 = aliceSm.startHandshake("2")
        val res1 = bobSm.processHandshakeMessage("1", msg1) as net.hexnet.app.core.crypto.HandshakeProcessResult.Reply
        val res2 = aliceSm.processHandshakeMessage("2", res1.messageBytes) as net.hexnet.app.core.crypto.HandshakeProcessResult.EstablishedWithReply
        bobSm.processHandshakeMessage("1", res2.messageBytes)

        val aliceEngine = MeshRoutingEngine(localHexAddress = 0x01L, sessionManager = aliceSm)
        aliceEngine.sendDirectMessage(destinationHex = 0x02L, plaintext = "Classified".toByteArray(), packetId = 8888L)

        // Attacker creates a spoofed, unencrypted DeliveryAck pretending to be Bob
        val spoofedAckPayload = DeliveryAck.serialize(
            DeliveryAck(
                originalPacketId = 8888L,
                isEndToEnd = true,
                ackSenderHex = 0x02L,
                originalSenderHex = 0x01L
            )
        )
        val spoofedPacket = BinaryPacket(
            type = PacketType.DELIVERY_ACK,
            flags = PacketFlags.NONE, // NOT encrypted!
            sourceHex = 0x02L,
            destinationHex = 0x01L,
            packetId = 9991L,
            payload = spoofedAckPayload
        )

        val rawSpoof = PacketSerializer.serialize(spoofedPacket)
        val decision = aliceEngine.processIncomingPacket("ROGUE_DEV", rawSpoof)

        // Must drop spoofed unauthenticated ACK!
        assertTrue(decision is RoutingDecision.DropInvalidChecksum)
        assertEquals("Rejected unencrypted ACK for encrypted direct message", (decision as RoutingDecision.DropInvalidChecksum).reason)

        // Verify message is NOT marked delivered
        assertNotEquals("DELIVERED", aliceEngine.getOutboundPacketState(8888L))

        aliceEngine.stopQueueWorker()
    }

    @Test
    fun testAckReplayIsIdempotent() {
        val aliceEngine = MeshRoutingEngine(localHexAddress = 0x01L)
        val ackCount = AtomicInteger(0)

        aliceEngine.onDeliveryStateChanged = { id, state ->
            if (state == "DELIVERED") ackCount.incrementAndGet()
        }

        val ack = DeliveryAck(originalPacketId = 3333L, isEndToEnd = true, ackSenderHex = 0x02L, originalSenderHex = 0x01L)

        // 1st receipt
        aliceEngine.handleIncomingE2eAck(ack)
        assertEquals(1, ackCount.get())

        // 2nd replayed receipt: Ignored without duplicate notification
        aliceEngine.handleIncomingE2eAck(ack)
        assertEquals(1, ackCount.get())

        aliceEngine.stopQueueWorker()
    }

    @Test
    fun testBoundedExponentialRetryScheduling() = runBlocking {
        val mockDao = MockOutboundQueueDao()
        val node = MeshRoutingEngine(localHexAddress = 0xAAAA01L, outboundQueueDao = mockDao)

        // Insert a pending packet due for retry
        val entity = OutboundPacketEntity(
            packetId = 4444L,
            destinationHex = 0xBBBB02L,
            packetType = PacketType.DIRECT_MESSAGE.code,
            rawPacketBytes = PacketSerializer.serialize(BinaryPacket(type = PacketType.DIRECT_MESSAGE, sourceHex = 0xAAAA01L, destinationHex = 0xBBBB02L, payload = ByteArray(4), packetId = 4444L)),
            retryCount = 0,
            maxRetries = 3,
            nextRetryEpochMs = System.currentTimeMillis() - 1000L, // Due now
            createdAtEpochMs = System.currentTimeMillis() - 5000L,
            state = "PENDING"
        )
        mockDao.insertPacket(entity)

        // Process retry 1: retryCount becomes 1, backoff is 1500ms
        node.processDueRetries()
        val p1 = mockDao.getPacket(4444L)!!
        assertEquals(1, p1.retryCount)
        assertEquals("RETRYING", p1.state)

        // Process retry 2
        mockDao.updateRetryProgress(4444L, "RETRYING", 1, System.currentTimeMillis() - 1000L)
        node.processDueRetries()
        val p2 = mockDao.getPacket(4444L)!!
        assertEquals(2, p2.retryCount)

        // Process retry 3
        mockDao.updateRetryProgress(4444L, "RETRYING", 2, System.currentTimeMillis() - 1000L)
        node.processDueRetries()
        val p3 = mockDao.getPacket(4444L)!!
        assertEquals(3, p3.retryCount)

        // Exceeded max retries (3): marked as FAILED
        mockDao.updateRetryProgress(4444L, "RETRYING", 3, System.currentTimeMillis() - 1000L)
        node.processDueRetries()
        val p4 = mockDao.getPacket(4444L)!!
        assertEquals("FAILED", p4.state)

        node.stopQueueWorker()
    }

    @Test
    fun testRelayStorageLimitsAndEviction() = runBlocking {
        val mockRelayDao = MockRelayQueueDao()
        val node = MeshRoutingEngine(localHexAddress = 0xAAAA01L, relayQueueDao = mockRelayDao)

        // Fill relay queue to capacity (300)
        val now = System.currentTimeMillis()
        for (i in 1..300) {
            val p = BinaryPacket(type = PacketType.DIRECT_MESSAGE, sourceHex = 0x01L, destinationHex = 0x02L, payload = ByteArray(4), packetId = i.toLong())
            mockRelayDao.insertRelayPacket(RelayPacketEntity(
                packetId = i.toLong(),
                destinationHex = 0x02L,
                sourceHex = 0x01L,
                rawPacketBytes = PacketSerializer.serialize(p),
                expiresEpochMs = now + 10_000L * i,
                receivedEpochMs = now
            ))
        }
        assertEquals(300, mockRelayDao.getCount())

        // Adding 301st packet triggers eviction of oldest 10 packets
        val extraPacket = BinaryPacket(type = PacketType.DIRECT_MESSAGE, sourceHex = 0x01L, destinationHex = 0x02L, payload = ByteArray(4), packetId = 9999L)
        node.storeForRelay(extraPacket, ttlMs = 50_000L)

        // Capacity bounded: 300 - 10 evicted + 1 added = 291
        assertEquals(291, mockRelayDao.getCount())
        assertNull(mockRelayDao.getPacket(1L)) // Oldest evicted
        assertNotNull(mockRelayDao.getPacket(9999L)) // New packet preserved

        node.stopQueueWorker()
    }

    @Test
    fun testExpiredRelayPacketPruning() = runBlocking {
        val mockRelayDao = MockRelayQueueDao()
        val now = System.currentTimeMillis()

        // 2 expired packets, 1 active packet
        mockRelayDao.insertRelayPacket(RelayPacketEntity(packetId = 1L, destinationHex = 2L, sourceHex = 1L, rawPacketBytes = ByteArray(10), expiresEpochMs = now - 5000L, receivedEpochMs = now - 10000L))
        mockRelayDao.insertRelayPacket(RelayPacketEntity(packetId = 2L, destinationHex = 2L, sourceHex = 1L, rawPacketBytes = ByteArray(10), expiresEpochMs = now - 1000L, receivedEpochMs = now - 10000L))
        mockRelayDao.insertRelayPacket(RelayPacketEntity(packetId = 3L, destinationHex = 2L, sourceHex = 1L, rawPacketBytes = ByteArray(10), expiresEpochMs = now + 30000L, receivedEpochMs = now))

        assertEquals(3, mockRelayDao.getCount())
        val pruned = mockRelayDao.pruneExpired(now)
        assertEquals(2, pruned)
        assertEquals(1, mockRelayDao.getCount())
        assertNotNull(mockRelayDao.getPacket(3L))
    }

    @Test
    fun testConcurrentMessageQueueProcessing() = runBlocking {
        val mockOutboundDao = MockOutboundQueueDao()
        val node = MeshRoutingEngine(localHexAddress = 0xAAAA01L, outboundQueueDao = mockOutboundDao)

        // Launch 20 concurrent coroutines sending messages
        val jobs = (1..20).map { idx ->
            launch(Dispatchers.Default) {
                node.sendDirectMessage(
                    destinationHex = 0xBBBB02L,
                    plaintext = "Concurrent payload $idx".toByteArray(),
                    packetId = (10000 + idx).toLong()
                )
            }
        }
        jobs.joinAll()

        assertEquals(20, mockOutboundDao.getPendingCount())
        assertEquals(20, node.getPendingOutboundCount())

        node.stopQueueWorker()
    }
}
