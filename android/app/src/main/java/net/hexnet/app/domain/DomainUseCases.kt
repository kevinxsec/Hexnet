package net.hexnet.app.domain

import net.hexnet.app.core.model.Message
import net.hexnet.app.core.model.MessageDeliveryState
import net.hexnet.app.core.model.MeshPacket
import java.util.UUID

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}

class ValidateUsernameUseCase {
    private val reservedUsernames = setOf("admin", "root", "hexnet", "broadcast", "mesh", "system", "anonymous")

    fun execute(username: String): ValidationResult {
        val trimmed = username.trim()
        if (trimmed.length < 3) {
            return ValidationResult.Invalid("Username must be at least 3 characters long")
        }
        if (trimmed.length > 20) {
            return ValidationResult.Invalid("Username must not exceed 20 characters")
        }
        if (!trimmed.matches(Regex("^[a-z0-9_]+$"))) {
            return ValidationResult.Invalid("Only lowercase letters, numbers, and underscores allowed")
        }
        if (trimmed.startsWith("_") || trimmed.endsWith("_")) {
            return ValidationResult.Invalid("Username cannot start or end with an underscore")
        }
        if (reservedUsernames.contains(trimmed.lowercase())) {
            return ValidationResult.Invalid("'$trimmed' is reserved by the network protocol")
        }
        return ValidationResult.Valid
    }
}

class GenerateHexIdentityUseCase {
    fun execute(username: String): Pair<String, String> {
        val seed = "$username-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val bytes = seed.toByteArray()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(bytes)
        val hexAddress = "0x" + hashBytes.joinToString("") { "%02x".format(it) }.take(16)
        val keyFingerprint = "HEX:" + hashBytes.takeLast(8).joinToString(":") { "%02X".format(it) }
        return Pair(hexAddress, keyFingerprint)
    }
}

class MeshRoutingEngine(
    private val defaultMaxHops: Int = 5
) {
    private val seenPacketHashes = LinkedHashSet<String>()
    private val maxCacheSize = 1000

    @Synchronized
    fun processPacket(packet: MeshPacket, localHexAddress: String): RoutingDecision {
        // 1. Check if packet already processed (Loop / Flood suppression)
        if (seenPacketHashes.contains(packet.packetHash)) {
            return RoutingDecision.DropDuplicate
        }
        
        // Add to cache with LRU eviction
        seenPacketHashes.add(packet.packetHash)
        if (seenPacketHashes.size > maxCacheSize) {
            val first = seenPacketHashes.first()
            seenPacketHashes.remove(first)
        }

        // 2. Check if packet reached destination
        if (packet.destinationHex == localHexAddress || packet.destinationHex == "0xBROADCAST") {
            return RoutingDecision.AcceptLocal(packet)
        }

        // 3. Check TTL / Hop Limit
        if (packet.hopCount >= packet.ttl || packet.hopCount >= defaultMaxHops) {
            return RoutingDecision.DropHopLimitExceeded
        }

        // 4. Eligible for relay forwarding
        val forwardedPacket = packet.copy(hopCount = packet.hopCount + 1)
        return RoutingDecision.Forward(forwardedPacket)
    }

    sealed class RoutingDecision {
        data class AcceptLocal(val packet: MeshPacket) : RoutingDecision()
        data class Forward(val packet: MeshPacket) : RoutingDecision()
        object DropDuplicate : RoutingDecision()
        object DropHopLimitExceeded : RoutingDecision()
    }
}
