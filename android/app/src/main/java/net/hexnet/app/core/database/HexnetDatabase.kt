package net.hexnet.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import net.hexnet.app.core.database.dao.*
import net.hexnet.app.core.database.entity.*

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UserProfileEntity::class,
        MessageEntity::class,
        ConversationEntity::class,
        PeerEntity::class,
        ChatRoomEntity::class,
        NetworkEventEntity::class,
        OutboundPacketEntity::class,
        RelayPacketEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(HexnetTypeConverters::class)
abstract class HexnetDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun peerDao(): PeerDao
    abstract fun chatRoomDao(): ChatRoomDao
    abstract fun networkEventDao(): NetworkEventDao
    abstract fun outboundQueueDao(): OutboundQueueDao
    abstract fun relayQueueDao(): RelayQueueDao

    companion object {
        @Volatile
        private var INSTANCE: HexnetDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS outbound_packets (
                        packetId INTEGER PRIMARY KEY NOT NULL,
                        destinationHex INTEGER NOT NULL,
                        packetType INTEGER NOT NULL,
                        rawPacketBytes BLOB NOT NULL,
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        maxRetries INTEGER NOT NULL DEFAULT 3,
                        nextRetryEpochMs INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        state TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS relay_packets (
                        packetId INTEGER PRIMARY KEY NOT NULL,
                        destinationHex INTEGER NOT NULL,
                        sourceHex INTEGER NOT NULL,
                        rawPacketBytes BLOB NOT NULL,
                        expiresEpochMs INTEGER NOT NULL,
                        receivedEpochMs INTEGER NOT NULL,
                        relayAttempts INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages (conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_packets_retry ON outbound_packets (state, nextRetryEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_relay_packets_dest_expires ON relay_packets (destinationHex, expiresEpochMs)")
            }
        }

        fun getInstance(context: Context): HexnetDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HexnetDatabase::class.java,
                    "hexnet_mesh.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                 .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                 .fallbackToDestructiveMigration()
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
