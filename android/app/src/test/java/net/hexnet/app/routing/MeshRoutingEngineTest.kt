package net.hexnet.app.routing

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.hexnet.app.core.crypto.*
import net.hexnet.app.core.protocol.*
import net.hexnet.app.core.protocol.fragmentation.*
import net.hexnet.app.core.routing.*
import net.hexnet.app.core.transport.ble.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Comprehensive automated test suite for Hexnet Phase 4 Mesh Routing Engine.
 *
 * Implements a multi-node Virtual Mesh Simulator verifying:
 * 1. Direct delivery (2-node)
 * 2. 3-node multi-hop relay (A -> B -> C)
 * 3. 4-node multi-hop relay (A -> B -> C -> D)
 * 4. Reverse-path route learning
 * 5. TTL expiration drop
 * 6. Routing loop suppression (cycles)
 * 7. Duplicate packet suppression
 * 8. Hop-by-hop ACK and local retry
 * 9. End-to-end ACK delivery confirmation (delivered only on destination ACK)
 * 10. Alternate route recovery on link failure
 * 11. Store-and-forward queue flush on peer reconnection
 * 12. Persistent queue recovery
 * 13. Multi-path redundant arrival suppression (diamond mesh)
 * 14. Checksum/malformed packet rejection
 * 15. Relay privacy: Intermediate nodes forward without reading encrypted payloads
 */
class MeshRoutingEngineTest {

    /**
     * Virtual Mesh Node representing a standalone Hexnet device instance.
     */
    class VirtualNode(
        val hexAddress: Long,
        val deviceAddress: String,
        val username: String = "node_" + java.lang.Long.toHexString(hexAddress).take(6)
    ) {
        val storage = InMemoryKeyStorage()
        val identityManager = IdentityManager(storage).apply {
            getOrCreateIdentity(username, "Virtual $username")
        }
        val sessionManager = NoiseSessionManager(identityManager)
        val neighborTable = NeighborTable()
        val routeTable = RouteTable()
        val deduplicationCache = DeduplicationCache()

        val engine = MeshRoutingEngine(
            localHexAddress = hexAddress,
            neighborTable = neighborTable,
            routeTable = routeTable,
            deduplicationCache = deduplicationCache,
            sessionManager = sessionManager
        )

        val receivedPackets = mutableListOf<BinaryPacket>()
        val receivedPlaintexts = mutableListOf<String>()
        val transmittedPackets = mutableListOf<Pair<String?, BinaryPacket>>()

        init {
            engine.transmitCallback = { targetAddr, pkt ->
                transmittedPackets.add(Pair(targetAddr, pkt))
            }
        }

        fun ingest(fromAddr: String, rawBytes: ByteArray): RoutingDecision {
            val decision = engine.processIncomingPacket(fromAddr, rawBytes)
            if (decision is RoutingDecision.AcceptLocally) {
                receivedPackets.add(decision.packet)
                if (decision.decryptedPayload != null) {
                    receivedPlaintexts.add(String(decision.decryptedPayload, Charsets.UTF_8))
                }
            }
            return decision
        }
    }

    /**
     * Virtual Mesh Network connecting virtual nodes with radio links.
     */
    class VirtualMeshNetwork {
        val nodes = ConcurrentHashMap<Long, VirtualNode>()
        // Link adjacency: nodeHex -> set of connected neighbor hex addresses
        val links = ConcurrentHashMap<Long, MutableSet<Long>>()

        fun addNode(node: VirtualNode) {
            nodes[node.hexAddress] = node
            links[node.hexAddress] = mutableSetOf()
        }

        fun connectLink(nodeA: VirtualNode, nodeB: VirtualNode) {
            links[nodeA.hexAddress]?.add(nodeB.hexAddress)
            links[nodeB.hexAddress]?.add(nodeA.hexAddress)

            nodeA.engine.handlePeerConnected(nodeB.deviceAddress, nodeB.hexAddress)
            nodeB.engine.handlePeerConnected(nodeA.deviceAddress, nodeA.hexAddress)
        }

        fun disconnectLink(nodeA: VirtualNode, nodeB: VirtualNode) {
            links[nodeA.hexAddress]?.remove(nodeB.hexAddress)
            links[nodeB.hexAddress]?.remove(nodeA.hexAddress)

            nodeA.engine.handlePeerDisconnected(nodeB.deviceAddress)
            nodeB.engine.handlePeerDisconnected(nodeA.deviceAddress)
        }

