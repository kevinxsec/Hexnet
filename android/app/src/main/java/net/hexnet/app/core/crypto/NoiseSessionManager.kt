package net.hexnet.app.core.crypto

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class HandshakeProcessResult {
    data class Reply(val messageBytes: ByteArray) : HandshakeProcessResult()
    data class Established(val session: NoiseSession) : HandshakeProcessResult()
    data class EstablishedWithReply(val session: NoiseSession, val messageBytes: ByteArray) : HandshakeProcessResult()
    object Error : HandshakeProcessResult()
}

/**
 * Encapsulates cross-key mutual authentication: binds an Ed25519 identity key
 * to an X25519 Noise static public key and handshake transcript hash.
 */
object IdentityProofHelper {
    const val PROOF_SIZE = 96 // 32 Ed25519 pub + 64 Ed25519 sig

    fun createProof(identity: NodeIdentity, handshakeHash: ByteArray): ByteArray {
        val staticPub = identity.x25519KeyPair.publicKey
        val dataToSign = staticPub + handshakeHash
        val signature = CryptoPrimitives.signEd25519(identity.ed25519KeyPair.privateKey, dataToSign)
        return identity.ed25519KeyPair.publicKey + signature
    }

    fun verifyProof(
        proofBytes: ByteArray,
        expectedPeerHex: String,
        remoteStaticKey: ByteArray,
        handshakeHash: ByteArray,
        strictAddressCheck: Boolean = false
    ): Boolean {
        if (proofBytes.size < PROOF_SIZE) return false
        val ed25519Pub = proofBytes.copyOfRange(0, 32)
        val signature = proofBytes.copyOfRange(32, 96)

        // 1. Verify signature over (remoteStaticKey || handshakeHash)
        val signedData = remoteStaticKey + handshakeHash
        val sigValid = CryptoPrimitives.verifyEd25519(ed25519Pub, signedData, signature)
        if (!sigValid) return false

        // 2. Verify Ed25519 public key hashes to expectedPeerHex when strict
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val pubHash = md.digest(ed25519Pub)
        val derivedHex = pubHash.take(8).joinToString("") { "%02x".format(it) }
        val cleanExpected = expectedPeerHex.removePrefix("0x").lowercase()

        return if (strictAddressCheck && cleanExpected.isNotEmpty()) {
            derivedHex == cleanExpected
        } else {
            true
        }
    }
}

/**
 * Manages active E2EE Noise sessions and concurrent peer handshakes across the mesh network.
 */
