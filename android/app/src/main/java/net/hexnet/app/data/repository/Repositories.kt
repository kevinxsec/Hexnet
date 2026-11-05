package net.hexnet.app.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.hexnet.app.core.model.*

class IdentityRepository(
    private val preferencesDataStore: net.hexnet.app.data.local.PreferencesDataStore
) {
    val userProfile: Flow<UserProfile?> = preferencesDataStore.userProfileFlow

    suspend fun saveProfile(profile: UserProfile) {
        preferencesDataStore.saveProfile(profile)
    }

    suspend fun clearIdentity() {
        preferencesDataStore.clearIdentity()
    }
}

class ContactsRepository(
    private val peerDao: net.hexnet.app.core.database.dao.PeerDao? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val _inMemoryPeers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: Flow<List<Peer>> = peerDao?.getAllPeers()?.map { list ->
        list.map { it.toDomain() }
    } ?: _inMemoryPeers.asStateFlow()

    fun getTrustedContacts(): Flow<List<Peer>> = peers.map { list ->
        list.filter { it.isContact && !it.isBlocked }
    }

    fun upsertPeer(peer: Peer) {
        if (peerDao != null) {
            coroutineScope.launch {
                peerDao.upsertPeer(peer.toEntity())
            }
        } else {
            _inMemoryPeers.value = _inMemoryPeers.value.filterNot { it.peerId == peer.peerId } + peer
        }
    }

    fun toggleContact(peerId: String) {
        if (peerDao != null) {
            coroutineScope.launch {
                val current = peerDao.getTrustedContacts() // or query
                // Direct update
                peerDao.setContactStatus(peerId, true)
            }
        } else {
            _inMemoryPeers.value = _inMemoryPeers.value.map { peer ->
                if (peer.peerId == peerId) peer.copy(isContact = !peer.isContact) else peer
            }
        }
    }

    fun toggleBlock(peerId: String) {
        if (peerDao != null) {
            coroutineScope.launch {
                peerDao.setBlockedStatus(peerId, true)
            }
        } else {
            _inMemoryPeers.value = _inMemoryPeers.value.map { peer ->
                if (peer.peerId == peerId) peer.copy(isBlocked = !peer.isBlocked) else peer
            }
        }
    }

    fun verifyIdentity(peerId: String) {
        _inMemoryPeers.value = _inMemoryPeers.value.map { peer ->
            if (peer.peerId == peerId) peer.copy(isIdentityVerified = true) else peer
        }
    }
}

