package net.hexnet.app.core.crypto

import java.io.ByteArrayOutputStream

enum class NoiseRole {
    INITIATOR,
    RESPONDER
}

/**
 * Implements the full Noise_XX_25519_ChaChaPoly_BLAKE2s Handshake state machine (§7.2).
 * Provides mutual authentication, identity hiding, and forward secrecy.
 * Handshake sequence:
 *   -> e
 *   <- e, ee, s, es
 *   -> s, se
 */
class NoiseHandshakeState(
    val role: NoiseRole,
    val localStaticKeyPair: KeyPairBytes,
    prologue: ByteArray = ByteArray(0)
) {
    val symmetricState = NoiseSymmetricState("Noise_XX_25519_ChaChaPoly_BLAKE2s")

    private var localEphemeralKeyPair: KeyPairBytes? = null
    var remoteEphemeralPublicKey: ByteArray? = null
        private set
    var remoteStaticPublicKey: ByteArray? = null
        private set

    var step: Int = 0
        private set

    init {
        if (prologue.isNotEmpty()) {
            symmetricState.mixHash(prologue)
        }
    }

    fun isComplete(): Boolean = step == 3

    /**
     * Executes the next outgoing message step for the active role.
     */
    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()

        when (role) {
            NoiseRole.INITIATOR -> {
                when (step) {
                    0 -> {
                        // -> e
                        val e = CryptoPrimitives.generateX25519KeyPair()
                        localEphemeralKeyPair = e
                        out.write(e.publicKey)
                        symmetricState.mixHash(e.publicKey)

                        val encryptedPayload = symmetricState.encryptAndHash(payload)
                        out.write(encryptedPayload)
                        step = 1
                    }
                    2 -> {
                        // -> s, se
                        val re = remoteEphemeralPublicKey
                            ?: throw IllegalStateException("Cannot write Message 3: remote ephemeral key missing")

                        val encryptedStatic = symmetricState.encryptAndHash(localStaticKeyPair.publicKey)
                        out.write(encryptedStatic)

                        val se = CryptoPrimitives.calculateX25519(localStaticKeyPair.privateKey, re)
                        symmetricState.mixKey(se)

                        val encryptedPayload = symmetricState.encryptAndHash(payload)
                        out.write(encryptedPayload)
                        step = 3 // Complete
                    }
                    else -> throw IllegalStateException("Initiator invalid write step: $step")
                }
            }
            NoiseRole.RESPONDER -> {
                when (step) {
                    1 -> {
                        // <- e, ee, s, es
                        val re = remoteEphemeralPublicKey
                            ?: throw IllegalStateException("Cannot write Message 2: remote ephemeral key missing")

                        val e = CryptoPrimitives.generateX25519KeyPair()
                        localEphemeralKeyPair = e
                        out.write(e.publicKey)
                        symmetricState.mixHash(e.publicKey)

                        val ee = CryptoPrimitives.calculateX25519(e.privateKey, re)
                        symmetricState.mixKey(ee)

                        val encryptedStatic = symmetricState.encryptAndHash(localStaticKeyPair.publicKey)
                        out.write(encryptedStatic)

                        val es = CryptoPrimitives.calculateX25519(localStaticKeyPair.privateKey, re)
                        symmetricState.mixKey(es)

                        val encryptedPayload = symmetricState.encryptAndHash(payload)
                        out.write(encryptedPayload)
                        step = 2
                    }
                    else -> throw IllegalStateException("Responder invalid write step: $step")
                }
            }
        }

        return out.toByteArray()
    }

    /**
     * Ingests and processes an incoming handshake message for the active role.
     * Returns the decrypted payload embedded in the handshake frame.
     */
    fun readMessage(message: ByteArray): ByteArray {
        var offset = 0

        when (role) {
            NoiseRole.INITIATOR -> {
                when (step) {
                    1 -> {
                        // <- e, ee, s, es
                        if (message.size < 32 + 48) { // 32 e.pk + 48 s.pk (32+16 tag)
                            throw IllegalArgumentException("Message 2 too short: ${message.size} bytes")
                        }

                        val re = message.copyOfRange(offset, offset + 32)
                        offset += 32
                        remoteEphemeralPublicKey = re
                        symmetricState.mixHash(re)

                        val e = localEphemeralKeyPair
                            ?: throw IllegalStateException("Local ephemeral key missing")
                        val ee = CryptoPrimitives.calculateX25519(e.privateKey, re)
                        symmetricState.mixKey(ee)

                        val encStatic = message.copyOfRange(offset, offset + 48)
                        offset += 48
                        val rs = symmetricState.decryptAndHash(encStatic)
                        remoteStaticPublicKey = rs

                        val es = CryptoPrimitives.calculateX25519(e.privateKey, rs)
                        symmetricState.mixKey(es)

                        val encPayload = message.copyOfRange(offset, message.size)
                        val payload = symmetricState.decryptAndHash(encPayload)
                        step = 2
                        return payload
                    }
                    else -> throw IllegalStateException("Initiator invalid read step: $step")
                }
            }
            NoiseRole.RESPONDER -> {
                when (step) {
                    0 -> {
                        // -> e
                        if (message.size < 32) {
                            throw IllegalArgumentException("Message 1 too short: ${message.size} bytes")
                        }

                        val re = message.copyOfRange(0, 32)
                        offset = 32
                        remoteEphemeralPublicKey = re
                        symmetricState.mixHash(re)

                        val encPayload = message.copyOfRange(offset, message.size)
                        val payload = symmetricState.decryptAndHash(encPayload)
                        step = 1
                        return payload
                    }
                    2 -> {
                        // -> s, se
                        if (message.size < 48) { // 32 s.pk + 16 tag
                            throw IllegalArgumentException("Message 3 too short: ${message.size} bytes")
                        }

                        val encStatic = message.copyOfRange(0, 48)
                        offset = 48
                        val rs = symmetricState.decryptAndHash(encStatic)
                        remoteStaticPublicKey = rs

                        val e = localEphemeralKeyPair
                            ?: throw IllegalStateException("Local ephemeral key missing")
                        val se = CryptoPrimitives.calculateX25519(e.privateKey, rs)
                        symmetricState.mixKey(se)

                        val encPayload = message.copyOfRange(offset, message.size)
                        val payload = symmetricState.decryptAndHash(encPayload)
                        step = 3 // Complete
                        return payload
                    }
                    else -> throw IllegalStateException("Responder invalid read step: $step")
                }
            }
        }
    }

    /**
     * Splits completed handshake state into transport ciphers (sendCipher, recvCipher).
     * Must only be called after isComplete() is true.
     */
    fun split(): Pair<NoiseCipherState, NoiseCipherState> {
        check(isComplete()) { "Handshake must be complete (step 3) before split()" }
        val (c1, c2) = symmetricState.split()

        // Secure wipe of ephemeral private key
        localEphemeralKeyPair?.destroy()
        localEphemeralKeyPair = null

        return if (role == NoiseRole.INITIATOR) {
            Pair(c1, c2) // Initiator sends with c1, receives with c2
        } else {
            Pair(c2, c1) // Responder sends with c2, receives with c1
        }
    }

    fun destroy() {
        symmetricState.destroy()
        localEphemeralKeyPair?.destroy()
        localEphemeralKeyPair = null
        remoteEphemeralPublicKey = null
        remoteStaticPublicKey = null
    }
}