class NoiseSessionManager(
    private val identityManager: IdentityManager
) {
    private val activeSessions = ConcurrentHashMap<String, NoiseSession>()
    private val inFlightHandshakes = ConcurrentHashMap<String, NoiseHandshakeState>()

    fun hasActiveSession(peerHexAddress: String): Boolean = activeSessions.containsKey(peerHexAddress)

    fun getSession(peerHexAddress: String): NoiseSession? = activeSessions[peerHexAddress]

    /**
     * Initiates a new Noise_XX handshake with a remote peer (Acts as INITIATOR).
     * Returns Message 1 bytes to be transmitted over BLE transport.
     */
    @Synchronized
    fun startHandshake(peerHexAddress: String): ByteArray {
        val identity = identityManager.getIdentity()
            ?: throw IllegalStateException("Cannot start handshake: local identity not initialized")

        val handshake = NoiseHandshakeState(
            role = NoiseRole.INITIATOR,
            localStaticKeyPair = identity.x25519KeyPair
        )

        val message1 = handshake.writeMessage()
        inFlightHandshakes[peerHexAddress] = handshake
        return message1
    }

    /**
     * Ingests an incoming handshake frame from a peer and returns the appropriate reply or established session.
     * Enforces mutual cross-key authentication and handles handshake glare.
     */
    @Synchronized
    fun processHandshakeMessage(
        peerHexAddress: String,
        incomingBytes: ByteArray
    ): HandshakeProcessResult {
        val identity = identityManager.getIdentity() ?: return HandshakeProcessResult.Error
        val existingHandshake = inFlightHandshakes[peerHexAddress]

        if (existingHandshake == null) {
            // No existing handshake in flight -> We are receiving Message 1 as RESPONDER
            return try {
                val handshake = NoiseHandshakeState(
                    role = NoiseRole.RESPONDER,
                    localStaticKeyPair = identity.x25519KeyPair
                )
                handshake.readMessage(incomingBytes) // Ingest Msg 1
                val proof = IdentityProofHelper.createProof(identity, handshake.symmetricState.h)
                val message2 = handshake.writeMessage(proof) // Produce Msg 2 with proof
                inFlightHandshakes[peerHexAddress] = handshake
                HandshakeProcessResult.Reply(message2)
            } catch (e: Exception) {
                HandshakeProcessResult.Error
            }
        } else {
            // Glare detection: Both peers simultaneously initiated a handshake
            if (existingHandshake.role == NoiseRole.INITIATOR && existingHandshake.step == 1 && incomingBytes.size <= 32) {
                val localHex = identity.hexAddress.removePrefix("0x").lowercase()
                val remoteHex = peerHexAddress.removePrefix("0x").lowercase()

                if (localHex < remoteHex) {
                    // Local node yields: cancel our INITIATOR handshake and act as RESPONDER
                    existingHandshake.destroy()
                    return try {
                        val responderHandshake = NoiseHandshakeState(
                            role = NoiseRole.RESPONDER,
                            localStaticKeyPair = identity.x25519KeyPair
                        )
                        responderHandshake.readMessage(incomingBytes)
                        val proof = IdentityProofHelper.createProof(identity, responderHandshake.symmetricState.h)
                        val message2 = responderHandshake.writeMessage(proof)
                        inFlightHandshakes[peerHexAddress] = responderHandshake
                        HandshakeProcessResult.Reply(message2)
                    } catch (e: Exception) {
                        inFlightHandshakes.remove(peerHexAddress)
                        HandshakeProcessResult.Error
                    }
                } else {
                    // Local node has higher ID and insists on being INITIATOR: ignore remote Msg 1, await Msg 2
                    return HandshakeProcessResult.Error
                }
            }

            return try {
                if (existingHandshake.role == NoiseRole.INITIATOR && existingHandshake.step == 1) {
                    // Initiator receives Message 2
                    val hashBeforeMsg2 = existingHandshake.symmetricState.h.copyOf()
                    val payload = existingHandshake.readMessage(incomingBytes)

                    val remoteStatic = existingHandshake.remoteStaticPublicKey
                        ?: throw IllegalStateException("Remote static key missing on completion")

                    // Verify Responder's identity proof if present
                    if (payload.size >= IdentityProofHelper.PROOF_SIZE) {
                        val verified = IdentityProofHelper.verifyProof(
                            proofBytes = payload,
                            expectedPeerHex = peerHexAddress,
                            remoteStaticKey = remoteStatic,
                            handshakeHash = hashBeforeMsg2
                        )
                        if (!verified) {
                            throw SecurityException("Responder identity proof verification failed for peer $peerHexAddress")
                        }
                    }

                    val proof = IdentityProofHelper.createProof(identity, existingHandshake.symmetricState.h)
                    val message3 = existingHandshake.writeMessage(proof) // Produce Msg 3 with proof

                    // Handshake is now complete for Initiator!
                    val (sendCipher, recvCipher) = existingHandshake.split()

                    val session = NoiseSession(
                        sessionId = "sess_" + UUID.randomUUID().toString().take(8),
                        peerHexAddress = peerHexAddress,
                        remoteStaticPublicKey = remoteStatic,
                        sendCipher = sendCipher,
                        recvCipher = recvCipher
                    )

                    activeSessions[peerHexAddress] = session
                    inFlightHandshakes.remove(peerHexAddress)
                    HandshakeProcessResult.EstablishedWithReply(session, message3)
                } else if (existingHandshake.role == NoiseRole.RESPONDER && existingHandshake.step == 2) {
                    // Responder receives Message 3
                    val hashBeforeMsg3 = existingHandshake.symmetricState.h.copyOf()
                    val payload = existingHandshake.readMessage(incomingBytes)

                    val remoteStatic = existingHandshake.remoteStaticPublicKey
                        ?: throw IllegalStateException("Remote static key missing on completion")

                    // Verify Initiator's identity proof if present
                    if (payload.size >= IdentityProofHelper.PROOF_SIZE) {
                        val verified = IdentityProofHelper.verifyProof(
                            proofBytes = payload,
                            expectedPeerHex = peerHexAddress,
                            remoteStaticKey = remoteStatic,
                            handshakeHash = hashBeforeMsg3
                        )
                        if (!verified) {
                            throw SecurityException("Initiator identity proof verification failed for peer $peerHexAddress")
                        }
                    }

                    // Handshake is now complete for Responder!
                    val (sendCipher, recvCipher) = existingHandshake.split()

                    val session = NoiseSession(
                        sessionId = "sess_" + UUID.randomUUID().toString().take(8),
                        peerHexAddress = peerHexAddress,
                        remoteStaticPublicKey = remoteStatic,
                        sendCipher = sendCipher,
                        recvCipher = recvCipher
                    )

                    activeSessions[peerHexAddress] = session
                    inFlightHandshakes.remove(peerHexAddress)
                    HandshakeProcessResult.Established(session)
                } else {
                    HandshakeProcessResult.Error
                }
            } catch (e: Exception) {
                inFlightHandshakes.remove(peerHexAddress)
                HandshakeProcessResult.Error
            }
        }
    }

    /**
     * Encrypts outgoing direct message payload using the peer's active Noise session.
     */
    fun encryptDirectMessage(peerHexAddress: String, plaintext: ByteArray): EncryptedMessagePayload {
        val session = activeSessions[peerHexAddress]
            ?: throw IllegalStateException("No active encrypted Noise session for peer $peerHexAddress")
        return session.encryptPayload(plaintext)
    }

    /**
     * Decrypts incoming direct message payload using the peer's active Noise session with replay validation.
     */
    fun decryptDirectMessage(peerHexAddress: String, seq: Long, ciphertext: ByteArray): ByteArray {
        val session = activeSessions[peerHexAddress]
            ?: throw IllegalStateException("No active encrypted Noise session for peer $peerHexAddress")
        return session.decryptPayload(seq, ciphertext)
    }

    fun terminateSession(peerHexAddress: String) {
        val session = activeSessions.remove(peerHexAddress)
        session?.destroy()
        val handshake = inFlightHandshakes.remove(peerHexAddress)
        handshake?.destroy()
    }

    fun clearAll() {
        for ((_, session) in activeSessions) {
            session.destroy()
        }
        activeSessions.clear()
        for ((_, handshake) in inFlightHandshakes) {
            handshake.destroy()
        }
        inFlightHandshakes.clear()
    }
}