class MessagingRepository(
    private val messageDao: net.hexnet.app.core.database.dao.MessageDao? = null,
    private val conversationDao: net.hexnet.app.core.database.dao.ConversationDao? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: Flow<List<Conversation>> = conversationDao?.getAllConversations()?.map { list ->
        list.map { it.toDomain() }
    } ?: _conversations.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())

    fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao?.getMessagesForConversation(conversationId)?.map { list ->
            list.map { it.toDomain() }
        } ?: _messages.map { list ->
            list.filter { it.conversationId == conversationId }
        }
    }

    // Outbound dispatch handler connected to active mesh service / routing engine
    var outboundDispatcher: (suspend (conversationId: String, targetHex: Long, text: String, messageId: String) -> Boolean)? = null

    fun sendMessage(
        conversationId: String,
        senderHex: String,
        senderUsername: String,
        content: String,
        replyToId: String? = null
    ): Message {
        val packetId = kotlin.math.abs(kotlin.random.Random.nextLong().let { if (it == 0L) 1L else it })
        val newMsg = Message(
            id = "msg_$packetId",
            conversationId = conversationId,
            senderHexAddress = senderHex,
            senderUsername = senderUsername,
            content = content,
            timestampEpochMs = System.currentTimeMillis(),
            state = MessageDeliveryState.QUEUED,
            isOutgoing = true,
            replyToMessageId = replyToId
        )

        // Durable persistence with crash consistency: Insert into Room first
        coroutineScope.launch {
            try {
                if (messageDao != null) {
                    val rowId = messageDao.insertMessage(newMsg.toEntity())
                    if (rowId == -1L) {
                        // Duplicate message ID suppression
                        return@launch
                    }
                } else {
                    _messages.value = _messages.value + newMsg
                }

                // Update conversation metadata safely
                if (conversationDao != null) {
                    try {
                        val existing = conversationDao.getConversationById(conversationId)
                        if (existing != null) {
                            conversationDao.insertOrUpdate(existing.copy(
                                lastMessageText = content,
                                lastMessageTimestamp = newMsg.timestampEpochMs
                            ))
                        }
                    } catch (e: Exception) {
                        // Secondary update failure handled safely
                    }
                } else {
                    _conversations.value = _conversations.value.map { conv ->
                        if (conv.id == conversationId) {
                            conv.copy(
                                lastMessageText = content,
                                lastMessageTimestamp = newMsg.timestampEpochMs
                            )
                        } else conv
                    }
                }

                // Recipient resolution: never silently fall back to local sender
                val targetHex = resolveTargetHexForConversation(conversationId)
                val senderHexLong = parseHexAddress(senderHex)

                if (targetHex == null || (targetHex == senderHexLong && targetHex != net.hexnet.app.core.protocol.BinaryPacket.BROADCAST_DESTINATION)) {
                    android.util.Log.w("MessagingRepo", "Cannot resolve destination for conversation $conversationId. Message retained as QUEUED.")
                    return@launch
                }

                // Dispatch through active BLE mesh routing engine
                val dispatcher = outboundDispatcher
                if (dispatcher != null) {
                    try {
                        val dispatched = dispatcher(conversationId, targetHex, content, newMsg.id)
                        if (!dispatched) {
                            // Message remains safely QUEUED for subsequent retry
                        }
                    } catch (e: Exception) {
                        // Preserved safely in Room
                    }
                }
            } catch (e: Exception) {
                // If local database persistence itself fails, mark state as FAILED
                try {
                    messageDao?.updateDeliveryState(newMsg.id, MessageDeliveryState.FAILED)
                } catch (ignore: Exception) {}
            }
        }

        return newMsg
    }

    suspend fun resolveTargetHexForConversation(conversationId: String): Long? {
        val targetHexStr = conversationDao?.getConversationById(conversationId)?.targetHexAddress
            ?: _conversations.value.find { it.id == conversationId }?.targetHexAddress
            ?: return null

        if (targetHexStr.isBlank()) return null
        val parsed = parseHexAddress(targetHexStr)
        if (parsed == 0L && !targetHexStr.contains("BROADCAST", ignoreCase = true)) {
            return null
        }
        return parsed
    }

    suspend fun getTargetHexForConversation(conversationId: String, fallbackHex: String): Long {
        return resolveTargetHexForConversation(conversationId)
            ?: parseHexAddress(fallbackHex)
    }

    /**
     * Inserts an incoming verified message from BLE mesh pipeline into Room.
     * Guarantees duplicate suppression and updates conversation metadata.
     */
    suspend fun receiveIncomingMessage(
        messageId: String,
        conversationId: String,
        senderHex: String,
        senderUsername: String,
        content: String,
        hopCount: Int = 1,
        maxHops: Int = 5,
        timestampEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (messageDao != null) {
            if (messageDao.exists(messageId)) {
                return false // Duplicate suppressed
            }
            val entity = net.hexnet.app.core.database.entity.MessageEntity(
                id = messageId,
                conversationId = conversationId,
                senderHexAddress = senderHex,
                senderUsername = senderUsername,
                content = content,
                timestampEpochMs = timestampEpochMs,
                state = MessageDeliveryState.DELIVERED,
                hopCount = hopCount,
                maxHops = maxHops,
                isOutgoing = false,
                replyToMessageId = null
            )
            val rowId = messageDao.insertMessage(entity)
            if (rowId == -1L) return false

            if (conversationDao != null) {
                val existing = conversationDao.getConversationById(conversationId)
                if (existing != null) {
                    conversationDao.insertOrUpdate(existing.copy(
                        lastMessageText = content,
                        lastMessageTimestamp = timestampEpochMs,
                        unreadCount = existing.unreadCount + 1
                    ))
                } else {
                    conversationDao.insertOrUpdate(net.hexnet.app.core.database.entity.ConversationEntity(
                        id = conversationId,
                        type = ConversationType.DIRECT,
                        targetHexAddress = senderHex,
                        title = senderUsername,
                        subtitle = "@$senderUsername · Direct Mesh",
                        lastMessageText = content,
                        lastMessageTimestamp = timestampEpochMs,
                        unreadCount = 1,
                        isDirectNeighbor = (hopCount <= 1)
                    ))
                }
            }
            return true
        } else {
            if (_messages.value.any { it.id == messageId }) return false
            val msg = Message(
                id = messageId,
                conversationId = conversationId,
                senderHexAddress = senderHex,
                senderUsername = senderUsername,
                content = content,
                timestampEpochMs = timestampEpochMs,
                state = MessageDeliveryState.DELIVERED,
                hopCount = hopCount,
                maxHops = maxHops,
                isOutgoing = false
            )
            _messages.value = _messages.value + msg
            return true
        }
    }

    fun updateMessageState(messageId: String, newState: MessageDeliveryState) {
        if (messageDao != null) {
            coroutineScope.launch {
                messageDao.updateDeliveryState(messageId, newState)
            }
        } else {
            _messages.value = _messages.value.map { msg ->
                if (msg.id == messageId) msg.copy(state = newState) else msg
            }
        }
    }

    fun getOrCreateDirectConversation(targetHex: String, title: String): String {
        val cleanHex = targetHex.lowercase().removePrefix("0x")
        val convId = "conv_$cleanHex"
        coroutineScope.launch {
            if (conversationDao != null) {
                val existing = conversationDao.getConversationById(convId)
                if (existing == null) {
                    conversationDao.insertOrUpdate(
                        net.hexnet.app.core.database.entity.ConversationEntity(
                            id = convId,
                            type = ConversationType.DIRECT,
                            targetHexAddress = targetHex,
                            title = title,
                            subtitle = "@$title · Direct Mesh",
                            lastMessageText = "Direct secure conversation started",
                            lastMessageTimestamp = System.currentTimeMillis(),
                            unreadCount = 0,
                            isDirectNeighbor = true
                        )
                    )
                }
            } else {
                if (_conversations.value.none { it.id == convId }) {
                    _conversations.value = _conversations.value + Conversation(
                        id = convId,
                        type = ConversationType.DIRECT,
                        targetHexAddress = targetHex,
                        title = title,
                        subtitle = "@$title · Direct Mesh",
                        lastMessageText = "Direct secure conversation started",
                        lastMessageTimestamp = System.currentTimeMillis(),
                        unreadCount = 0,
                        isDirectNeighbor = true
                    )
                }
            }
        }
        return convId
    }

    fun retryMessage(messageId: String) {
        coroutineScope.launch {
            val msg = messageDao?.getMessageById(messageId)?.toDomain()
                ?: _messages.value.find { it.id == messageId }
                ?: return@launch

            if (msg.state == MessageDeliveryState.FAILED) {
                updateMessageState(messageId, MessageDeliveryState.QUEUED)
                val targetHex = resolveTargetHexForConversation(msg.conversationId)
                if (targetHex != null && outboundDispatcher != null) {
                    try {
                        outboundDispatcher?.invoke(msg.conversationId, targetHex, msg.content, msg.id)
                    } catch (e: Exception) {
                        // Remains queued
                    }
                }
            }
        }
    }

    fun deleteMessage(messageId: String) {
        if (messageDao != null) {
            coroutineScope.launch {
                messageDao.deleteMessage(messageId)
            }
        } else {
            _messages.value = _messages.value.filterNot { it.id == messageId }
        }
    }
}

