package net.hexnet.app.core.crypto

/**
 * Noise Protocol CipherState (§5.1).
 * Encapsulates a 32-byte symmetric key `k` and a 64-bit integer nonce `n`.
 */
class NoiseCipherState(
    private var key: ByteArray? = null,
    var nonce: Long = 0L
) {
    fun hasKey(): Boolean = key != null

    fun initializeKey(keyBytes: ByteArray) {
        require(keyBytes.size == 32) { "CipherState key must be 32 bytes" }
        key = keyBytes.copyOf()
        nonce = 0L
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext // If key is empty, return plaintext as-is (§5.1)

        if (nonce < 0 || nonce == Long.MAX_VALUE) {
            throw IllegalStateException("Noise CipherState nonce exhaustion: must rekey before encrypting")
        }

        val ciphertext = CryptoPrimitives.encryptChaCha20Poly1305(k, nonce, ad, plaintext)
        nonce++
        return ciphertext
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext // If key is empty, return ciphertext as-is (§5.1)

        if (nonce < 0 || nonce == Long.MAX_VALUE) {
            throw IllegalStateException("Noise CipherState nonce exhaustion: must rekey before decrypting")
        }

        val plaintext = CryptoPrimitives.decryptChaCha20Poly1305(k, nonce, ad, ciphertext)
        nonce++
        return plaintext
    }

    /**
     * Rekey (§4.2): Sets k = ENCRYPT(k, max_nonce, zeros, zeros) to advance the forward-secrecy ratchet.
     */
    fun rekey() {
        val k = key ?: return
        val zeros = ByteArray(32)
        val maxNonce = -1L // 0xFFFFFFFFFFFFFFFFL in unsigned
        val newKeyMaterial = CryptoPrimitives.encryptChaCha20Poly1305(k, maxNonce, ByteArray(0), zeros)
        key = newKeyMaterial.copyOf(32)
    }

    fun getKeyBytes(): ByteArray? = key?.copyOf()

    fun destroy() {
        key?.fill(0)
        key = null
        nonce = 0L
    }
}
