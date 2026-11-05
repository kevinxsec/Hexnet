package net.hexnet.app.core.database

import androidx.room.TypeConverter
import net.hexnet.app.core.model.ConversationType
import net.hexnet.app.core.model.MessageDeliveryState
import net.hexnet.app.core.model.NetworkEventType
import net.hexnet.app.core.model.PeerStatus

class HexnetTypeConverters {
    @TypeConverter
    fun fromDeliveryState(state: MessageDeliveryState): String = state.name

    @TypeConverter
    fun toDeliveryState(value: String): MessageDeliveryState = MessageDeliveryState.valueOf(value)

    @TypeConverter
    fun fromConversationType(type: ConversationType): String = type.name

    @TypeConverter
    fun toConversationType(value: String): ConversationType = ConversationType.valueOf(value)

    @TypeConverter
    fun fromPeerStatus(status: PeerStatus): String = status.name

    @TypeConverter
    fun toPeerStatus(value: String): PeerStatus = PeerStatus.valueOf(value)

    @TypeConverter
    fun fromNetworkEventType(type: NetworkEventType): String = type.name

    @TypeConverter
    fun toNetworkEventType(value: String): NetworkEventType = NetworkEventType.valueOf(value)
}