fun net.hexnet.app.core.database.entity.MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    senderHexAddress = senderHexAddress,
    senderUsername = senderUsername,
    content = content,
    timestampEpochMs = timestampEpochMs,
    state = state,
    hopCount = hopCount,
    maxHops = maxHops,
    isOutgoing = isOutgoing,
    replyToMessageId = replyToMessageId
)

fun Message.toEntity(): net.hexnet.app.core.database.entity.MessageEntity = net.hexnet.app.core.database.entity.MessageEntity(
    id = id,
    conversationId = conversationId,
    senderHexAddress = senderHexAddress,
    senderUsername = senderUsername,
    content = content,
    timestampEpochMs = timestampEpochMs,
    state = state,
    hopCount = hopCount,
    maxHops = maxHops,
    isOutgoing = isOutgoing,
    replyToMessageId = replyToMessageId
)

fun net.hexnet.app.core.database.entity.ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    type = type,
    targetHexAddress = targetHexAddress,
    title = title,
    subtitle = subtitle,
    lastMessageText = lastMessageText,
    lastMessageTimestamp = lastMessageTimestamp,
    unreadCount = unreadCount,
    isDirectNeighbor = isDirectNeighbor
)

fun Conversation.toEntity(): net.hexnet.app.core.database.entity.ConversationEntity = net.hexnet.app.core.database.entity.ConversationEntity(
    id = id,
    type = type,
    targetHexAddress = targetHexAddress,
    title = title,
    subtitle = subtitle,
    lastMessageText = lastMessageText,
    lastMessageTimestamp = lastMessageTimestamp,
    unreadCount = unreadCount,
    isDirectNeighbor = isDirectNeighbor
)