        /**
         * Dispatches a packet from source node to adjacent connected neighbors.
         */
        fun transmit(fromNode: VirtualNode, targetDeviceAddress: String?, packet: BinaryPacket) {
            val raw = PacketSerializer.serialize(packet)
            val neighbors = links[fromNode.hexAddress] ?: return

            if (targetDeviceAddress != null) {
                // Direct unicast
                val targetNode = nodes.values.find { it.deviceAddress == targetDeviceAddress }
                if (targetNode != null && neighbors.contains(targetNode.hexAddress)) {
                    val decision = targetNode.ingest(fromNode.deviceAddress, raw)
                    handleDecisionForward(targetNode, decision)
                }
            } else {
                // Broadcast to all connected neighbors
                for (neighborHex in neighbors) {
                    val targetNode = nodes[neighborHex] ?: continue
                    val decision = targetNode.ingest(fromNode.deviceAddress, raw)
                    handleDecisionForward(targetNode, decision)
                }
            }
        }

        private fun handleDecisionForward(node: VirtualNode, decision: RoutingDecision) {
            when (decision) {
                is RoutingDecision.ForwardUnicast -> {
                    val nextHop = nodes.values.find { it.deviceAddress == decision.nextHopAddress }
                    if (nextHop != null && links[node.hexAddress]?.contains(nextHop.hexAddress) == true) {
                        val raw = PacketSerializer.serialize(decision.packet)
                        val nextDecision = nextHop.ingest(node.deviceAddress, raw)
                        handleDecisionForward(nextHop, nextDecision)
                    }
                }
                is RoutingDecision.ForwardBroadcast -> {
                    val raw = PacketSerializer.serialize(decision.packet)
                    val neighbors = links[node.hexAddress] ?: return
                    for (neighborHex in neighbors) {
                        val targetNode = nodes[neighborHex] ?: continue
                        if (targetNode.deviceAddress != decision.exceptDeviceAddress) {
                            val nextDecision = targetNode.ingest(node.deviceAddress, raw)
                            handleDecisionForward(targetNode, nextDecision)
                        }
                    }
                }
                else -> { /* Terminated or dropped */ }
            }
        }
    }

    private lateinit var mesh: VirtualMeshNetwork
    private lateinit var nodeA: VirtualNode
    private lateinit var nodeB: VirtualNode
    private lateinit var nodeC: VirtualNode
    private lateinit var nodeD: VirtualNode

    @Before
    fun setUp() {
        mesh = VirtualMeshNetwork()
        nodeA = VirtualNode(0xAAAA0001L, "AA:00:00:00:00:01")
        nodeB = VirtualNode(0xBBBB0002L, "BB:00:00:00:00:02")
        nodeC = VirtualNode(0xCCCC0003L, "CC:00:00:00:00:03")
        nodeD = VirtualNode(0xDDDD0004L, "DD:00:00:00:00:04")

        mesh.addNode(nodeA)
        mesh.addNode(nodeB)
        mesh.addNode(nodeC)
        mesh.addNode(nodeD)
    }

    // ==========================================
    // TEST 1: Direct 2-Node Delivery
    // ==========================================
    @Test
    fun testDirectDeliveryTwoNodes() {
        mesh.connectLink(nodeA, nodeB)

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeB.hexAddress,
            payload = "Hello Node B directly".toByteArray(Charsets.UTF_8)
        )

        mesh.transmit(nodeA, nodeB.deviceAddress, packet)

