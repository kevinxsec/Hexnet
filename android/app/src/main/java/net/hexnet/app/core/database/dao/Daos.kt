package net.hexnet.app.core.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.hexnet.app.core.database.entity.*

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampEpochMs ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :messageId)")
    suspend fun exists(messageId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("UPDATE messages SET state = :state WHERE id = :messageId")
    suspend fun updateDeliveryState(messageId: String, state: MessageDeliveryState)

    @Query("SELECT * FROM messages WHERE state = 'QUEUED' AND isOutgoing = 1 ORDER BY timestampEpochMs ASC")
    suspend fun getPendingQueuedMessages(): List<MessageEntity>
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun markAsRead(id: String)
}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeenEpochMs DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE isContact = 1 ORDER BY displayName ASC")
    fun getTrustedContacts(): Flow<List<PeerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("UPDATE peers SET isContact = :isContact WHERE peerId = :peerId")
    suspend fun setContactStatus(peerId: String, isContact: Boolean)

    @Query("UPDATE peers SET isBlocked = :isBlocked WHERE peerId = :peerId")
    suspend fun setBlockedStatus(peerId: String, isBlocked: Boolean)
}

@Dao
interface ChatRoomDao {
    @Query("SELECT * FROM chat_rooms ORDER BY participantCount DESC")
    fun getAllRooms(): Flow<List<ChatRoomEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoom(room: ChatRoomEntity)

    @Query("UPDATE chat_rooms SET isJoined = :isJoined WHERE id = :roomId")
    suspend fun setJoinedStatus(roomId: String, isJoined: Boolean)
}

@Dao
interface NetworkEventDao {
    @Query("SELECT * FROM network_events ORDER BY timestampEpochMs DESC LIMIT 50")
    fun getRecentEvents(): Flow<List<NetworkEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: NetworkEventEntity)
}

@Dao
interface OutboundQueueDao {
    @Query("SELECT * FROM outbound_packets WHERE state != 'DELIVERED' AND state != 'FAILED' ORDER BY createdAtEpochMs ASC")
    suspend fun getAllPendingPackets(): List<OutboundPacketEntity>

    @Query("SELECT * FROM outbound_packets WHERE state != 'DELIVERED' AND state != 'FAILED' AND nextRetryEpochMs <= :nowEpochMs ORDER BY createdAtEpochMs ASC")
    suspend fun getPacketsDueForRetry(nowEpochMs: Long): List<OutboundPacketEntity>

    @Query("SELECT COUNT(*) FROM outbound_packets WHERE state != 'DELIVERED' AND state != 'FAILED'")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM outbound_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun getPacket(packetId: Long): OutboundPacketEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPacket(packet: OutboundPacketEntity)

    @Update
    suspend fun updatePacket(packet: OutboundPacketEntity)

    @Query("UPDATE outbound_packets SET state = :state, retryCount = :retryCount, nextRetryEpochMs = :nextRetryEpochMs WHERE packetId = :packetId")
    suspend fun updateRetryProgress(packetId: Long, state: String, retryCount: Int, nextRetryEpochMs: Long)

    @Query("UPDATE outbound_packets SET state = :state WHERE packetId = :packetId")
    suspend fun updateState(packetId: Long, state: String)

    @Query("UPDATE outbound_packets SET state = 'DELIVERED' WHERE packetId = :packetId")
    suspend fun markDelivered(packetId: Long)

    @Query("DELETE FROM outbound_packets WHERE packetId = :packetId")
    suspend fun deletePacket(packetId: Long)

    @Query("DELETE FROM outbound_packets WHERE state = 'DELIVERED' OR state = 'FAILED'")
    suspend fun pruneCompleted()

    @Query("DELETE FROM outbound_packets WHERE createdAtEpochMs < :olderThanEpochMs")
    suspend fun pruneOldPackets(olderThanEpochMs: Long): Int
}

@Dao
interface RelayQueueDao {
    @Query("SELECT * FROM relay_packets WHERE destinationHex = :destinationHex AND expiresEpochMs > :nowEpochMs")
    suspend fun getPacketsForDestination(destinationHex: Long, nowEpochMs: Long): List<RelayPacketEntity>

    @Query("SELECT * FROM relay_packets WHERE expiresEpochMs > :nowEpochMs ORDER BY receivedEpochMs ASC")
    suspend fun getAllActiveRelayPackets(nowEpochMs: Long): List<RelayPacketEntity>

    @Query("SELECT COUNT(*) FROM relay_packets")
    suspend fun getCount(): Int

    @Query("SELECT * FROM relay_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun getPacket(packetId: Long): RelayPacketEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelayPacket(packet: RelayPacketEntity)

    @Query("DELETE FROM relay_packets WHERE packetId = :packetId")
    suspend fun deleteRelayPacket(packetId: Long)

    @Query("DELETE FROM relay_packets WHERE expiresEpochMs <= :nowEpochMs")
    suspend fun pruneExpired(nowEpochMs: Long): Int

    @Query("DELETE FROM relay_packets WHERE packetId IN (SELECT packetId FROM relay_packets ORDER BY expiresEpochMs ASC LIMIT :count)")
    suspend fun evictOldest(count: Int): Int
}

