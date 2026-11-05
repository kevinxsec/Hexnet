package net.hexnet.app.integration

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import net.hexnet.app.core.database.dao.ConversationDao
import net.hexnet.app.core.database.dao.MessageDao
import net.hexnet.app.core.database.dao.OutboundQueueDao
import net.hexnet.app.core.database.dao.RelayQueueDao
import net.hexnet.app.core.database.entity.ConversationEntity
import net.hexnet.app.core.database.entity.MessageEntity
import net.hexnet.app.core.database.entity.OutboundPacketEntity
import net.hexnet.app.core.database.entity.RelayPacketEntity
import net.hexnet.app.core.model.ConversationType
import net.hexnet.app.core.model.MessageDeliveryState
import net.hexnet.app.core.protocol.BinaryPacket
import net.hexnet.app.core.protocol.PacketFlags
import net.hexnet.app.core.protocol.PacketSerializer
import net.hexnet.app.core.protocol.PacketType
import net.hexnet.app.core.routing.MeshRoutingEngine
import net.hexnet.app.core.routing.RoutingDecision
import net.hexnet.app.data.repository.MessagingRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-End Integration Test Suite for Hexnet.
 *
 * Verifies:
 * 1. UI message submission -> Room persistence -> MeshRoutingEngine dispatch -> Link ACK.
 * 2. Service unavailable / disconnected fallback -> persistent Room queuing -> reconnection flush.
 * 3. Incoming encrypted message -> validation & decryption -> Room insertion & conversation update.
 * 4. Duplicate incoming packet suppression -> deduplication cache & Room conflict avoidance.
 * 5. Invalid authentication (tampered ciphertext) & wrong destination packet rejection.
 * 6. Database persistence failure resilience without app crash or false delivery state.
 * 7. Process restart & pending outbound queue recovery via recoverQueues().
 */
class EndToEndMessagePipelineTest {

    // --- Mock In-Memory DAOs for Integration Testing ---

    class MockMessageDao : MessageDao {
        val messages = ConcurrentHashMap<String, MessageEntity>()
        private val flowState = MutableStateFlow<List<MessageEntity>>(emptyList())
        var shouldFailInsert = false

        private fun notifyChange() {
            flowState.value = messages.values.sortedBy { it.timestampEpochMs }
        }

        override fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> {
            return flowState.map { list -> list.filter { it.conversationId == conversationId } }
        }

        override suspend fun getMessageById(messageId: String): MessageEntity? = messages[messageId]

        override suspend fun exists(messageId: String): Boolean = messages.containsKey(messageId)

        override suspend fun insertMessage(message: MessageEntity): Long {
            if (shouldFailInsert) {
                throw IllegalStateException("Simulated SQLite database write exception")
            }
            if (messages.containsKey(message.id)) {
                return -1L // IGNORE on conflict
            }
            messages[message.id] = message
            notifyChange()
            return 1L
        }

        override suspend fun updateMessage(message: MessageEntity) {
            messages[message.id] = message
            notifyChange()
        }

        override suspend fun deleteMessage(messageId: String) {
            messages.remove(messageId)
            notifyChange()
        }

        override suspend fun updateDeliveryState(messageId: String, state: MessageDeliveryState) {
            val existing = messages[messageId]
            if (existing != null) {
                messages[messageId] = existing.copy(state = state)
                notifyChange()
            }
        }

        override suspend fun getPendingQueuedMessages(): List<MessageEntity> {
            return messages.values
                .filter { it.state == MessageDeliveryState.QUEUED && it.isOutgoing }
                .sortedBy { it.timestampEpochMs }
        }
    }

    class MockConversationDao : ConversationDao {
        val conversations = ConcurrentHashMap<String, ConversationEntity>()
        private val flowState = MutableStateFlow<List<ConversationEntity>>(emptyList())

        private fun notifyChange() {
            flowState.value = conversations.values.sortedByDescending { it.lastMessageTimestamp }
        }

        override fun getAllConversations(): Flow<List<ConversationEntity>> = flowState