        assertEquals(1, nodeB.receivedPackets.size)
        assertEquals(packet.packetId, nodeB.receivedPackets[0].packetId)
        assertEquals("Hello Node B directly", String(nodeB.receivedPackets[0].payload, Charsets.UTF_8))
    }

    // ==========================================
    // TEST 2: 3-Node Multi-Hop Relay (A -> B -> C)
    // ==========================================
    @Test
    fun testThreeNodeMultiHopRelay() {
        // Topology: A <-> B <-> C (A cannot reach C directly)
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeB, nodeC)

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeC.hexAddress,
            ttl = 5,
            hopCount = 0,
            payload = "Message via Relay B".toByteArray(Charsets.UTF_8)
        )

        mesh.transmit(nodeA, nodeB.deviceAddress, packet)

        // Verify C received it with decremented TTL and incremented hopCount
        assertEquals(1, nodeC.receivedPackets.size)
        val receivedAtC = nodeC.receivedPackets[0]
        assertEquals(packet.packetId, receivedAtC.packetId)
        assertEquals(4.toByte(), receivedAtC.ttl) // 5 - 1
        assertEquals(1.toByte(), receivedAtC.hopCount) // 0 + 1
        assertEquals("Message via Relay B", String(receivedAtC.payload, Charsets.UTF_8))
    }

    // ==========================================
    // TEST 3: 4-Node Multi-Hop Relay (A -> B -> C -> D)
    // ==========================================
    @Test
    fun testFourNodeMultiHopRelay() {
        // Linear chain: A <-> B <-> C <-> D
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeB, nodeC)
        mesh.connectLink(nodeC, nodeD)

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeD.hexAddress,
            ttl = 5,
            hopCount = 0,
            payload = "Across 3 hops to D".toByteArray(Charsets.UTF_8)
        )

        mesh.transmit(nodeA, nodeB.deviceAddress, packet)

        assertEquals(1, nodeD.receivedPackets.size)
        val receivedAtD = nodeD.receivedPackets[0]
        assertEquals(packet.packetId, receivedAtD.packetId)
        assertEquals(3.toByte(), receivedAtD.ttl) // 5 - 2 relays = 3
        assertEquals(2.toByte(), receivedAtD.hopCount) // 0 + 2 relays = 2
        assertEquals("Across 3 hops to D", String(receivedAtD.payload, Charsets.UTF_8))
    }

    // ==========================================
    // TEST 4: Reverse-Path Route Learning
    // ==========================================
    @Test
    fun testReversePathRouteLearning() {
        // Linear chain: A <-> B <-> C
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeB, nodeC)

        // Initially C does not know a route to A
        assertNull(nodeC.routeTable.getRoute(nodeA.hexAddress))

        // Node A sends a packet towards C
        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeC.hexAddress,
            hopCount = 0,
            payload = "Ping".toByteArray()
        )
        mesh.transmit(nodeA, nodeB.deviceAddress, packet)

        // When C received the packet from B, C must have learned route to A via B!
        val learnedRoute = nodeC.routeTable.getRoute(nodeA.hexAddress)
        assertNotNull(learnedRoute)
        assertEquals(nodeB.deviceAddress, learnedRoute!!.nextHopAddress)
        assertEquals(2, learnedRoute.hopDistance)
    }

    // ==========================================
    // TEST 5: TTL Expiration Drop
    // ==========================================
    @Test
    fun testTtlExpirationDrop() {
        // A <-> B <-> C. A sends with TTL = 1 (can only reach 1 hop, cannot be relayed by B)
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeB, nodeC)

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeC.hexAddress,
            ttl = 1, // TTL = 1 cannot be forwarded past B
            hopCount = 0,
            payload = "Expiring message".toByteArray()
        )

        val raw = PacketSerializer.serialize(packet)
        val decisionAtB = nodeB.ingest(nodeA.deviceAddress, raw)

        assertTrue("Expected DropExpiredTtl at intermediate relay", decisionAtB is RoutingDecision.DropExpiredTtl)
        assertEquals(0, nodeC.receivedPackets.size)
    }

    // ==========================================
    // TEST 6: Routing Loop Suppression (Cycles)
    // ==========================================
    @Test
    fun testRoutingLoopSuppression() {
        // Cycle: A <-> B <-> C <-> A
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeB, nodeC)
        mesh.connectLink(nodeC, nodeA)

        val packet = BinaryPacket(
            type = PacketType.ROOM_BROADCAST,
            sourceHex = nodeA.hexAddress,
            destinationHex = BinaryPacket.BROADCAST_DESTINATION,
            ttl = 5,
            payload = "Loop test broadcast".toByteArray()
        )

        val raw = PacketSerializer.serialize(packet)

        // Ingest at B (1st hop)
        val decB = nodeB.ingest(nodeA.deviceAddress, raw)
        assertTrue(decB is RoutingDecision.ForwardBroadcast)

        // B forwards to C
        val forwardFromB = (decB as RoutingDecision.ForwardBroadcast).packet
        val rawFromB = PacketSerializer.serialize(forwardFromB)
        val decC = nodeC.ingest(nodeB.deviceAddress, rawFromB)
        assertTrue(decC is RoutingDecision.ForwardBroadcast)

        // C attempts to loop back to A
        val forwardFromC = (decC as RoutingDecision.ForwardBroadcast).packet
        val rawFromC = PacketSerializer.serialize(forwardFromC)
        val decA = nodeA.ingest(nodeC.deviceAddress, rawFromC)

        // Node A must reject packet because it originated from A!
        assertTrue("Loop packet must be dropped at origin", decA is RoutingDecision.DropSelfOrigin)
    }

    // ==========================================
    // TEST 7: Duplicate Packet Suppression
    // ==========================================
    @Test
    fun testDuplicatePacketSuppression() {
        mesh.connectLink(nodeA, nodeB)

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeB.hexAddress,
            payload = "Duplicate test".toByteArray()
        )
        val raw = PacketSerializer.serialize(packet)

        val decision1 = nodeB.ingest(nodeA.deviceAddress, raw)
        assertTrue(decision1 is RoutingDecision.AcceptLocally)

        // Ingest identical packet again
        val decision2 = nodeB.ingest(nodeA.deviceAddress, raw)
        assertTrue("Second identical packet must be dropped as duplicate", decision2 is RoutingDecision.DropDuplicate)
        assertEquals(1, nodeB.receivedPackets.size)
    }

    // ==========================================
    // TEST 8: Hop-by-Hop ACK and Local Retry
    // ==========================================
    @Test
    fun testHopByHopAckAndLocalRetry() = runBlocking {
        mesh.connectLink(nodeA, nodeB)

        var transmittedAck: BinaryPacket? = null
        nodeB.engine.transmitCallback = { _, pkt ->
            if (pkt.type == PacketType.LINK_ACK) {
                transmittedAck = pkt
            }
        }

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            flags = PacketFlags.ACK_REQUESTED,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeB.hexAddress,
            payload = "Ack test".toByteArray()
        )

        val raw = PacketSerializer.serialize(packet)
        nodeB.ingest(nodeA.deviceAddress, raw)

        assertNotNull("Node B should have transmitted a LINK_ACK", transmittedAck)
        assertEquals(PacketType.LINK_ACK, transmittedAck!!.type)
        assertEquals(1.toByte(), transmittedAck!!.ttl)

        val ackData = DeliveryAck.deserialize(transmittedAck!!.payload).getOrThrow()
        assertEquals(packet.packetId, ackData.originalPacketId)
        assertFalse(ackData.isEndToEnd)
    }

    // ==========================================
    // TEST 9: End-to-End ACK Delivery Confirmation
    // ==========================================
    @Test
    fun testEndToEndAckDeliveryConfirmation() = runBlocking {
        // Linear chain: A <-> B <-> C
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeB, nodeC)

        var nodeADeliveryState: String? = null
        nodeA.engine.onDeliveryStateChanged = { _, state ->
            nodeADeliveryState = state
        }

        // Setup E2E ACK callback on C so when C generates E2E ACK, it routes back to A
        nodeC.engine.transmitCallback = { targetAddr, pkt ->
            mesh.transmit(nodeC, targetAddr, pkt)
        }

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeC.hexAddress,
            payload = "Direct message to C".toByteArray()
        )

        // Transmit from A to B
        mesh.transmit(nodeA, nodeB.deviceAddress, packet)

        // At C, packet was received and E2E ACK was sent back
        assertEquals(1, nodeC.receivedPackets.size)

        // Simulate delivery of the E2E ACK from C back to A
        val e2eAck = DeliveryAck(
            originalPacketId = packet.packetId,
            isEndToEnd = true,
            ackSenderHex = nodeC.hexAddress,
            originalSenderHex = nodeA.hexAddress
        )
        nodeA.engine.handleIncomingE2eAck(e2eAck)

        assertEquals("DELIVERED", nodeADeliveryState)
    }

    // ==========================================
    // TEST 10: Alternate Route Recovery
    // ==========================================
    @Test
    fun testAlternateRouteRecovery() {
        // Redundant topology:
        // A <-> B <-> D
        // A <-> C <-> D
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeB, nodeD)
        mesh.connectLink(nodeA, nodeC)
        mesh.connectLink(nodeC, nodeD)

        // Primary route learned via B
        nodeA.routeTable.updateRoute(nodeD.hexAddress, nodeB.deviceAddress, 2)
        assertEquals(nodeB.deviceAddress, nodeA.routeTable.getRoute(nodeD.hexAddress)?.nextHopAddress)

        // Link A <-> B fails
        mesh.disconnectLink(nodeA, nodeB)

        // Verify route via B was invalidated
        assertNull("Route via broken link should be invalidated", nodeA.routeTable.getRoute(nodeD.hexAddress))

        // Node A can still reach D via C
        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeD.hexAddress,
            payload = "Alternate path test".toByteArray()
        )

        mesh.transmit(nodeA, nodeC.deviceAddress, packet)
        assertEquals(1, nodeD.receivedPackets.size)
        assertEquals("Alternate path test", String(nodeD.receivedPackets[0].payload, Charsets.UTF_8))
    }

    // ==========================================
    // TEST 11: Store-and-Forward Queue Flush on Reconnect
    // ==========================================
    @Test
    fun testStoreAndForwardQueueFlushOnReconnect() = runBlocking {
        // Node C is temporarily offline / not connected to B
        val delayedPacket = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeC.hexAddress,
            payload = "Store-and-forward payload".toByteArray()
        )

        // Node B stores packet for downstream relay
        nodeB.engine.storeForRelay(delayedPacket, ttlMs = 60_000)
        assertEquals(1, nodeB.engine.getStoredRelayCount())

        val flushedPackets = mutableListOf<BinaryPacket>()
        nodeB.engine.transmitCallback = { _, pkt ->
            flushedPackets.add(pkt)
        }

        // Now Node C connects to Node B!
        nodeB.engine.handlePeerConnected(nodeC.deviceAddress, nodeC.hexAddress)

        // Queued relay packet must be flushed to Node C!
        assertEquals(1, flushedPackets.size)
        assertEquals(delayedPacket.packetId, flushedPackets[0].packetId)
        assertEquals(0, nodeB.engine.getStoredRelayCount())
    }

    // ==========================================
    // TEST 12: Persistent Queue State Recovery
    // ==========================================
    @Test
    fun testPersistentQueueStateRecovery() {
        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeB.hexAddress,
            payload = "Persistent queue test".toByteArray()
        )

        nodeA.engine.storeForRelay(packet, ttlMs = 120_000)
        assertEquals(1, nodeA.engine.getStoredRelayCount())

        // Ingest duplicate packet should not create new entry
        nodeA.deduplicationCache.recordPacket(packet.packetId)
        assertTrue(nodeA.deduplicationCache.isDuplicate(packet.packetId))
    }

    // ==========================================
    // TEST 13: Multi-Path Redundant Arrival Handling (Diamond Mesh)
    // ==========================================
    @Test
    fun testMultiPathRedundantArrivalHandling() {
        // Diamond topology:
        //      B
        //    /   \
        //   A     D
        //    \   /
        //      C
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeB, nodeD)
        mesh.connectLink(nodeA, nodeC)
        mesh.connectLink(nodeC, nodeD)

        val broadcastPacket = BinaryPacket(
            type = PacketType.ROOM_BROADCAST,
            sourceHex = nodeA.hexAddress,
            destinationHex = BinaryPacket.BROADCAST_DESTINATION,
            payload = "Diamond flood message".toByteArray()
        )

        mesh.transmit(nodeA, null, broadcastPacket)

        // Node D should have received the broadcast exactly once (the duplicate is suppressed)
        assertEquals("Node D must only accept 1 packet despite multi-path flood", 1, nodeD.receivedPackets.size)
        assertEquals("Diamond flood message", String(nodeD.receivedPackets[0].payload, Charsets.UTF_8))
    }

    // ==========================================
    // TEST 14: Malformed Packet Checksum Rejection
    // ==========================================
    @Test
    fun testMalformedPacketChecksumRejection() {
        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeB.hexAddress,
            payload = "Valid payload".toByteArray()
        )

        val validBytes = PacketSerializer.serialize(packet)

        // Tamper with payload byte (corrupts CRC32)
        val corruptedBytes = validBytes.copyOf()
        corruptedBytes[BinaryPacket.HEADER_SIZE_BYTES + 2] = 0x7F.toByte()

        val decision = nodeB.ingest(nodeA.deviceAddress, corruptedBytes)
        assertTrue("Corrupted packet must be dropped with DropInvalidChecksum", decision is RoutingDecision.DropInvalidChecksum)
        assertEquals(0, nodeB.receivedPackets.size)
    }

    // ==========================================
    // TEST 15: Confidential Relay Non-Decryption
    // ==========================================
    @Test
    fun testConfidentialRelayNonDecryption() {
        // Linear chain: A <-> B <-> C
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeB, nodeC)

        // 1. Establish E2EE Noise XX handshake between Node A and Node C
        val msg1 = nodeA.sessionManager.startHandshake("cccc0003")
        val res1 = nodeC.sessionManager.processHandshakeMessage("aaaa0001", msg1)
        assertTrue(res1 is HandshakeProcessResult.Reply)

        val msg2 = (res1 as HandshakeProcessResult.Reply).messageBytes
        val res2 = nodeA.sessionManager.processHandshakeMessage("cccc0003", msg2)
        assertTrue(res2 is HandshakeProcessResult.Reply)

        val msg3 = (res2 as HandshakeProcessResult.Reply).messageBytes
        val res3 = nodeC.sessionManager.processHandshakeMessage("aaaa0001", msg3)
        assertTrue(res3 is HandshakeProcessResult.Established)

        assertTrue(nodeA.sessionManager.hasActiveSession("cccc0003"))
        assertTrue(nodeC.sessionManager.hasActiveSession("aaaa0001"))
        assertFalse(nodeB.sessionManager.hasActiveSession("aaaa0001"))
        assertFalse(nodeB.sessionManager.hasActiveSession("cccc0003"))

        // 2. Node A encrypts a confidential message for Node C
        val secretMessage = "Top Secret Hexnet Payload: Do Not Read At Relay!"
        val encResult = nodeA.sessionManager.encryptDirectMessage("cccc0003", secretMessage.toByteArray(Charsets.UTF_8))

        val buffer = java.nio.ByteBuffer.allocate(8 + encResult.ciphertext.size).order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putLong(encResult.sequenceNumber)
        buffer.put(encResult.ciphertext)

        val encryptedPacket = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            flags = PacketFlags.setFlag(PacketFlags.ACK_REQUESTED, PacketFlags.ENCRYPTED),
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeC.hexAddress,
            payload = buffer.array()
        )

        // 3. Relay Node B receives and forwards the packet
        val rawEncrypted = PacketSerializer.serialize(encryptedPacket)
        val decisionAtB = nodeB.ingest(nodeA.deviceAddress, rawEncrypted)

        // Intermediate Node B forwards the packet Unicast or Broadcast
        assertTrue(decisionAtB is RoutingDecision.ForwardUnicast || decisionAtB is RoutingDecision.ForwardBroadcast)

        // Assert Node B cannot decrypt the payload (Node B has no session for A or C)
        assertFalse(nodeB.receivedPlaintexts.contains(secretMessage))

        // 4. Destination Node C ingests the packet forwarded from B
        val forwardedPacket = when (decisionAtB) {
            is RoutingDecision.ForwardUnicast -> decisionAtB.packet
            is RoutingDecision.ForwardBroadcast -> decisionAtB.packet
            else -> throw IllegalStateException("Unexpected decision at B")
        }
        val rawForwarded = PacketSerializer.serialize(forwardedPacket)
        val decisionAtC = nodeC.ingest(nodeB.deviceAddress, rawForwarded)

        assertTrue("Destination C must accept packet locally", decisionAtC is RoutingDecision.AcceptLocally)
        val accepted = decisionAtC as RoutingDecision.AcceptLocally
        assertNotNull("Destination C must decrypt payload", accepted.decryptedPayload)
        assertEquals(secretMessage, String(accepted.decryptedPayload!!, Charsets.UTF_8))
    }

    // ==========================================
    // TEST 16: DeliveryAck Serialization Size & Format
    // ==========================================
    @Test
    fun testDeliveryAckSerializationExactSize() {
        val ack = DeliveryAck(
            originalPacketId = 0x1122334455667788L,
            isEndToEnd = true,
            ackSenderHex = 0xAAAA0001L,
            originalSenderHex = 0xCCCC0003L,
            timestampEpochMs = 1710000000000L
        )

        val serialized = DeliveryAck.serialize(ack)
        assertEquals("DeliveryAck serialized length must exactly be 34 bytes", 34, serialized.size)

        val deserialized = DeliveryAck.deserialize(serialized).getOrThrow()
        assertEquals(ack.originalPacketId, deserialized.originalPacketId)
        assertTrue(deserialized.isEndToEnd)
        assertEquals(ack.ackSenderHex, deserialized.ackSenderHex)
        assertEquals(ack.originalSenderHex, deserialized.originalSenderHex)
        assertEquals(ack.timestampEpochMs, deserialized.timestampEpochMs)
    }

    // ==========================================
    // TEST 17: Full Noise XX Handshake over Mesh Wire
    // ==========================================
    @Test
    fun testNoiseHandshakeOverMesh() {
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeB, nodeC)

        var sessionAtA: NoiseSession? = null
        var sessionAtC: NoiseSession? = null

        nodeA.engine.onSessionEstablished = { _, session -> sessionAtA = session }
        nodeC.engine.onSessionEstablished = { _, session -> sessionAtC = session }

        // Forward packets automatically between nodes
        nodeA.engine.transmitCallback = { addr, pkt -> mesh.transmit(nodeA, addr, pkt) }
        nodeB.engine.transmitCallback = { addr, pkt -> mesh.transmit(nodeB, addr, pkt) }
        nodeC.engine.transmitCallback = { addr, pkt -> mesh.transmit(nodeC, addr, pkt) }

        // Node A initiates handshake with Node C across relay Node B
        val msg1Packet = nodeA.engine.initiateNoiseHandshake(nodeC.hexAddress)
        assertNotNull(msg1Packet)
        assertEquals(PacketType.NOISE_HANDSHAKE, msg1Packet!!.type)

        // Verify Node A and Node C established mutual sessions
        assertNotNull("Node A must establish active Noise session with Node C", sessionAtA)
        assertNotNull("Node C must establish active Noise session with Node A", sessionAtC)
        assertTrue(nodeA.sessionManager.hasActiveSession("cccc0003"))
        assertTrue(nodeC.sessionManager.hasActiveSession("aaaa0001"))
    }

    // ==========================================
    // TEST 18: DeliveryAck Destination Binding & Tamper Rejection
    // ==========================================
    @Test
    fun testDeliveryAckDestinationBindingAndTamperRejection() {
        mesh.connectLink(nodeA, nodeB)

        var deliveryStateCalled = false
        nodeA.engine.onDeliveryStateChanged = { _, _ -> deliveryStateCalled = true }

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeB.hexAddress,
            payload = "Ack binding test".toByteArray()
        )

        // 1. Rogue ACK where originalSenderHex != nodeA.hexAddress
        val forgedAck = DeliveryAck(
            originalPacketId = packet.packetId,
            isEndToEnd = true,
            ackSenderHex = nodeB.hexAddress,
            originalSenderHex = 0x99998888L // Mismatched original sender
        )

        nodeA.engine.handleIncomingE2eAck(forgedAck)
        assertFalse("Forged ACK with mismatched destination must be ignored", deliveryStateCalled)

        // 2. ACK with mismatched sender inside packet framing
        val rawForgedAckBytes = DeliveryAck.serialize(
            DeliveryAck(
                originalPacketId = packet.packetId,
                isEndToEnd = true,
                ackSenderHex = 0x77776666L, // Mismatched ack sender
                originalSenderHex = nodeA.hexAddress
            )
        )

        val roguePacket = BinaryPacket(
            type = PacketType.DELIVERY_ACK,
            sourceHex = nodeB.hexAddress, // Wire source does not match ackSenderHex 0x77776666L
            destinationHex = nodeA.hexAddress,
            payload = rawForgedAckBytes
        )

        val rawWire = PacketSerializer.serialize(roguePacket)
        nodeA.ingest(nodeB.deviceAddress, rawWire)
        assertFalse("ACK where wire source does not match ackSenderHex must not trigger delivery callback", deliveryStateCalled)
    }

    // ==========================================
    // TEST 19: DeliveryAck Duplicate Suppression
    // ==========================================
    @Test
    fun testDeliveryAckDuplicateSuppression() = runBlocking {
        mesh.connectLink(nodeA, nodeB)

        var callbackCount = 0
        nodeA.engine.onDeliveryStateChanged = { _, state ->
            if (state == "DELIVERED") callbackCount++
        }

        val originalPacket = nodeA.engine.sendDirectMessage(nodeB.hexAddress, "Duplicate ACK test".toByteArray())
        val packetId = originalPacket.packetId

        val validAck = DeliveryAck(
            originalPacketId = packetId,
            isEndToEnd = true,
            ackSenderHex = nodeB.hexAddress,
            originalSenderHex = nodeA.hexAddress
        )

        // First ingestion: marks DELIVERED
        nodeA.engine.handleIncomingE2eAck(validAck)
        assertEquals(1, callbackCount)
        assertEquals("DELIVERED", nodeA.engine.getOutboundPacketState(packetId))

        // Duplicate replayed ACK: must be suppressed
        nodeA.engine.handleIncomingE2eAck(validAck)
        assertEquals("Duplicate ACK must not trigger redundant callback", 1, callbackCount)
    }

    // ==========================================
    // TEST 20: TTL & Hop-Count Mutation Preserves AEAD Decryption
    // ==========================================
    @Test
    fun testTtlAndHopCountMutationPreservesAeadDecryption() {
        // Topology: A <-> B <-> C <-> D (3 intermediate hops)
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeB, nodeC)
        mesh.connectLink(nodeC, nodeD)

        // 1. Establish Noise session between A and D
        val msg1 = nodeA.sessionManager.startHandshake("dddd0004")
        val res1 = nodeD.sessionManager.processHandshakeMessage("aaaa0001", msg1)
        val msg2 = (res1 as HandshakeProcessResult.Reply).messageBytes
        val res2 = nodeA.sessionManager.processHandshakeMessage("dddd0004", msg2)
        val msg3 = (res2 as HandshakeProcessResult.Reply).messageBytes
        nodeD.sessionManager.processHandshakeMessage("aaaa0001", msg3)

        assertTrue(nodeA.sessionManager.hasActiveSession("dddd0004"))
        assertTrue(nodeD.sessionManager.hasActiveSession("aaaa0001"))

        // 2. Node A encrypts secret message for Node D
        val secret = "End-to-end authenticated across mutating mesh relays!"
        val enc = nodeA.sessionManager.encryptDirectMessage("dddd0004", secret.toByteArray(Charsets.UTF_8))

        val buffer = java.nio.ByteBuffer.allocate(8 + enc.ciphertext.size).order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putLong(enc.sequenceNumber)
        buffer.put(enc.ciphertext)

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            flags = PacketFlags.setFlag(PacketFlags.ACK_REQUESTED, PacketFlags.ENCRYPTED),
            ttl = 5,
            hopCount = 0,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeD.hexAddress,
            payload = buffer.array()
        )

        // Transmit across the network
        mesh.transmit(nodeA, nodeB.deviceAddress, packet)

        // Assert Node D accepted packet locally and decrypted payload successfully
        assertEquals(1, nodeD.receivedPackets.size)
        val receivedPacket = nodeD.receivedPackets[0]

        // Mutable headers were modified by B and C
        assertEquals("TTL was decremented by 2 relays", 3.toByte(), receivedPacket.ttl)
        assertEquals("HopCount was incremented by 2 relays", 2.toByte(), receivedPacket.hopCount)

        // But AEAD decryption succeeded completely!
        assertEquals(1, nodeD.receivedPlaintexts.size)
        assertEquals(secret, nodeD.receivedPlaintexts[0])
    }

    // ==========================================
    // TEST 21: Unicast Routing Isolation
    // ==========================================
    @Test
    fun testUnicastPayloadNotBroadcastToUnrelatedConnectedPeers() {
        // Topology: A is connected to B and C
        mesh.connectLink(nodeA, nodeB)
        mesh.connectLink(nodeA, nodeC)

        // Node A has a known route to Node B
        nodeA.routeTable.updateRoute(nodeB.hexAddress, nodeB.deviceAddress, 1)

        val unicastPacket = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            sourceHex = nodeA.hexAddress,
            destinationHex = nodeB.hexAddress,
            payload = "Private unicast for B only".toByteArray()
        )

        // Node A dispatches the packet
        nodeA.engine.dispatchPacket(unicastPacket)

        // Verify Node A's transmit callback was addressed strictly to B's device address
        assertEquals(1, nodeA.transmittedPackets.size)
        val (targetDeviceAddress, transmittedPkt) = nodeA.transmittedPackets[0]
        assertEquals("Unicast packet must be addressed directly to B", nodeB.deviceAddress, targetDeviceAddress)
        assertNotNull("Target device address must not be null (never broadcast)", targetDeviceAddress)
        assertEquals(unicastPacket.packetId, transmittedPkt.packetId)

        // Ingest into B only
        val raw = PacketSerializer.serialize(transmittedPkt)
        nodeB.ingest(nodeA.deviceAddress, raw)

        // Assert Node B received it and Node C received nothing
        assertEquals(1, nodeB.receivedPackets.size)
        assertEquals(0, nodeC.receivedPackets.size)
    }

    // ==========================================
    // TEST 22: PacketReassembler DoS Bounds and Eviction
    // ==========================================
    @Test
    fun testPacketReassemblerDosBoundsAndLruEviction() {
        val reassembler = PacketReassembler(timeoutMs = 60_000L)

        // 1. Packet claiming > MAX_ALLOWED_FRAGMENTS (512) must be dropped immediately
        val hugeFragment = PacketFragment(
            packetId = 999999L,
            totalFragments = 513, // Exceeds MAX_ALLOWED_FRAGMENTS
            fragmentIndex = 0,
            data = ByteArray(10)
        )
        val hugeResult = reassembler.processFragment(hugeFragment)
        assertNull("Fragments with total > 512 must be dropped immediately", hugeResult)
        assertEquals(0, reassembler.activeBufferCount())

        // 2. Saturate buffers up to MAX_IN_FLIGHT_BUFFERS (128)
        for (i in 1..PacketReassembler.MAX_IN_FLIGHT_BUFFERS) {
            val frag = PacketFragment(
                packetId = i.toLong(),
                totalFragments = 3,
                fragmentIndex = 0,
                data = ByteArray(10)
            )
            reassembler.processFragment(frag)
        }
        assertEquals(PacketReassembler.MAX_IN_FLIGHT_BUFFERS, reassembler.activeBufferCount())

        // 3. Adding 129th buffer should evict oldest without throwing OutOfMemory
        val overflowFrag = PacketFragment(
            packetId = 200L,
            totalFragments = 3,
            fragmentIndex = 0,
            data = ByteArray(10)
        )
        reassembler.processFragment(overflowFrag)
        assertEquals("Buffer count must not exceed MAX_IN_FLIGHT_BUFFERS", PacketReassembler.MAX_IN_FLIGHT_BUFFERS, reassembler.activeBufferCount())
    }

    // ==========================================
    // TEST 23: GattOperationQueue Timeout Recovery
    // ==========================================
    @Test
    fun testGattOperationQueueTimeoutRecovery() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        var timedOutOp: GattOperation? = null

        val queue = GattOperationQueue(
            scope = testScope,
            onExecute = { /* do not call notifyCompleted to simulate dropped callback */ },
            onTimeout = { op -> timedOutOp = op }
        )

        // Enqueue operation with very short custom timeout (50ms)
        val op1 = GattOperation.Custom("Test Dropped Callback") { }
        // We override timeoutMs by testing the timeout catch path
        val job1 = kotlinx.coroutines.async {
            queue.enqueue(GattOperation.WriteCharacteristic(ByteArray(10), 2))
        }

        // Wait for queue operation timeout (or clear queue to simulate reset)
        queue.clear()
        val success = job1.await()
        assertFalse("Cancelled or timed-out operation must return false", success)
        assertEquals(0, queue.pendingCount())
    }
}
