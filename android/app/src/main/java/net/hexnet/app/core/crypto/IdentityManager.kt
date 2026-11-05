package net.hexnet.app.core.crypto

import java.security.MessageDigest
import java.util.UUID

data class NodeIdentity(
    val username: String,
    val displayName: String,
    val hexAddress: String,
    val keyFingerprint: String,
    val x25519KeyPair: KeyPairBytes,
    val ed25519KeyPair: KeyPairBytes,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    fun destroy() {
        x25519KeyPair.destroy()
        ed25519KeyPair.destroy()
    }
}

/**
 * Manages the node's persistent cryptographic identities:
 * - Ed25519 signing keypair for verifiable digital signatures
 * - X25519 keypair for Noise XX Diffie-Hellman authenticated sessions
 * - Fingerprint derivation and Hex address formatting
 */
class IdentityManager(
    private val keyStorage: KeyStorage = InMemoryKeyStorage()
) {
    private var cachedIdentity: NodeIdentity? = null

    /**
     * Initializes or loads the persistent local cryptographic node identity.
     */
    fun getOrCreateIdentity(
        username: String,
        displayName: String,
        customX25519: KeyPairBytes? = null,
        customEd25519: KeyPairBytes? = null
    ): NodeIdentity {
        val existing = cachedIdentity ?: keyStorage.loadIdentity()
        if (existing != null) {
            cachedIdentity = existing
            return existing
        }

        // Generate fresh cryptographic keypairs or use supplied
        val x25519 = customX25519 ?: CryptoPrimitives.generateX25519KeyPair()
        val ed25519 = customEd25519 ?: CryptoPrimitives.generateEd25519KeyPair()

        // Derive deterministic 16-hex address (64-bit ID) from Ed25519 public key hash
        val md = MessageDigest.getInstance("SHA-256")
        val pubHash = md.digest(ed25519.publicKey)
        val hexAddress = "0x" + pubHash.take(8).joinToString("") { "%02x".format(it) }

        // Derive RFC-style fingerprint
        val fingerprint = "HEX:" + pubHash.takeLast(8).joinToString(":") { "%02X".format(it) }

        val newIdentity = NodeIdentity(
            username = username,
            displayName = displayName,
            hexAddress = hexAddress,
            keyFingerprint = fingerprint,
            x25519KeyPair = x25519,
            ed25519KeyPair = ed25519
        )

        keyStorage.saveIdentity(newIdentity)
        cachedIdentity = newIdentity
        return newIdentity
    }

    fun getIdentity(): NodeIdentity? {
        return cachedIdentity ?: keyStorage.loadIdentity()?.also { cachedIdentity = it }
    }

    fun signData(data: ByteArray): ByteArray {
        val identity = getIdentity() ?: throw IllegalStateException("Node identity not initialized")
        return CryptoPrimitives.signEd25519(identity.ed25519KeyPair.privateKey, data)
    }

    fun verifyPeerSignature(peerPublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return CryptoPrimitives.verifyEd25519(peerPublicKey, data, signature)
    }

    fun clearIdentity() {
        cachedIdentity?.destroy()
        cachedIdentity = null
        keyStorage.clear()
    }
}

interface KeyStorage {
    fun saveIdentity(identity: NodeIdentity)
    fun loadIdentity(): NodeIdentity?
    fun clear()
}

/**
 * Thread-safe in-memory key storage implementation for testing and isolated runtime.
 */
class InMemoryKeyStorage : KeyStorage {
    private var storedIdentity: NodeIdentity? = null

    @Synchronized
    override fun saveIdentity(identity: NodeIdentity) {
        storedIdentity = identity
    }

    @Synchronized
    override fun loadIdentity(): NodeIdentity? = storedIdentity

    @Synchronized
    override fun clear() {
        storedIdentity?.destroy()
        storedIdentity = null
    }
}
