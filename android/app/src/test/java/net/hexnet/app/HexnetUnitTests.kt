package net.hexnet.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.hexnet.app.core.model.*
import net.hexnet.app.data.repository.ContactsRepository
import net.hexnet.app.data.repository.MessagingRepository
import net.hexnet.app.domain.MeshRoutingEngine
import net.hexnet.app.domain.ValidateUsernameUseCase
import net.hexnet.app.domain.ValidationResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ValidateUsernameUseCaseTest {
    private lateinit var useCase: ValidateUsernameUseCase

    @Before
    fun setUp() {
        useCase = ValidateUsernameUseCase()
    }

    @Test
    fun testValidUsernames() {
        assertTrue(useCase.execute("alice") is ValidationResult.Valid)
        assertTrue(useCase.execute("node_42") is ValidationResult.Valid)
        assertTrue(useCase.execute("hex_mesh_99") is ValidationResult.Valid)
    }

    @Test
    fun testTooShortUsernameFails() {
        val result = useCase.execute("ab")
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Username must be at least 3 characters long", (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun testTooLongUsernameFails() {
        val result = useCase.execute("a".repeat(21))
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Username must not exceed 20 characters", (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun testInvalidCharactersFail() {
        val result = useCase.execute("Alice!")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun testLeadingTrailingUnderscoreFails() {
        assertTrue(useCase.execute("_node") is ValidationResult.Invalid)
        assertTrue(useCase.execute("node_") is ValidationResult.Invalid)
    }

    @Test
    fun testReservedWordsFail() {
        assertTrue(useCase.execute("admin") is ValidationResult.Invalid)
        assertTrue(useCase.execute("hexnet") is ValidationResult.Invalid)
        assertTrue(useCase.execute("broadcast") is ValidationResult.Invalid)
    }
}

class MeshRoutingEngineTest {
    private lateinit var routingEngine: MeshRoutingEngine
    private val localAddress = "0xLOCAL_NODE_01"

    @Before
    fun setUp() {
        routingEngine = MeshRoutingEngine(defaultMaxHops = 5)
    }

    @Test
    fun testLocalDestinationAccepted() {
        val packet = MeshPacket(
            packetId = "p1",
            sourceHex = "0xSENDER",
            destinationHex = localAddress,
            payloadType = "TEXT",
            payload = "Hello",
            hopCount = 1,
            ttl = 5,
            packetHash = "hash_001"
        )
        val decision = routingEngine.processPacket(packet, localAddress)
        assertTrue(decision is MeshRoutingEngine.RoutingDecision.AcceptLocal)
    }

    @Test
    fun testBroadcastDestinationAccepted() {
        val packet = MeshPacket(
            packetId = "p2",
            sourceHex = "0xSENDER",
            destinationHex = "0xBROADCAST",
            payloadType = "ROOM_BROADCAST",
            payload = "Announcement",
            hopCount = 1,
            ttl = 5,
            packetHash = "hash_002"
        )
        val decision = routingEngine.processPacket(packet, localAddress)
        assertTrue(decision is MeshRoutingEngine.RoutingDecision.AcceptLocal)
    }

    @Test
    fun testDuplicatePacketSuppression() {
        val packet = MeshPacket(
            packetId = "p3",
            sourceHex = "0xSENDER",
            destinationHex = "0xOTHER",
            payloadType = "TEXT",
            payload = "Data",
            hopCount = 1,
            ttl = 5,
            packetHash = "duplicate_hash_123"
        )

        val firstDecision = routingEngine.processPacket(packet, localAddress)
        assertTrue(firstDecision is MeshRoutingEngine.RoutingDecision.Forward)

        // Same packet received again via alternate route -> Must be suppressed
        val secondDecision = routingEngine.processPacket(packet, localAddress)
        assertTrue(secondDecision is MeshRoutingEngine.RoutingDecision.DropDuplicate)
    }

    @Test
    fun testHopLimitExceededDropped() {
        val packet = MeshPacket(
            packetId = "p4",
            sourceHex = "0xSENDER",
            destinationHex = "0xDEST",
            payloadType = "TEXT",
            payload = "Far away",
            hopCount = 5, // At max hops
            ttl = 5,
            packetHash = "hash_exceeded"
        )
        val decision = routingEngine.processPacket(packet, localAddress)
        assertTrue(decision is MeshRoutingEngine.RoutingDecision.DropHopLimitExceeded)
    }
}

class MessagingRepositoryTest {
    @Test
    fun testSendMessageAddsToQueueAndUpdatesConversation() = runBlocking {
        val repo = MessagingRepository(coroutineScope = this)
        val initialMessages = repo.getMessages("conv_solaris").first()
        val initialCount = initialMessages.size

        val newMsg = repo.sendMessage(
            conversationId = "conv_solaris",
            senderHex = "0xTEST",
            senderUsername = "test_user",
            content = "Testing offline queue dispatch"
        )
        kotlinx.coroutines.delay(50)

        assertEquals(MessageDeliveryState.QUEUED, newMsg.state)
        val updatedMessages = repo.getMessages("conv_solaris").first()
        assertEquals(initialCount + 1, updatedMessages.size)

        // Status transition test
        repo.updateMessageState(newMsg.id, MessageDeliveryState.DELIVERED)
        kotlinx.coroutines.delay(50)
        val deliveredList = repo.getMessages("conv_solaris").first()
        val verifiedMsg = deliveredList.find { it.id == newMsg.id }
        assertEquals(MessageDeliveryState.DELIVERED, verifiedMsg?.state)
    }
}

class ContactsRepositoryTest {
    @Test
    fun testToggleContactAndVerify() = runBlocking {
        val repo = ContactsRepository(coroutineScope = this)
        val testPeer = Peer(
            peerId = "peer_solaris",
            hexAddress = "0x7F10A4B29C44E100",
            username = "solaris_node",
            displayName = "Solaris Prime",
            rssiDbm = -65,
            hopDistance = 1,
            isDirectNeighbor = true,
            lastSeenEpochMs = System.currentTimeMillis(),
            status = PeerStatus.ACTIVE_DIRECT,
            isContact = false
        )
        repo.upsertPeer(testPeer)

        val peers = repo.peers.first()
        val targetPeer = peers.first()

        repo.toggleContact(targetPeer.peerId)
        val updatedPeers = repo.peers.first()
        val updated = updatedPeers.find { it.peerId == targetPeer.peerId }
        assertNotEquals(targetPeer.isContact, updated?.isContact)

        repo.verifyIdentity(targetPeer.peerId)
        val verifiedPeers = repo.peers.first()
        val verified = verifiedPeers.find { it.peerId == targetPeer.peerId }
        assertTrue(verified?.isIdentityVerified == true)
    }
}
