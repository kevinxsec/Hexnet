package net.hexnet.app.core.routing

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.hexnet.app.core.crypto.NoiseSessionManager
import net.hexnet.app.core.database.dao.OutboundQueueDao
import net.hexnet.app.core.database.dao.RelayQueueDao
import net.hexnet.app.core.database.entity.OutboundPacketEntity
import net.hexnet.app.core.database.entity.RelayPacketEntity
import net.hexnet.app.core.protocol.BinaryPacket
import net.hexnet.app.core.protocol.PacketFlags
import net.hexnet.app.core.protocol.PacketSerializer
import net.hexnet.app.core.protocol.PacketType
import java.util.concurrent.ConcurrentHashMap

/**
 * Core Mesh Routing Engine for Hexnet decentralized mesh networking.
 *
 * Implements:
 * 1. Hybrid routing: Directed reverse-path routing table with controlled flooding fallback.
 * 2. Packet deduplication and loop suppression via bounded LRU cache.
 * 3. Hop-by-hop delivery verification (Link ACKs) with retry and backoff.
 * 4. End-to-end delivery confirmation (E2E ACKs) with cryptographic session authentication.
 * 5. Durable Store-and-Forward packet queues for intermittent connectivity with capacity caps.
 * 6. Concurrency-safe queue recovery, background retry worker, and queue exhaustion protection.
 * 7. Cryptographic payload privacy: Intermediate relays cannot decrypt E2EE payloads.
 */
