package net.hexnet.app.core.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EncryptedMessagePayload(
    val sequenceNumber: Long,
    val ciphertext: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedMessagePayload
        return sequenceNumber == other.sequenceNumber && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = sequenceNumber.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}

/**
 * Represents an established, bidirectional encrypted Noise session between this node and a remote peer.
 */
class NoiseSession(
    val sessionId: String,
    val peerHexAddress: String,
    val remoteStaticPublicKey: ByteArray,
    private val sendCipher: NoiseCipherState,
    private val recvCipher: NoiseCipherState,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    var lastActiveEpochMs: Long = System.currentTimeMillis()
        private set
    var messagesSent: Long = 0L
        private set
    var messagesReceived: Long = 0L
        private set

    companion object {
        const val REKEY_INTERVAL = 1000L
    }

    private var currentSendEpoch: Long = 0L
    private var currentRecvEpoch: Long = 0L
    private var previousRecvKey: ByteArray? = null
    private var previousRecvEpoch: Long = -1L

    private val replayFilter = ReplayFilter(windowSize = 128)

    /**
     * Encrypts outgoing application plaintext with ChaCha20-Poly1305.
     * Associated Data (AD) binds the 64-bit sequence number to prevent sequence tampering.
     */
    @Synchronized
    fun encryptPayload(plaintext: ByteArray): EncryptedMessagePayload {
        val seq = sendCipher.nonce
        val targetEpoch = seq / REKEY_INTERVAL
        while (currentSendEpoch < targetEpoch) {
            sendCipher.rekey()
            currentSendEpoch++
        }

        val ad = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(seq).array()
        val ciphertext = sendCipher.encryptWithAd(ad, plaintext)

        messagesSent++
        lastActiveEpochMs = System.currentTimeMillis()

        return EncryptedMessagePayload(sequenceNumber = seq, ciphertext = ciphertext)
    }

    /**
     * Decrypts and authenticates incoming message payload.
     * Enforces strict sliding-window replay protection and epoch-based ratchet recovery.
     */
    @Synchronized
    fun decryptPayload(sequenceNumber: Long, ciphertext: ByteArray): ByteArray {
        // 1. Replay Protection Verification
        val isFresh = replayFilter.validateAndAdvance(sequenceNumber)
        if (!isFresh) {
            throw SecurityException("Replay attack detected: sequence $sequenceNumber is a duplicate or outside the valid window")
        }

        val targetEpoch = sequenceNumber / REKEY_INTERVAL
        val ad = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(sequenceNumber).array()

        // 2. Epoch Ratchet Step & Key Resolution
        val plaintext = when {
            targetEpoch == currentRecvEpoch -> {
                recvCipher.nonce = sequenceNumber
                recvCipher.decryptWithAd(ad, ciphertext)
            }
            targetEpoch == currentRecvEpoch + 1 -> {
                // Advance to next epoch, retaining current key for out-of-order packets in sliding window
                previousRecvKey?.fill(0)
                previousRecvKey = recvCipher.getKeyBytes()
                previousRecvEpoch = currentRecvEpoch
                recvCipher.rekey()
                currentRecvEpoch = targetEpoch

                recvCipher.nonce = sequenceNumber
                recvCipher.decryptWithAd(ad, ciphertext)
            }
            targetEpoch == previousRecvEpoch && previousRecvKey != null -> {
                // Out-of-order packet arriving late across rekey boundary
                val fallbackCipher = NoiseCipherState()
                fallbackCipher.initializeKey(previousRecvKey!!)
                fallbackCipher.nonce = sequenceNumber
                val pt = fallbackCipher.decryptWithAd(ad, ciphertext)
                fallbackCipher.destroy()
                pt
            }
            targetEpoch > currentRecvEpoch + 1 -> {
                // Packet jumped multiple epochs (sequence advance within window)
                while (currentRecvEpoch < targetEpoch) {
                    previousRecvKey?.fill(0)
                    previousRecvKey = recvCipher.getKeyBytes()
                    previousRecvEpoch = currentRecvEpoch
                    recvCipher.rekey()
                    currentRecvEpoch++
                }
                recvCipher.nonce = sequenceNumber
                recvCipher.decryptWithAd(ad, ciphertext)
            }
            else -> {
                throw SecurityException("Packet from obsolete key epoch $targetEpoch rejected (current: $currentRecvEpoch)")
            }
        }

        messagesReceived++
        lastActiveEpochMs = System.currentTimeMillis()

        return plaintext
    }

    /**
     * Explicit manual key rotation advancing the ratchet.
     */
    @Synchronized
    fun rotateKeys() {
        sendCipher.rekey()
        recvCipher.rekey()
        currentSendEpoch++
        currentRecvEpoch++
    }

    /**
     * Securely wipes all session keys from memory.
     */
    @Synchronized
    fun destroy() {
        sendCipher.destroy()
        recvCipher.destroy()
        previousRecvKey?.fill(0)
        previousRecvKey = null
        replayFilter.reset()
    }
}
