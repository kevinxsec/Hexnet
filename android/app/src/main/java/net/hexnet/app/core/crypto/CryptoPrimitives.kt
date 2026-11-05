package net.hexnet.app.core.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

data class KeyPairBytes(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KeyPairBytes
        return privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = privateKey.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }

    fun destroy() {
        privateKey.fill(0)
    }
}

/**
 * Low-level cryptographic primitives wrapping reviewed Bouncy Castle implementations:
 * - RFC 7748 X25519 (Curve25519 ECDH key agreement)
 * - RFC 8032 Ed25519 (Digital signatures)
 * - RFC 8439 ChaCha20-Poly1305 (Authenticated Encryption with Associated Data - AEAD)
 * - BLAKE2s-256 and HMAC-BLAKE2s
 */
object CryptoPrimitives {

    private val secureRandom = SecureRandom()

    // -----------------------------------------------------------------------------------
    // Curve25519 / X25519 Key Agreement
    // -----------------------------------------------------------------------------------

    fun generateX25519KeyPair(): KeyPairBytes {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val pair = generator.generateKeyPair()

        val priv = (pair.private as X25519PrivateKeyParameters).encoded
        val pub = (pair.public as X25519PublicKeyParameters).encoded
        return KeyPairBytes(priv, pub)
    }

    fun calculateX25519(ourPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        require(ourPrivateKey.size == 32) { "X25519 private key must be 32 bytes" }
        require(theirPublicKey.size == 32) { "X25519 public key must be 32 bytes" }

        val privParams = X25519PrivateKeyParameters(ourPrivateKey, 0)
        val pubParams = X25519PublicKeyParameters(theirPublicKey, 0)

        val agreement = X25519Agreement()
        agreement.init(privParams)

        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(pubParams, sharedSecret, 0)

        // RFC 7748 §6.1 contributory behavior requirement:
        // Reject all-zero shared secrets resulting from low-order points or weak keys
        if (sharedSecret.all { it == 0.toByte() }) {
            throw SecurityException("X25519 contributory check failed: all-zero shared secret (weak or low-order public key)")
        }

        return sharedSecret
    }

    // -----------------------------------------------------------------------------------
    // Ed25519 Signatures & Identity Keys
    // -----------------------------------------------------------------------------------

    fun generateEd25519KeyPair(): KeyPairBytes {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(secureRandom))
        val pair = generator.generateKeyPair()

        val priv = (pair.private as Ed25519PrivateKeyParameters).encoded
        val pub = (pair.public as Ed25519PublicKeyParameters).encoded
        return KeyPairBytes(priv, pub)
    }

    fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Ed25519 private key must be 32 bytes" }
        val privParams = Ed25519PrivateKeyParameters(privateKey, 0)
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val pubParams = Ed25519PublicKeyParameters(publicKey, 0)
            val signer = Ed25519Signer()
            signer.init(false, pubParams)
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (e: Exception) {
            false
        }
    }

    // -----------------------------------------------------------------------------------
    // BLAKE2s-256 Hashing & HMAC-BLAKE2s
    // -----------------------------------------------------------------------------------

    fun blake2s(data: ByteArray, key: ByteArray? = null): ByteArray {
        val digest = if (key != null) Blake2sDigest(key) else Blake2sDigest(32 * 8)
        digest.update(data, 0, data.size)
        val output = ByteArray(32)
        digest.doFinal(output, 0)
        return output
    }

    fun hmacBlake2s(key: ByteArray, data: ByteArray): ByteArray {
        val hmac = HMac(Blake2sDigest(32 * 8))
        hmac.init(KeyParameter(key))
        hmac.update(data, 0, data.size)
        val out = ByteArray(32)
        hmac.doFinal(out, 0)
        return out
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return java.security.MessageDigest.isEqual(a, b)
    }

    /**
     * HKDF using HMAC-BLAKE2s returning 2 output keys (Noise spec §5.2).
     */
    fun hkdf2(chainingKey: ByteArray, inputKeyMaterial: ByteArray): Pair<ByteArray, ByteArray> {
        val tempKey = hmacBlake2s(chainingKey, inputKeyMaterial)
        val output1 = hmacBlake2s(tempKey, byteArrayOf(0x01))
        val output2 = hmacBlake2s(tempKey, output1 + byteArrayOf(0x02))
        tempKey.fill(0)
        return Pair(output1, output2)
    }

    /**
     * HKDF using HMAC-BLAKE2s returning 3 output keys (Noise spec §5.2).
     */
    fun hkdf3(chainingKey: ByteArray, inputKeyMaterial: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val tempKey = hmacBlake2s(chainingKey, inputKeyMaterial)
        val output1 = hmacBlake2s(tempKey, byteArrayOf(0x01))
        val output2 = hmacBlake2s(tempKey, output1 + byteArrayOf(0x02))
        val output3 = hmacBlake2s(tempKey, output2 + byteArrayOf(0x03))
        tempKey.fill(0)
        return Triple(output1, output2, output3)
    }

    // -----------------------------------------------------------------------------------
    // RFC 8439 ChaCha20-Poly1305 AEAD
    // -----------------------------------------------------------------------------------

    /**
     * Encrypts plaintext with ChaCha20-Poly1305 AEAD.
     * Nonce formatting adheres to Noise Protocol: 4 zero bytes + 8 bytes 64-bit integer little-endian = 12 bytes IV.
     * Output format: Ciphertext || 16-byte Poly1305 Authentication Tag.
     */
    fun encryptChaCha20Poly1305(
        key: ByteArray,
        nonce: Long,
        ad: ByteArray,
        plaintext: ByteArray
    ): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce >= 0 || nonce == -1L) { "Nonce must be non-negative or max uint64 (-1L)" }

        val iv = ByteArray(12)
        ByteBuffer.wrap(iv, 4, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(nonce)

        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), 128, iv, ad)
        cipher.init(true, params)

        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, len)
        return output
    }

    /**
     * Decrypts and authenticates ciphertext with ChaCha20-Poly1305 AEAD.
     * Throws SecurityException if the Poly1305 authentication tag does not match (tampering detected).
     */
    fun decryptChaCha20Poly1305(
        key: ByteArray,
        nonce: Long,
        ad: ByteArray,
        ciphertextAndTag: ByteArray
    ): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(ciphertextAndTag.size >= 16) { "Ciphertext must contain at least 16-byte Poly1305 tag" }
        require(nonce >= 0 || nonce == -1L) { "Nonce must be non-negative or max uint64 (-1L)" }

        val iv = ByteArray(12)
        ByteBuffer.wrap(iv, 4, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(nonce)

        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), 128, iv, ad)
        cipher.init(false, params)

        val output = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        return try {
            val len = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, output, 0)
            val finalLen = len + cipher.doFinal(output, len)
            output.copyOf(finalLen)
        } catch (e: Exception) {
            throw SecurityException("ChaCha20-Poly1305 MAC tag verification failed (tampered payload or bad key)", e)
        }
    }
}