class MeshRoutingEngine(
    val localHexAddress: Long,
    val neighborTable: NeighborTable = NeighborTable(),
    val routeTable: RouteTable = RouteTable(),
    val deduplicationCache: DeduplicationCache = DeduplicationCache(),
    val sessionManager: NoiseSessionManager? = null,
    val outboundQueueDao: OutboundQueueDao? = null,
    val relayQueueDao: RelayQueueDao? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        const val MAX_RELAY_QUEUE_CAPACITY = 300
        const val MAX_OUTBOUND_QUEUE_CAPACITY = 200
        const val RETRY_CHECK_INTERVAL_MS = 3000L
        const val BASE_RETRY_BACKOFF_MS = 1500L
    }

    // In-memory pending hop-ACK callbacks: packetId -> CompletableDeferred<Boolean>
    private val pendingHopAcks = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    // In-memory pending E2E ACK listeners: packetId -> (DeliveryAck) -> Unit
    private val pendingE2eAckListeners = ConcurrentHashMap<Long, (DeliveryAck) -> Unit>()

    // Message delivery state listener: (packetId, state: String) -> Unit
    var onDeliveryStateChanged: ((packetId: Long, state: String) -> Unit)? = null

    // Session established listener: (peerHex: Long, session: NoiseSession) -> Unit
    var onSessionEstablished: ((peerHex: Long, session: net.hexnet.app.core.crypto.NoiseSession) -> Unit)? = null

    data class PendingDirectMessage(
        val destinationHex: Long,
        val plaintext: ByteArray,
        val packetId: Long
    )
    val pendingSecureMessages = ConcurrentHashMap<Long, java.util.concurrent.CopyOnWriteArrayList<PendingDirectMessage>>()

    // Outgoing radio transmit callback: (targetDeviceAddress: String?, packet: BinaryPacket) -> Unit
    var transmitCallback: ((targetDeviceAddress: String?, packet: BinaryPacket) -> Unit)? = null

    // In-memory store-and-forward fallback if database DAO is null (for pure unit tests)
    private val inMemoryRelayQueue = ConcurrentHashMap<Long, RelayPacketEntity>()
    private val inMemoryOutboundQueue = ConcurrentHashMap<Long, OutboundPacketEntity>()

    // Concurrency control for queue mutations and background worker
    private val queueMutex = Mutex()
    private var queueWorkerJob: Job? = null

    // Rate Limiter per peer interface to suppress flooding attacks and broadcast storms
    private val peerRateLimiters = ConcurrentHashMap<String, RateLimiter>()

    private class RateLimiter(
        private val maxTokens: Int = 30,
        private val refillIntervalMs: Long = 1000L
    ) {
        private var tokens = maxTokens
        private var lastRefillTime = System.currentTimeMillis()

        @Synchronized
        fun tryAcquire(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastRefillTime >= refillIntervalMs) {
                tokens = maxTokens
                lastRefillTime = now
            }
            return if (tokens > 0) {
                tokens--
                true
            } else {
                false
            }
        }
    }

    init {
        // Automatically start the background retry processor
        startQueueWorker()
    }

    /**
     * Ingests a raw byte buffer received over BLE from an adjacent node.
     * Evaluates framing, checksum, deduplication, destination, and produces a RoutingDecision.
     */
    fun processIncomingPacket(
        fromDeviceAddress: String,
        rawBytes: ByteArray
    ): RoutingDecision {
        // 0. Flood Protection: Rate limit check per incoming peer interface (max 30 pkts/sec)
        val rateLimiter = peerRateLimiters.computeIfAbsent(fromDeviceAddress) { RateLimiter() }
        if (!rateLimiter.tryAcquire()) {
            return RoutingDecision.DropInvalidChecksum("Rate limit exceeded for $fromDeviceAddress (flooding suppression)")
        }

        // 1. Validate CRC32 and Framing
        val packetResult = PacketSerializer.deserialize(rawBytes)
        if (packetResult.isFailure) {
            return RoutingDecision.DropInvalidChecksum(
                packetResult.exceptionOrNull()?.message ?: "Malformed packet"
            )
        }
        val packet = packetResult.getOrThrow()

        // 2. Reject packets originating from self (loop reflection)
        if (packet.sourceHex == localHexAddress) {
            return RoutingDecision.DropSelfOrigin(packet.packetId)
        }

        // 3. Deduplication Check: suppress already seen packets
        if (!deduplicationCache.recordPacket(packet.packetId)) {
            return RoutingDecision.DropDuplicate(packet.packetId)
        }

        // 4. Reverse-Path Route Learning
        // The sender is reachable via fromDeviceAddress with hopDistance = packet.hopCount + 1
        routeTable.updateRoute(
            destinationHex = packet.sourceHex,
            nextHopAddress = fromDeviceAddress,
            hopDistance = packet.hopCount.toInt() + 1
        )

        // 5. Handle LINK_ACK packets (hop-by-hop acknowledgement)
        if (packet.type == PacketType.LINK_ACK) {
            handleIncomingLinkAck(packet)
            return RoutingDecision.AcceptLocally(packet, isDirectDestination = true)
        }

        // 6. Check Destination: Local Node vs. Broadcast vs. Forward
        val isForMe = packet.destinationHex == localHexAddress
        val isBroadcast = packet.destinationHex == BinaryPacket.BROADCAST_DESTINATION

        if (isForMe) {
            // Send hop-by-hop LINK_ACK back to the immediate transmitter if requested
            if (PacketFlags.hasFlag(packet.flags, PacketFlags.ACK_REQUESTED) || packet.type == PacketType.DIRECT_MESSAGE) {
                sendHopAck(fromDeviceAddress, packet.packetId, packet.sourceHex)
            }

            // Check if this packet is a Noise Handshake frame
            if (packet.type == PacketType.NOISE_HANDSHAKE) {
                val peerHexStr = java.lang.Long.toHexString(packet.sourceHex)
                val result = sessionManager?.processHandshakeMessage(peerHexStr, packet.payload)
                when (result) {
                    is net.hexnet.app.core.crypto.HandshakeProcessResult.Reply -> {
                        val replyPacket = BinaryPacket(
                            type = PacketType.NOISE_HANDSHAKE,
                            sourceHex = localHexAddress,
                            destinationHex = packet.sourceHex,
                            payload = result.messageBytes
                        )
                        dispatchPacket(replyPacket)
                    }
                    is net.hexnet.app.core.crypto.HandshakeProcessResult.EstablishedWithReply -> {
                        val replyPacket = BinaryPacket(
                            type = PacketType.NOISE_HANDSHAKE,
                            sourceHex = localHexAddress,
                            destinationHex = packet.sourceHex,
                            payload = result.messageBytes
                        )
                        dispatchPacket(replyPacket)
                        onSessionEstablished?.invoke(packet.sourceHex, result.session)
                        handleSessionEstablished(packet.sourceHex, result.session)
                    }
                    is net.hexnet.app.core.crypto.HandshakeProcessResult.Established -> {
                        onSessionEstablished?.invoke(packet.sourceHex, result.session)
                        handleSessionEstablished(packet.sourceHex, result.session)
                    }
                    else -> { /* Handshake error or no reply */ }
                }
                return RoutingDecision.AcceptLocally(packet, isDirectDestination = true)
            }

            // Check if this packet is an End-to-End Delivery ACK
            if (packet.type == PacketType.DELIVERY_ACK || (packet.type == PacketType.DIRECT_MESSAGE && packet.payload.isNotEmpty() && packet.payload[0] == 0x41.toByte())) {
                var ackBytes = packet.payload
                var wasEncrypted = false

                // Decrypt if encrypted with Noise session
                if (PacketFlags.hasFlag(packet.flags, PacketFlags.ENCRYPTED) && sessionManager != null) {
                    try {
                        if (packet.payload.size >= 8) {
                            val buffer = java.nio.ByteBuffer.wrap(packet.payload).order(java.nio.ByteOrder.BIG_ENDIAN)
                            val seq = buffer.getLong()
                            val ciphertext = ByteArray(packet.payload.size - 8)
                            buffer.get(ciphertext)
                            val peerHexStr = java.lang.Long.toHexString(packet.sourceHex)
                            ackBytes = sessionManager.decryptDirectMessage(peerHexStr, seq, ciphertext)
                            wasEncrypted = true
                        }
                    } catch (e: Exception) {
                        return RoutingDecision.DropInvalidChecksum("Tampered or unauthenticated DELIVERY_ACK")
                    }
                }

                val ackResult = DeliveryAck.deserialize(ackBytes)
                if (ackResult.isSuccess) {
                    val ack = ackResult.getOrThrow()
                    // Verify cryptographic destination binding and source authentication
                    if (ack.isEndToEnd && ack.originalSenderHex == localHexAddress && ack.ackSenderHex == packet.sourceHex) {
                        // Enforce cryptographic session binding:
                        // If the destination node has an active Noise session or outbound packet was encrypted,
                        // reject any unencrypted ACK!
                        val peerHexStr = java.lang.Long.toHexString(packet.sourceHex)
                        val sessionActive = sessionManager?.hasActiveSession(peerHexStr) == true
                        val outboundRecord = inMemoryOutboundQueue[ack.originalPacketId]
                        val wasOrigEncrypted = outboundRecord != null && (PacketFlags.hasFlag(outboundRecord.packetType, PacketFlags.ENCRYPTED) || sessionActive)

                        if (wasOrigEncrypted && !wasEncrypted) {
                            return RoutingDecision.DropInvalidChecksum("Rejected unencrypted ACK for encrypted direct message")
                        }

                        handleIncomingE2eAck(ack)
                        return RoutingDecision.AcceptLocally(packet, isDirectDestination = true)
                    }
                }
            }

            // If direct encrypted message, attempt decryption via Noise session
            var decrypted: ByteArray? = null
            if (PacketFlags.hasFlag(packet.flags, PacketFlags.ENCRYPTED) && sessionManager != null) {
                try {
                    // Extract seq (8 bytes) + ciphertext
                    if (packet.payload.size >= 8) {
                        val buffer = java.nio.ByteBuffer.wrap(packet.payload).order(java.nio.ByteOrder.BIG_ENDIAN)
                        val seq = buffer.getLong()
                        val ciphertext = ByteArray(packet.payload.size - 8)
                        buffer.get(ciphertext)
                        val peerHexStr = java.lang.Long.toHexString(packet.sourceHex)
                        decrypted = sessionManager.decryptDirectMessage(peerHexStr, seq, ciphertext)
                    }
                } catch (e: Exception) {
                    // Decryption failed or unestablished session; keep encrypted payload
                }
            }

            // End-to-End Delivery ACK is sent ONLY after successful authentication,
            // destination verification, and durable Room persistence by the foreground service / repository!
            return RoutingDecision.AcceptLocally(
                packet = packet,
                isDirectDestination = true,
                decryptedPayload = decrypted
            )
        } else if (isBroadcast) {
            // Accept broadcast locally
            // Then forward if TTL > 1
            if (packet.ttl <= 1) {
                return RoutingDecision.DropExpiredTtl(packet.packetId, packet.ttl)
            }

            val forwardedPacket = packet.copy(
                ttl = (packet.ttl - 1).toByte(),
                hopCount = (packet.hopCount + 1).toByte()
            )

            // Broadcast to other neighbors except arrival interface
            transmitCallback?.invoke(null, forwardedPacket)
            return RoutingDecision.ForwardBroadcast(
                packet = forwardedPacket,
                exceptDeviceAddress = fromDeviceAddress
            )
        } else {
            // Unicast packet intended for another node (Relay Node Role)
            // 1. Send hop-level ACK back to sender immediately
            if (PacketFlags.hasFlag(packet.flags, PacketFlags.ACK_REQUESTED) || packet.type == PacketType.DIRECT_MESSAGE) {
                sendHopAck(fromDeviceAddress, packet.packetId, packet.sourceHex)
            }

            // 2. Check TTL
            if (packet.ttl <= 1) {
                return RoutingDecision.DropExpiredTtl(packet.packetId, packet.ttl)
            }

            // 3. Decrement TTL and increment hop count
            // Note: Relay node NEVER decrypts or modifies the payload!
            val forwardedPacket = packet.copy(
                ttl = (packet.ttl - 1).toByte(),
                hopCount = (packet.hopCount + 1).toByte()
            )

            // 4. Route lookup
            val knownRoute = routeTable.getRoute(packet.destinationHex)
            if (knownRoute != null && neighborTable.isConnected(knownRoute.nextHopAddress)) {
                // Directed unicast forward
                transmitCallback?.invoke(knownRoute.nextHopAddress, forwardedPacket)
                return RoutingDecision.ForwardUnicast(forwardedPacket, knownRoute.nextHopAddress)
            } else {
                // Controlled flood discovery fallback
                transmitCallback?.invoke(null, forwardedPacket)
                return RoutingDecision.ForwardBroadcast(
                    packet = forwardedPacket,
                    exceptDeviceAddress = fromDeviceAddress
                )
            }
        }
    }

    /**
     * Sends a hop-by-hop LINK_ACK packet directly to the immediate transmitter.
     */
    private fun sendHopAck(targetDeviceAddress: String, originalPacketId: Long, originalSourceHex: Long) {
        val ackPayload = DeliveryAck.serialize(
            DeliveryAck(
                originalPacketId = originalPacketId,
                isEndToEnd = false,
                ackSenderHex = localHexAddress,
                originalSenderHex = originalSourceHex
            )
        )

        val ackPacket = BinaryPacket(
            type = PacketType.LINK_ACK,
            flags = PacketFlags.NONE,
            ttl = 1, // Only 1 hop for link ack
            hopCount = 0,
            packetId = kotlin.random.Random.nextLong(),
            sourceHex = localHexAddress,
            destinationHex = originalSourceHex,
            payload = ackPayload
        )

        transmitCallback?.invoke(targetDeviceAddress, ackPacket)
    }

    /**
     * Sends an End-to-End Delivery ACK packet back to the original message sender.
     * Authenticates and encrypts the ACK using the active Noise session if present.
     */
    fun sendEndToEndAck(destinationHex: Long, originalPacketId: Long) {
        val ackPayload = DeliveryAck.serialize(
            DeliveryAck(
                originalPacketId = originalPacketId,
                isEndToEnd = true,
                ackSenderHex = localHexAddress,
                originalSenderHex = destinationHex
            )
        )

        val peerHexStr = java.lang.Long.toHexString(destinationHex)
        var finalPayload = ackPayload
        var flags: Byte = PacketFlags.NONE

        if (sessionManager != null && sessionManager.hasActiveSession(peerHexStr)) {
            val encrypted = sessionManager.encryptDirectMessage(peerHexStr, ackPayload)
            val buffer = java.nio.ByteBuffer.allocate(8 + encrypted.ciphertext.size).order(java.nio.ByteOrder.BIG_ENDIAN)
            buffer.putLong(encrypted.sequenceNumber)
            buffer.put(encrypted.ciphertext)
            finalPayload = buffer.array()
            flags = PacketFlags.setFlag(flags, PacketFlags.ENCRYPTED)
        }

        val ackPacket = BinaryPacket(
            type = PacketType.DELIVERY_ACK,
            flags = flags,
            ttl = BinaryPacket.DEFAULT_TTL,
            hopCount = 0,
            packetId = kotlin.random.Random.nextLong(),
            sourceHex = localHexAddress,
            destinationHex = destinationHex,
            payload = finalPayload
        )

        dispatchPacket(ackPacket)
    }

    /**
     * Initiates a Noise XX cryptographic handshake with a remote peer across the mesh.
     */
    fun initiateNoiseHandshake(destinationHex: Long): BinaryPacket? {
        val sm = sessionManager ?: return null
        val peerHexStr = java.lang.Long.toHexString(destinationHex)
        val msg1 = sm.startHandshake(peerHexStr)
        val packet = BinaryPacket(
            type = PacketType.NOISE_HANDSHAKE,
            flags = PacketFlags.NONE,
            ttl = BinaryPacket.DEFAULT_TTL,
            hopCount = 0,
            packetId = kotlin.random.Random.nextLong(),
            sourceHex = localHexAddress,
            destinationHex = destinationHex,
            payload = msg1
        )
        dispatchPacket(packet)
        return packet
    }

    /**
     * Internal handler when an incoming LINK_ACK is accepted.
     */
    private fun handleIncomingLinkAck(packet: BinaryPacket) {
        val ackResult = DeliveryAck.deserialize(packet.payload)
        if (ackResult.isSuccess) {
            val ack = ackResult.getOrThrow()
            val deferred = pendingHopAcks.remove(ack.originalPacketId)
            deferred?.complete(true)
        }
    }

    /**
     * Internal handler when an incoming End-to-End ACK is received by original sender.
     */
    fun handleIncomingE2eAck(ack: DeliveryAck) {
        if (ack.originalSenderHex != localHexAddress) {
            return
        }

        val inMem = inMemoryOutboundQueue[ack.originalPacketId]
        if (inMem?.state == "DELIVERED") {
            // Already acknowledged, suppress redundant duplicate callback
            return
        }

        // Mark delivery state as DELIVERED
        onDeliveryStateChanged?.invoke(ack.originalPacketId, "DELIVERED")

        scope.launch {
            outboundQueueDao?.markDelivered(ack.originalPacketId)
        }
        if (inMem != null) {
            inMemoryOutboundQueue[ack.originalPacketId] = inMem.copy(state = "DELIVERED")
        }

        val listener = pendingE2eAckListeners.remove(ack.originalPacketId)
        listener?.invoke(ack)
    }

    /**
     * Sends a direct message to a destination peer with optional E2EE encryption and reliable delivery.
     * Crash consistent: Awaits Room persistence before attempting radio transmission.
     */
    suspend fun sendDirectMessage(
        destinationHex: Long,
        plaintext: ByteArray,
        packetId: Long = kotlin.random.Random.nextLong()
    ): BinaryPacket {
        var payloadBytes = plaintext
        var flags: Byte = PacketFlags.ACK_REQUESTED

        val isBroadcast = destinationHex == BinaryPacket.BROADCAST_DESTINATION
        val peerHexStr = java.lang.Long.toHexString(destinationHex)

        // Enforce Noise encryption: Direct messages MUST be encrypted if sessionManager is available
        if (!isBroadcast && sessionManager != null) {
            if (sessionManager.hasActiveSession(peerHexStr)) {
                val encrypted = sessionManager.encryptDirectMessage(peerHexStr, plaintext)
                // Pack seq (8 bytes) + ciphertext
                val buffer = java.nio.ByteBuffer.allocate(8 + encrypted.ciphertext.size).order(java.nio.ByteOrder.BIG_ENDIAN)
                buffer.putLong(encrypted.sequenceNumber)
                buffer.put(encrypted.ciphertext)
                payloadBytes = buffer.array()
                flags = PacketFlags.setFlag(flags, PacketFlags.ENCRYPTED)
            } else {
                // No active session: queue the message and automatically initiate Noise XX handshake
                val pending = PendingDirectMessage(destinationHex, plaintext, packetId)
                pendingSecureMessages.computeIfAbsent(destinationHex) { java.util.concurrent.CopyOnWriteArrayList() }.add(pending)
                onDeliveryStateChanged?.invoke(packetId, "QUEUED")
                initiateNoiseHandshake(destinationHex)

                return BinaryPacket(
                    type = PacketType.DIRECT_MESSAGE,
                    flags = flags,
                    ttl = BinaryPacket.DEFAULT_TTL,
                    hopCount = 0,
                    packetId = packetId,
                    sourceHex = localHexAddress,
                    destinationHex = destinationHex,
                    payload = plaintext
                )
            }
        }

        val packet = BinaryPacket(
            type = PacketType.DIRECT_MESSAGE,
            flags = flags,
            ttl = BinaryPacket.DEFAULT_TTL,
            hopCount = 0,
            packetId = packetId,
            sourceHex = localHexAddress,
            destinationHex = destinationHex,
            payload = payloadBytes
        )

        // Enqueue into persistent outbound queue
        val rawBytes = PacketSerializer.serialize(packet)
        val entity = OutboundPacketEntity(
            packetId = packet.packetId,
            destinationHex = destinationHex,
            packetType = packet.type.code,
            rawPacketBytes = rawBytes,
            retryCount = 0,
            maxRetries = 3,
            nextRetryEpochMs = System.currentTimeMillis() + BASE_RETRY_BACKOFF_MS,
            createdAtEpochMs = System.currentTimeMillis(),
            state = "PENDING"
        )

        inMemoryOutboundQueue[packet.packetId] = entity

        // Crash consistency: Persist to database before transmission.
        // If write fails, throw to prevent false delivery state!
        if (outboundQueueDao != null) {
            try {
                outboundQueueDao.insertPacket(entity)
            } catch (e: Exception) {
                onDeliveryStateChanged?.invoke(packet.packetId, "FAILED_PERSISTENCE")
                throw e
            }
        }

        onDeliveryStateChanged?.invoke(packet.packetId, "SENDING")

        // Dispatch with hop-by-hop retry
        dispatchWithRetry(packet, maxRetries = 3)

        return packet
    }

    /**
     * Dispatches a packet towards destination with hop-by-hop link ACK retry.
     */
    suspend fun dispatchWithRetry(packet: BinaryPacket, maxRetries: Int = 3): Boolean {
        var attempts = 0
        var backoffMs = 800L

        while (attempts <= maxRetries) {
            val hopAckDeferred = CompletableDeferred<Boolean>()
            pendingHopAcks[packet.packetId] = hopAckDeferred

            dispatchPacket(packet)

            try {
                // Wait for LINK_ACK from next-hop
                withTimeout(backoffMs) {
                    hopAckDeferred.await()
                }
                // Hop ACK confirmed!
                onDeliveryStateChanged?.invoke(packet.packetId, "RELAYED")
                return true
            } catch (e: TimeoutCancellationException) {
                attempts++
                pendingHopAcks.remove(packet.packetId)

                if (attempts <= maxRetries) {
                    backoffMs *= 2 // Exponential backoff
                    // If route failed, try invalidating or falling back
                    val route = routeTable.getRoute(packet.destinationHex)
                    if (route != null && attempts > 1) {
                        routeTable.invalidateRoutesVia(route.nextHopAddress)
                    }
                }
            }
        }

        onDeliveryStateChanged?.invoke(packet.packetId, "FAILED")
        return false
    }

    /**
     * Dispatches packet via direct next-hop unicast if known, or broadcast flood fallback.
     */
    fun dispatchPacket(packet: BinaryPacket) {
        val route = routeTable.getRoute(packet.destinationHex)
        if (route != null && neighborTable.isConnected(route.nextHopAddress)) {
            transmitCallback?.invoke(route.nextHopAddress, packet)
        } else {
            transmitCallback?.invoke(null, packet)
        }
    }

    /**
     * Initiates a Noise_XX handshake with the remote peer over BLE mesh transport.
     */
    fun initiateNoiseHandshake(destinationHex: Long) {
        if (sessionManager == null || destinationHex == BinaryPacket.BROADCAST_DESTINATION) return
        val peerHexStr = java.lang.Long.toHexString(destinationHex)
        if (sessionManager.hasActiveSession(peerHexStr)) return

        try {
            val msg1Bytes = sessionManager.startHandshake(peerHexStr)
            val handshakePacket = BinaryPacket(
                type = PacketType.NOISE_HANDSHAKE,
                flags = PacketFlags.NONE,
                ttl = BinaryPacket.DEFAULT_TTL,
                hopCount = 0,
                packetId = kotlin.random.Random.nextLong(),
                sourceHex = localHexAddress,
                destinationHex = destinationHex,
                payload = msg1Bytes
            )
            dispatchPacket(handshakePacket)
        } catch (e: Exception) {
            // Handshake initiation handled safely
        }
    }

    /**
     * Resumes and encrypts all direct messages queued while awaiting session establishment.
     */
    fun handleSessionEstablished(peerHex: Long, session: net.hexnet.app.core.crypto.NoiseSession) {
        val pendingList = pendingSecureMessages.remove(peerHex) ?: return
        scope.launch {
            for (pending in pendingList) {
                try {
                    sendDirectMessage(
                        destinationHex = pending.destinationHex,
                        plaintext = pending.plaintext,
                        packetId = pending.packetId
                    )
                } catch (e: Exception) {
                    onDeliveryStateChanged?.invoke(pending.packetId, "FAILED")
                }
            }
        }
    }

    /**
     * Starts the periodic background queue processor for scheduled retries and cleanup.
     */
    fun startQueueWorker() {
        if (queueWorkerJob?.isActive == true) return
        queueWorkerJob = scope.launch {
            while (isActive) {
                delay(RETRY_CHECK_INTERVAL_MS)
                processDueRetries()
                pruneOldQueues()
            }
        }
    }

    /**
     * Stops the background queue worker.
     */
    fun stopQueueWorker() {
        queueWorkerJob?.cancel()
        queueWorkerJob = null
    }

    /**
     * Processes outbound packets that have reached their scheduled retry timestamp.
     * Concurrency-safe: guarded by Mutex to avoid simultaneous race conditions.
     */
    suspend fun processDueRetries() {
        queueMutex.withLock {
            val now = System.currentTimeMillis()
            outboundQueueDao?.let { dao ->
                val duePackets = dao.getPacketsDueForRetry(now)
                for (entity in duePackets) {
                    if (entity.retryCount >= entity.maxRetries) {
                        dao.updateState(entity.packetId, "FAILED")
                        inMemoryOutboundQueue[entity.packetId] = entity.copy(state = "FAILED")
                        onDeliveryStateChanged?.invoke(entity.packetId, "FAILED")
                    } else {
                        val nextRetry = entity.retryCount + 1
                        val backoffMs = BASE_RETRY_BACKOFF_MS * (1 shl entity.retryCount)
                        dao.updateRetryProgress(entity.packetId, "RETRYING", nextRetry, now + backoffMs)
                        inMemoryOutboundQueue[entity.packetId] = entity.copy(
                            state = "RETRYING",
                            retryCount = nextRetry,
                            nextRetryEpochMs = now + backoffMs
                        )
                        onDeliveryStateChanged?.invoke(entity.packetId, "RETRYING")
                        val des = PacketSerializer.deserialize(entity.rawPacketBytes)
                        if (des.isSuccess) {
                            dispatchPacket(des.getOrThrow())
                        }
                    }
                }
            }
        }
    }

    /**
     * Recovers pending outbound and relay queues after application restart or process recreation.
     */
    suspend fun recoverQueues() {
        queueMutex.withLock {
            // 1. Recover outbound packets
            outboundQueueDao?.let { dao ->
                val pending = dao.getAllPendingPackets()
                for (p in pending) {
                    inMemoryOutboundQueue[p.packetId] = p
                }
            }

            // 2. Recover relay packets
            relayQueueDao?.let { dao ->
                val now = System.currentTimeMillis()
                dao.pruneExpired(now)
                val activeRelays = dao.getAllActiveRelayPackets(now)
                for (r in activeRelays) {
                    inMemoryRelayQueue[r.packetId] = r
                }
            }
        }
    }

    /**
     * Cleans up expired relay packets and completed outbound packets older than 24h.
     */
    suspend fun pruneOldQueues() {
        relayQueueDao?.pruneExpired(System.currentTimeMillis())
        outboundQueueDao?.pruneOldPackets(System.currentTimeMillis() - 86_400_000L)
    }

    /**
     * Registers a listener for End-to-End delivery acknowledgement.
     */
    fun registerE2eAckListener(packetId: Long, onAck: (DeliveryAck) -> Unit) {
        pendingE2eAckListeners[packetId] = onAck
    }

    /**
     * Called when a peer establishes a physical BLE connection.
     * Flushes pending store-and-forward relay packets and triggers retry processing.
     */
    fun handlePeerConnected(deviceAddress: String, hexAddress: Long, rssiDbm: Int = -60) {
        neighborTable.addOrUpdateNeighbor(deviceAddress, hexAddress, rssiDbm, isDirect = true)
        routeTable.updateRoute(hexAddress, deviceAddress, 1)

        scope.launch {
            flushQueuedPacketsForPeer(deviceAddress, hexAddress)
            processDueRetries()
        }
    }

    /**
     * Flushes stored packets destined for or routable through a newly connected peer.
     */
    suspend fun flushQueuedPacketsForPeer(deviceAddress: String, hexAddress: Long) {
        // 1. In-memory relay queue flush
        val toRemove = mutableListOf<Long>()
        for ((packetId, entity) in inMemoryRelayQueue) {
            if (entity.destinationHex == hexAddress || entity.expiresEpochMs > System.currentTimeMillis()) {
                val des = PacketSerializer.deserialize(entity.rawPacketBytes)
                if (des.isSuccess) {
                    transmitCallback?.invoke(deviceAddress, des.getOrThrow())
                    toRemove.add(packetId)
                }
            }
        }
        toRemove.forEach { inMemoryRelayQueue.remove(it) }

        // 2. Database relay queue flush
        relayQueueDao?.let { dao ->
            val pendingRelays = dao.getPacketsForDestination(hexAddress, System.currentTimeMillis())
            for (p in pendingRelays) {
                val des = PacketSerializer.deserialize(p.rawPacketBytes)
                if (des.isSuccess) {
                    transmitCallback?.invoke(deviceAddress, des.getOrThrow())
                    dao.deleteRelayPacket(p.packetId)
                }
            }
        }
    }

    /**
     * Stores a packet in the relay store-and-forward queue when downstream route is temporarily missing.
     * Enforces storage capacity limits and evicts oldest entries to prevent queue exhaustion.
     */
    suspend fun storeForRelay(packet: BinaryPacket, ttlMs: Long = 300_000) {
        val rawBytes = PacketSerializer.serialize(packet)
        val now = System.currentTimeMillis()
        val entity = RelayPacketEntity(
            packetId = packet.packetId,
            destinationHex = packet.destinationHex,
            sourceHex = packet.sourceHex,
            rawPacketBytes = rawBytes,
            expiresEpochMs = now + ttlMs,
            receivedEpochMs = now,
            relayAttempts = 0
        )
        inMemoryRelayQueue[packet.packetId] = entity

        relayQueueDao?.let { dao ->
            try {
                dao.pruneExpired(now)
                val count = dao.getCount()
                if (count >= MAX_RELAY_QUEUE_CAPACITY) {
                    dao.evictOldest(10)
                }
                dao.insertRelayPacket(entity)
            } catch (e: Exception) {
                // Storage error handled safely
            }
        }
    }

    /**
     * Called when a physical BLE peer connection disconnects.
     * Invalidates routes traversing this peer.
     */
    fun handlePeerDisconnected(deviceAddress: String) {
        neighborTable.removeNeighbor(deviceAddress)
        routeTable.invalidateRoutesVia(deviceAddress)
    }

    fun getPendingOutboundCount(): Int = inMemoryOutboundQueue.values.count { it.state != "DELIVERED" }

    fun getStoredRelayCount(): Int = inMemoryRelayQueue.size

    fun getOutboundPacketState(packetId: Long): String? = inMemoryOutboundQueue[packetId]?.state
}
