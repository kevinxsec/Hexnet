package net.hexnet.app.core.model

enum class MessageDeliveryState {
    PENDING,
    QUEUED,
    SENDING,
    RELAYED,
    DELIVERED,
    FAILED
}

enum class PeerStatus {
    ACTIVE_DIRECT,
    MESH_RELAY,
    RECENT_OFFLINE
}

enum class ConversationType {
    DIRECT,
    PUBLIC_ROOM
}

enum class NetworkEventType {
    PEER_DISCOVERED,
    PEER_DISCONNECTED,
    PACKET_RELAYED,
    ROUTE_OPTIMIZED,
    DUPLICATE_SUPPRESSED,
    HOP_LIMIT_EXCEEDED
}

data class UserProfile(
    val username: String,
    val displayName: String,
    val hexAddress: String,
    val keyFingerprint: String,
    val bio: String = "",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val isVerified: Boolean = false
)

data class Peer(
    val peerId: String,
    val hexAddress: String,
    val username: String,
    val displayName: String,
    val rssiDbm: Int,
    val hopDistance: Int,
    val isDirectNeighbor: Boolean,
    val lastSeenEpochMs: Long,
    val status: PeerStatus,
    val isContact: Boolean = false,
    val isBlocked: Boolean = false,
    val isIdentityVerified: Boolean = false
)

data class Contact(
    val contactId: String,
    val peerHexAddress: String,
    val customNickname: String? = null,
    val trustedSinceEpochMs: Long = System.currentTimeMillis(),
    val isVerified: Boolean = false,
    val notes: String = ""
)

data class Conversation(
    val id: String,
    val type: ConversationType,
    val targetHexAddress: String,
    val title: String,
    val subtitle: String,
    val lastMessageText: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int = 0,
    val isDirectNeighbor: Boolean = false
)

data class ChatRoom(
    val id: String,
    val name: String,
    val topic: String,
    val description: String,
    val channelHex: String,
    val participantCount: Int,
    val isJoined: Boolean,
    val maxHopLimit: Int = 5,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class RoomParticipant(
    val hexAddress: String,
    val username: String,
    val role: String,
    val isLocalUser: Boolean = false
)

data class Message(
    val id: String,
    val conversationId: String,
    val senderHexAddress: String,
    val senderUsername: String,
    val content: String,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val state: MessageDeliveryState = MessageDeliveryState.PENDING,
    val hopCount: Int = 0,
    val maxHops: Int = 5,
    val isOutgoing: Boolean = false,
    val replyToMessageId: String? = null
)

data class NetworkEvent(
    val id: String,
    val type: NetworkEventType,
    val summary: String,
    val details: String,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val relatedHexAddress: String? = null
)

data class UserPreferences(
    val darkMode: Boolean = true,
    val relayEnabled: Boolean = true,
    val maxHops: Int = 5,
    val notificationsEnabled: Boolean = true,
    val autoJoinDefaultRooms: Boolean = true,
    val diagnosticsEnabled: Boolean = true
)

data class MeshPacket(
    val packetId: String,
    val sourceHex: String,
    val destinationHex: String,
    val payloadType: String,
    val payload: String,
    val hopCount: Int,
    val ttl: Int,
    val packetHash: String,
    val timestamp: Long = System.currentTimeMillis()
)
