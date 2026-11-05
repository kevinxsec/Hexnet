package net.hexnet.app.core.protocol

/**
 * Discriminator byte identifying the nature and routing behavior of a Hexnet packet.
 */
enum class PacketType(val code: Byte) {
    PEER_ANNOUNCE(0x01),
    DIRECT_MESSAGE(0x02),
    ROOM_BROADCAST(0x03),
    LINK_ACK(0x04),
    ROUTE_REQUEST(0x05),
    ROUTE_REPLY(0x06),
    NOISE_HANDSHAKE(0x07),
    DELIVERY_ACK(0x08);

    companion object {
        private val codeMap = values().associateBy { it.code }

        fun fromCode(code: Byte): PacketType? = codeMap[code]
    }
}

/**
 * 1-byte bitfield flags governing packet processing.
 */
object PacketFlags {
    const val NONE: Byte = 0x00
    const val COMPRESSED: Byte = 0x01     // Payload is compressed with Deflate
    const val ENCRYPTED: Byte = 0x02      // Payload is end-to-end encrypted
    const val ACK_REQUESTED: Byte = 0x04  // Sender requests link-level ACK
    const val FRAGMENTED: Byte = 0x08     // Carries a fragment frame
    const val PRIORITY: Byte = 0x10       // Expedited forwarding (e.g. emergency)

    fun hasFlag(flags: Byte, flag: Byte): Boolean = (flags.toInt() and flag.toInt()) == flag.toInt()
    fun setFlag(flags: Byte, flag: Byte): Byte = (flags.toInt() or flag.toInt()).toByte()
}