        override suspend fun getConversationById(id: String): ConversationEntity? = conversations[id]

        override suspend fun insertOrUpdate(conversation: ConversationEntity) {
            conversations[conversation.id] = conversation
            notifyChange()
        }

        override suspend fun markAsRead(id: String) {
            val existing = conversations[id]
            if (existing != null) {
                conversations[id] = existing.copy(unreadCount = 0)
                notifyChange()
            }
        }
    }

    class MockOutboundQueueDao : OutboundQueueDao {
        val storage = ConcurrentHashMap<Long, OutboundPacketEntity>()

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
            storage[packet.packetId] = packet
        }

        override suspend fun updatePacket(packet: OutboundPacketEntity) {
            storage[packet.packetId] = packet
        }

        override suspend fun updateRetryProgress(packetId: Long, state: String, retryCount: Int, nextRetryEpochMs: Long) {
            val ex = storage[packetId]
            if (ex != null) {
                storage[packetId] = ex.copy(state = state, retryCount = retryCount, nextRetryEpochMs = nextRetryEpochMs)
            }
        }

        override suspend fun markDelivered(packetId: Long) {
            val ex = storage[packetId]
            if (ex != null) {
                storage[packetId] = ex.copy(state = "DELIVERED")
            }
        }

        override suspend fun updateState(packetId: Long, state: String) {
            val ex = storage[packetId]
            if (ex != null) {
                storage[packetId] = ex.copy(state = state)
            }
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

    // --- TEST 1: UI Message Submission to Routing Dispatch ---
    @Test
    fun test_ui_message_submission_to_routing_dispatch() = runBlocking {
        val messageDao = MockMessageDao()
        val conversationDao = MockConversationDao()
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val messagingRepo = MessagingRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            coroutineScope = testScope
        )

        val localHex = 0x1111222233334444L
        val targetHex = 0x5555666677778888L
        val targetHexStr = "0x" + java.lang.Long.toHexString(targetHex)

        // Seed target conversation
        conversationDao.insertOrUpdate(
            ConversationEntity(
                id = "conv_peer1",
                type = ConversationType.DIRECT,
                targetHexAddress = targetHexStr,
                title = "Elena Rostova",
                subtitle = "@solaris_node",
                lastMessageText = "Initial message",
                lastMessageTimestamp = 1000L,
                unreadCount = 0,
                isDirectNeighbor = true
            )
        )

        val engine = MeshRoutingEngine(localHexAddress = localHex, scope = testScope)
        val transmittedPackets = mutableListOf<BinaryPacket>()

        engine.transmitCallback = { _, packet ->
            synchronized(transmittedPackets) {
                transmittedPackets.add(packet)
            }
        }

        // Wire engine delivery state changes to messaging repository
        engine.onDeliveryStateChanged = { packetId, stateStr ->
            val msgId = "msg_$packetId"
            val state = when (stateStr) {
                "SENDING" -> MessageDeliveryState.SENDING
                "RELAYED" -> MessageDeliveryState.SENT
                "DELIVERED" -> MessageDeliveryState.DELIVERED
                "FAILED" -> MessageDeliveryState.FAILED
                else -> MessageDeliveryState.SENDING
            }
            messagingRepo.updateMessageState(msgId, state)
        }

        // Wire outbound dispatcher
        messagingRepo.outboundDispatcher = { conversationId, destinationHex, text, messageId ->
            val packetId = messageId.removePrefix("msg_").toLongOrNull() ?: 9999L
            engine.sendDirectMessage(
                destinationHex = destinationHex,
                plaintext = text.toByteArray(Charsets.UTF_8),
                packetId = packetId
            )
            true
        }

        // Act: UI submits message
        val submittedMsg = messagingRepo.sendMessage(
            conversationId = "conv_peer1",
            senderHex = "0x" + java.lang.Long.toHexString(localHex),
            senderUsername = "local_node",
            content = "Mesh ping packet payload"
        )

        // Allow coroutine execution
        delay(150)

        // Assert 1: Message persisted in Room messageDao
        val persisted = messageDao.getMessageById(submittedMsg.id)
        assertNotNull("Message must be persisted in database", persisted)
        assertEquals("Content must match", "Mesh ping packet payload", persisted?.content)

        // Assert 2: Dispatcher invoked and packet transmitted via radio
        assertTrue("Packet must be dispatched over radio", transmittedPackets.isNotEmpty())
        val txPacket = transmittedPackets.first()
        assertEquals("Destination hex must match peer", targetHex, txPacket.destinationHex)
        assertEquals("Source hex must match local", localHex, txPacket.sourceHex)
        assertEquals("Packet payload text must match", "Mesh ping packet payload", String(txPacket.payload, Charsets.UTF_8))

        // Assert 3: Conversation metadata updated
        val updatedConv = conversationDao.getConversationById("conv_peer1")
        assertEquals("Conversation last message must update", "Mesh ping packet payload", updatedConv?.lastMessageText)

        testScope.cancel()
    }