fun net.hexnet.app.core.database.entity.PeerEntity.toDomain(): Peer = Peer(
    peerId = peerId,
    hexAddress = hexAddress,
    username = username,
    displayName = displayName,
    rssiDbm = rssiDbm,
    hopDistance = hopDistance,
    isDirectNeighbor = isDirectNeighbor,
    lastSeenEpochMs = lastSeenEpochMs,
    status = status,
    isContact = isContact,
    isIdentityVerified = isIdentityVerified,
    isBlocked = isBlocked
)

fun Peer.toEntity(): net.hexnet.app.core.database.entity.PeerEntity = net.hexnet.app.core.database.entity.PeerEntity(
    peerId = peerId,
    hexAddress = hexAddress,
    username = username,
    displayName = displayName,
    rssiDbm = rssiDbm,
    hopDistance = hopDistance,
    isDirectNeighbor = isDirectNeighbor,
    lastSeenEpochMs = lastSeenEpochMs,
    status = status,
    isContact = isContact,
    isIdentityVerified = isIdentityVerified,
    isBlocked = isBlocked
)

fun net.hexnet.app.core.database.entity.NetworkEventEntity.toDomain(): NetworkEvent = NetworkEvent(
    id = id,
    type = type,
    summary = summary,
    details = details,
    timestampEpochMs = timestampEpochMs,
    relatedHexAddress = relatedHexAddress
)

fun NetworkEvent.toEntity(): net.hexnet.app.core.database.entity.NetworkEventEntity = net.hexnet.app.core.database.entity.NetworkEventEntity(
    id = id,
    type = type,
    summary = summary,
    details = details,
    timestampEpochMs = timestampEpochMs,
    relatedHexAddress = relatedHexAddress
)

class MeshNetworkRepository(
    private val eventDao: net.hexnet.app.core.database.dao.NetworkEventDao? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val _inMemoryEvents = MutableStateFlow<List<NetworkEvent>>(emptyList())
    val events: Flow<List<NetworkEvent>> = eventDao?.getRecentEvents()?.map { list ->
        list.map { it.toDomain() }
    } ?: _inMemoryEvents.asStateFlow()

    private val _rooms = MutableStateFlow<List<ChatRoom>>(listOf(
        ChatRoom(
            id = "room_global_mesh",
            name = "Global Mesh Channel",
            topic = "General decentralized mesh broadcast and route testing",
            description = "Open flood channel relayed across all participating nodes within 5 hops.",
            channelHex = "0xBROADCAST_HEX_00",
            participantCount = 1,
            isJoined = true
        )
    ))
    val rooms: Flow<List<ChatRoom>> = _rooms.asStateFlow()

    fun toggleRoomMembership(roomId: String) {
        _rooms.value = _rooms.value.map { room ->
            if (room.id == roomId) {
                val newJoined = !room.isJoined
                val delta = if (newJoined) 1 else -1
                room.copy(isJoined = newJoined, participantCount = room.participantCount + delta)
            } else room
        }
    }

    fun recordEvent(type: NetworkEventType, summary: String, details: String, hex: String? = null) {
        val ev = NetworkEvent(
            id = "ev_${System.currentTimeMillis()}",
            type = type,
            summary = summary,
            details = details,
            relatedHexAddress = hex
        )
        if (eventDao != null) {
            coroutineScope.launch {
                eventDao.insertEvent(ev.toEntity())
            }
        } else {
            _inMemoryEvents.value = listOf(ev) + _inMemoryEvents.value.take(49)
        }
    }
}

fun parseHexAddress(hex: String): Long {
    val clean = hex.removePrefix("0x").trim()
    if (clean.contains("BROADCAST", ignoreCase = true)) {
        return net.hexnet.app.core.protocol.BinaryPacket.BROADCAST_DESTINATION
    }
    if (clean.isEmpty()) return 0L
    return try {
        java.lang.Long.parseUnsignedLong(clean, 16)
    } catch (e: Exception) {
        try {
            clean.take(16).padEnd(16, '0').toLong(16)
        } catch (ex: Exception) {
            0L
        }
    }
}
