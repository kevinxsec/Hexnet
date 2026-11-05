package net.hexnet.app.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.hexnet.app.core.model.ConversationType
import net.hexnet.app.core.model.MessageDeliveryState
import net.hexnet.app.core.model.NetworkEventType
import net.hexnet.app.core.model.PeerStatus

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val username: String,
    val displayName: String,
    val hexAddress: String,
    val keyFingerprint: String,
    val bio: String,
    val createdAtEpochMs: Long,
    val isVerified: Boolean
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderHexAddress: String,
    val senderUsername: String,
    val content: String,
    val timestampEpochMs: Long,
    val state: MessageDeliveryState,
    val hopCount: Int,
    val maxHops: Int,
    val isOutgoing: Boolean,
    val replyToMessageId: String?
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: ConversationType,
    val targetHexAddress: String,
    val title: String,
    val subtitle: String,
    val lastMessageText: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    val isDirectNeighbor: Boolean
)

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val hexAddress: String,
    val username: String,
    val displayName: String,
    val rssiDbm: Int,
    val hopDistance: Int,
    val isDirectNeighbor: Boolean,
    val lastSeenEpochMs: Long,
    val status: PeerStatus,
    val isContact: Boolean,
    val isBlocked: Boolean,
    val isIdentityVerified: Boolean
)

@Entity(tableName = "chat_rooms")
data class ChatRoomEntity(
    @PrimaryKey val id: String,
    val name: String,
    val topic: String,
    val description: String,
    val channelHex: String,
    val participantCount: Int,
    val isJoined: Boolean,
    val maxHopLimit: Int,
    val createdAtEpochMs: Long
)

@Entity(tableName = "network_events")
data class NetworkEventEntity(
    @PrimaryKey val id: String,
    val type: NetworkEventType,
    val summary: String,
    val details: String,
    val timestampEpochMs: Long,
    val relatedHexAddress: String?
)

@Entity(tableName = "outbound_packets")
data class OutboundPacketEntity(
    @PrimaryKey val packetId: Long,
    val destinationHex: Long,
    val packetType: Byte,
    val rawPacketBytes: ByteArray,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val nextRetryEpochMs: Long,
    val createdAtEpochMs: Long,
    val state: String // "PENDING", "AWAITING_LINK_ACK", "AWAITING_E2E_ACK", "DELIVERED", "FAILED"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OutboundPacketEntity
        if (packetId != other.packetId) return false
        if (!rawPacketBytes.contentEquals(other.rawPacketBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = packetId.hashCode()
        result = 31 * result + rawPacketBytes.contentHashCode()
        return result
    }
}

@Entity(tableName = "relay_packets")
data class RelayPacketEntity(
    @PrimaryKey val packetId: Long,
    val destinationHex: Long,
    val sourceHex: Long,
    val rawPacketBytes: ByteArray,
    val expiresEpochMs: Long,
    val receivedEpochMs: Long,
    val relayAttempts: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RelayPacketEntity
        if (packetId != other.packetId) return false
        if (!rawPacketBytes.contentEquals(other.rawPacketBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = packetId.hashCode()
        result = 31 * result + rawPacketBytes.contentHashCode()
        return result
    }
}