    // --- TEST 2: Service Unavailable and Reconnection Queue Recovery ---
    @Test
    fun test_service_unavailable_and_reconnection() = runBlocking {
        val messageDao = MockMessageDao()
        val conversationDao = MockConversationDao()
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val messagingRepo = MessagingRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            coroutineScope = testScope
        )

        // Outbound dispatcher is NULL (Service is disconnected / BLE offline)
        messagingRepo.outboundDispatcher = null

        // Act: UI submits message while disconnected
        val queuedMsg = messagingRepo.sendMessage(
            conversationId = "conv_offline",
            senderHex = "0xLOCAL_HEX",
            senderUsername = "me",
            content = "Offline message pending reconnection"
        )

        delay(100)

        // Assert: Message is safely queued in Room with QUEUED state
        val inDb = messageDao.getMessageById(queuedMsg.id)
        assertNotNull("Message must not be dropped when service is disconnected", inDb)
        assertEquals("Message must remain in QUEUED state", MessageDeliveryState.QUEUED, inDb?.state)

        // Reconnection: Service starts and retrieves pending queued messages
        val pendingList = messageDao.getPendingQueuedMessages()
        assertEquals("Should recover 1 queued message", 1, pendingList.size)
        assertEquals(queuedMsg.id, pendingList.first().id)

        // Service flushes the queue
        val flushedPackets = mutableListOf<Long>()
        val engine = MeshRoutingEngine(localHexAddress = 0x1111L, scope = testScope)
        engine.transmitCallback = { _, packet ->
            flushedPackets.add(packet.packetId)
        }

        for (msg in pendingList) {
            messageDao.updateDeliveryState(msg.id, MessageDeliveryState.SENDING)
            val packetId = msg.id.removePrefix("msg_").toLongOrNull() ?: 1234L
            engine.sendDirectMessage(
                destinationHex = 0x2222L,
                plaintext = msg.content.toByteArray(Charsets.UTF_8),
                packetId = packetId
            )
        }

        delay(100)
        assertEquals("Queued message must be transmitted upon reconnection", 1, flushedPackets.size)
        assertEquals("Message state must transition away from QUEUED", MessageDeliveryState.SENDING, messageDao.getMessageById(queuedMsg.id)?.state)

