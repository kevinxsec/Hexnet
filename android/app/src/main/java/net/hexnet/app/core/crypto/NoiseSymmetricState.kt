package net.hexnet.app.core.crypto

/**
 * Noise Protocol SymmetricState (§5.2).
 * Encapsulates a CipherState, chaining key `ck`, and handshake hash `h`.
 */
class NoiseSymmetricState(
    protocolName: String = "Noise_XX_25519_ChaChaPoly_BLAKE2s"
) {
    val cipherState: NoiseCipherState = NoiseCipherState()
    var ck: ByteArray = ByteArray(32)
        private set
    var h: ByteArray = ByteArray(32)
        private set

    init {
        initializeSymmetric(protocolName)
    }

    fun initializeSymmetric(protocolName: String) {
        val nameBytes = protocolName.toByteArray(Charsets.US_ASCII)
        h = if (nameBytes.size <= 32) {
            val padded = ByteArray(32)
            System.arraycopy(nameBytes, 0, padded, 0, nameBytes.size)
            padded
        } else {
            CryptoPrimitives.blake2s(nameBytes)
        }
        ck = h.copyOf()
        cipherState.destroy()
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (newCk, tempK) = CryptoPrimitives.hkdf2(ck, inputKeyMaterial)
        ck = newCk
        cipherState.initializeKey(tempK)
    }

    fun mixHash(data: ByteArray) {
        h = CryptoPrimitives.blake2s(h + data)
    }

    fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val (newCk, tempH, tempK) = CryptoPrimitives.hkdf3(ck, inputKeyMaterial)
        ck = newCk
        mixHash(tempH)
        cipherState.initializeKey(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipherState.encryptWithAd(h, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipherState.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    /**
     * Splits chaining key into transport ciphers (Noise §5.2).
     * Returns Pair(sendCipher, recvCipher) for the active role.
     */
    fun split(): Pair<NoiseCipherState, NoiseCipherState> {
        val (tempK1, tempK2) = CryptoPrimitives.hkdf2(ck, ByteArray(0))
        val c1 = NoiseCipherState()
        c1.initializeKey(tempK1)
        val c2 = NoiseCipherState()
        c2.initializeKey(tempK2)
        return Pair(c1, c2)
    }

    fun destroy() {
        cipherState.destroy()
        ck.fill(0)
        h.fill(0)
    }
}