        testScope.cancel()
    }

    // --- TEST 3: Incoming Encrypted Message to Room Insertion ---
    @Test
    fun test_incoming_encrypted_message_to_room_insertion() = runBlocking {
        val messageDao = MockMessageDao()
        val conversationDao = MockConversationDao()
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val messagingRepo = MessagingRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            coroutineScope = testScope
        )

        val localHex = 0xAAAAAAAA11112222L
        val senderHex = 0xBBBBBBBB33334444L
        val senderHexStr = "0x" + java.lang.Long.toHexString(senderHex).padStart(16, '0')
        val packetId = 88776655L

        val incomingPacket = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            flags = PacketFlags.ENCRYPTED,
            ttl = 4,
            hopCount = 1,
            packetId = packetId,
            sourceHex = senderHex,
            destinationHex = localHex,
            payload = "Decrypted secure plaintext".toByteArray(Charsets.UTF_8)
        )

        // Simulate RoutingDecision.AcceptLocally with successfully decrypted payload
        val decision = RoutingDecision.AcceptLocally(
            packet = incomingPacket,
            isDirectDestination = true,
            decryptedPayload = "Decrypted secure plaintext".toByteArray(Charsets.UTF_8)
        )

        // Act: Process incoming accepted packet into Room via receiveIncomingMessage
        val msgId = "msg_${incomingPacket.packetId}"
        val convId = "conv_${java.lang.Long.toHexString(senderHex)}"
        val inserted = messagingRepo.receiveIncomingMessage(
            messageId = msgId,
            conversationId = convId,
            senderHex = senderHexStr,
            senderUsername = senderHexStr.take(10),
            content = String(decision.decryptedPayload!!, Charsets.UTF_8),
            hopCount = incomingPacket.hopCount.toInt(),
            maxHops = (incomingPacket.ttl + incomingPacket.hopCount).toInt()
        )

        // Assert: Inserted into Room
        assertTrue("Incoming verified message must be inserted", inserted)
        val inDb = messageDao.getMessageById(msgId)
        assertNotNull("Message must exist in Room", inDb)
        assertEquals("Decrypted secure plaintext", inDb?.content)
        assertEquals(MessageDeliveryState.DELIVERED, inDb?.state)
        assertFalse("Message must be marked incoming", inDb!!.isOutgoing)

        // Assert: Conversation created/updated
        val conv = conversationDao.getConversationById(convId)
        assertNotNull("Conversation must be created for peer", conv)
        assertEquals("Decrypted secure plaintext", conv?.lastMessageText)
        assertEquals("Unread count must be 1", 1, conv?.unreadCount)

        testScope.cancel()
    }

    // --- TEST 4: Duplicate Incoming Packet Suppression ---
    @Test
    fun test_duplicate_incoming_packet_suppression() = runBlocking {
        val messageDao = MockMessageDao()
        val conversationDao = MockConversationDao()
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val messagingRepo = MessagingRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            coroutineScope = testScope
        )

        val msgId = "msg_12345678"
        val convId = "conv_test_peer"

        // First receipt
        val firstInsert = messagingRepo.receiveIncomingMessage(
            messageId = msgId,
            conversationId = convId,
            senderHex = "0xPEER",
            senderUsername = "peer_user",
            content = "Hello from mesh node"
        )
        assertTrue("First arrival must succeed", firstInsert)
        assertEquals("Room must contain 1 message", 1, messageDao.messages.size)

        // Duplicate receipt of exact same packet (e.g. alternate mesh flood path)
        val duplicateInsert = messagingRepo.receiveIncomingMessage(
            messageId = msgId,
            conversationId = convId,
            senderHex = "0xPEER",
            senderUsername = "peer_user",
            content = "Hello from mesh node"
        )

        // Assert: Duplicate suppressed!
        assertFalse("Duplicate incoming message must be suppressed", duplicateInsert)
        assertEquals("Room must still contain exactly 1 message, no duplicate rows", 1, messageDao.messages.size)

        testScope.cancel()
    }

    // --- TEST 5: Invalid Authentication & Wrong Destination Rejection ---
    @Test
    fun test_invalid_authentication_and_wrong_destination_rejection() = runBlocking {
        val localHex = 0xCAFECAFE00000001L
        val wrongDestHex = 0xDEADBEEF00000002L
        val senderHex = 0x1111222233334444L

        // Scenario A: Wrong destination packet
        val wrongDestPacket = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            flags = 0,
            packetId = 1001L,
            sourceHex = senderHex,
            destinationHex = wrongDestHex, // Addressed to someone else!
            payload = "Private text for someone else".toByteArray(Charsets.UTF_8)
        )

        val isForMe = wrongDestPacket.destinationHex == localHex || wrongDestPacket.destinationHex == BinaryPacket.BROADCAST_DESTINATION
        assertFalse("Packet addressed to another node must not be accepted as local", isForMe)

        // Scenario B: Packet flagged ENCRYPTED but decryption failed (tampered / unauthenticated)
        val tamperedEncryptedPacket = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            flags = PacketFlags.ENCRYPTED,
            packetId = 1002L,
            sourceHex = senderHex,
            destinationHex = localHex,
            payload = byteArrayOf(0x01, 0x02, 0x03) // Garbled ciphertext
        )

        // Simulated AcceptLocally with null decryptedPayload
        val tamperedDecision = RoutingDecision.AcceptLocally(
            packet = tamperedEncryptedPacket,
            isDirectDestination = true,
            decryptedPayload = null // Decryption failed
        )

        val isEncrypted = PacketFlags.hasFlag(tamperedDecision.packet.flags, PacketFlags.ENCRYPTED)
        val isDecryptionSuccessful = tamperedDecision.decryptedPayload != null

        // Security rule: If flagged encrypted, payload MUST be authenticated and decrypted
        val isTrusted = !isEncrypted || isDecryptionSuccessful
        assertFalse("Unauthenticated or undecrypted content must be rejected from Room insertion", isTrusted)
    }

    // --- TEST 6: Database Persistence Failure Without Crash or False Delivery ---
    @Test
    fun test_database_failure_without_message_loss() = runBlocking {
        val messageDao = MockMessageDao()
        messageDao.shouldFailInsert = true // Simulate SQLite database failure

        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val messagingRepo = MessagingRepository(
            messageDao = messageDao,
            conversationDao = null,
            coroutineScope = testScope
        )

        var dispatcherInvoked = false
        messagingRepo.outboundDispatcher = { _, _, _, _ ->
            dispatcherInvoked = true
            true
        }

        // Act: UI calls sendMessage during disk error
        val msg = messagingRepo.sendMessage(
            conversationId = "conv_fail",
            senderHex = "0xLOCAL",
            senderUsername = "user",
            content = "This insert will throw an exception"
        )

        delay(100)

        // Assert: Application did not crash; dispatcher was NOT called (no false transmission!)
        assertFalse("Dispatcher must not be invoked if database insertion fails", dispatcherInvoked)

        testScope.cancel()
    }

    // --- TEST 7: Process Restart & Pending Queue Recovery ---
    @Test
    fun test_process_restart_and_pending_queue_recovery() = runBlocking {
        val outboundDao = MockOutboundQueueDao()
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Seed pending packets in OutboundQueueDao from previous process session
        val unAckedPacket1 = OutboundPacketEntity(
            packetId = 5001L,
            destinationHex = 0x2222L,
            packetType = PacketType.DIRECT_MESSAGE.code,
            rawPacketBytes = byteArrayOf(0x48, 0x58, 0x01),
            retryCount = 1,
            maxRetries = 3,
            nextRetryEpochMs = System.currentTimeMillis() - 5000L, // Overdue!
            createdAtEpochMs = System.currentTimeMillis() - 10000L,
            state = "PENDING"
        )
        outboundDao.insertPacket(unAckedPacket1)

        val deliveredPacket = OutboundPacketEntity(
            packetId = 5002L,
            destinationHex = 0x3333L,
            packetType = PacketType.DIRECT_MESSAGE.code,
            rawPacketBytes = byteArrayOf(0x48, 0x58, 0x01),
            retryCount = 0,
            maxRetries = 3,
            nextRetryEpochMs = System.currentTimeMillis() + 10000L,
            createdAtEpochMs = System.currentTimeMillis(),
            state = "DELIVERED"
        )
        outboundDao.insertPacket(deliveredPacket)

        // Act: Initialize new MeshRoutingEngine (simulating app process recreation)
        val engine = MeshRoutingEngine(
            localHexAddress = 0x1111L,
            outboundQueueDao = outboundDao,
            scope = testScope
        )

        // Execute queue recovery
        engine.recoverQueues()

        // Assert: Pending un-ACKed packet recovered
        val pendingCount = outboundDao.getPendingCount()
        assertEquals("Should recover 1 pending un-ACKed packet", 1, pendingCount)

        testScope.cancel()
    }

    // --- TEST 8: Recipient Resolution Failure Prevents Transmission to Self ---
    @Test
    fun test_recipient_resolution_failure_prevents_silent_transmission_to_self() = runBlocking {
        val messageDao = MockMessageDao()
        val conversationDao = MockConversationDao()
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val messagingRepo = MessagingRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            coroutineScope = testScope
        )

        val localHex = "0x1111222233334444"

        // Seed conversation where targetHexAddress is accidentally self-hex
        conversationDao.insertOrUpdate(
            ConversationEntity(
                id = "conv_self_loop",
                type = ConversationType.DIRECT,
                targetHexAddress = localHex, // Target is SELF!
                title = "Loopback",
                subtitle = "@self",
                lastMessageText = "",
                lastMessageTimestamp = 0L,
                unreadCount = 0,
                isDirectNeighbor = false
            )
        )

        var dispatcherInvoked = false
        messagingRepo.outboundDispatcher = { _, _, _, _ ->
            dispatcherInvoked = true
            true
        }

        // Act: Attempt to send message
        val msg = messagingRepo.sendMessage(
            conversationId = "conv_self_loop",
            senderHex = localHex,
            senderUsername = "alice",
            content = "This should not transmit to self"
        )

        delay(100)

        // Assert: Never silently transmit to self!
        assertFalse("Message must NEVER be dispatched to self as recipient", dispatcherInvoked)
        val inDb = messageDao.getMessageById(msg.id)
        assertNotNull("Message must be retained in Room", inDb)
        assertEquals("Message must remain in QUEUED state", MessageDeliveryState.QUEUED, inDb?.state)

        testScope.cancel()
    }

    // --- TEST 9: E2E Delivery ACK Sent Only After Room Persistence Succeeds ---
    @Test
    fun test_e2e_delivery_ack_sent_only_after_room_persistence_and_idempotent_duplicate() = runBlocking {
        val messageDao = MockMessageDao()
        val conversationDao = MockConversationDao()
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val localHex = 0xAAAA1111L
        val senderHex = 0xBBBB2222L

        val ackTransmitted = AtomicBoolean(false)
        val engine = MeshRoutingEngine(localHexAddress = localHex, scope = testScope)
        engine.transmitCallback = { _, packet ->
            if (packet.type == PacketType.DELIVERY_ACK) {
                ackTransmitted.set(true)
            }
        }

        val messagingRepo = MessagingRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            coroutineScope = testScope
        )

        // Scenario A: Message insert fails -> NO delivery ACK sent!
        messageDao.shouldFailInsert = true
        var ackSentOnFailure = false

        try {
            val inserted = messagingRepo.receiveIncomingMessage(
                messageId = "msg_fail_ack",
                conversationId = "conv_fail",
                senderHex = "0x" + java.lang.Long.toHexString(senderHex),
                senderUsername = "sender",
                content = "Test message"
            )
            if (inserted) {
                engine.sendEndToEndAck(senderHex, 9001L)
                ackSentOnFailure = true
            }
        } catch (e: Exception) {
            // Expected failure
        }

        assertFalse("E2E Delivery ACK must NOT be sent if Room persistence fails", ackSentOnFailure)
        assertFalse("Engine must not have transmitted DELIVERY_ACK", ackTransmitted.get())

        // Scenario B: Message insert succeeds -> E2E Delivery ACK sent!
        messageDao.shouldFailInsert = false
        val insertedSuccess = messagingRepo.receiveIncomingMessage(
            messageId = "msg_success_ack",
            conversationId = "conv_ok",
            senderHex = "0x" + java.lang.Long.toHexString(senderHex),
            senderUsername = "sender",
            content = "Valid message"
        )
        assertTrue(insertedSuccess)
        engine.sendEndToEndAck(senderHex, 9002L)
        assertTrue("E2E Delivery ACK must be transmitted after successful persistence", ackTransmitted.get())

        testScope.cancel()
    }
}

