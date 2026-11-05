package net.hexnet.app.crypto

import net.hexnet.app.core.crypto.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CryptoEngineTest {

    // -----------------------------------------------------------------------------------
    // 1. Identity Generation, Signatures & Fingerprint Verification
    // -----------------------------------------------------------------------------------

    @Test
    fun testIdentityGenerationAndFingerprint() {
        val storage = InMemoryKeyStorage()
        val identityManager = IdentityManager(storage)

        val identity = identityManager.getOrCreateIdentity("alex_node", "Alex Thorne")

        assertEquals("alex_node", identity.username)
        assertEquals("Alex Thorne", identity.displayName)
        assertTrue(identity.hexAddress.startsWith("0x"))
        assertEquals(18, identity.hexAddress.length) // "0x" + 16 chars

        assertTrue(identity.keyFingerprint.startsWith("HEX:"))
        val parts = identity.keyFingerprint.removePrefix("HEX:").split(":")
        assertEquals(8, parts.size)

        // Test signature generation and verification
        val message = "Hexnet cryptographic identity proof".toByteArray()
        val signature = identityManager.signData(message)
        assertEquals(64, signature.size) // Ed25519 signature is 64 bytes

        val valid = identityManager.verifyPeerSignature(identity.ed25519KeyPair.publicKey, message, signature)
        assertTrue("Signature verification must succeed with correct public key", valid)

        val tamperedMessage = "Hexnet tampered proof".toByteArray()
        val invalid = identityManager.verifyPeerSignature(identity.ed25519KeyPair.publicKey, tamperedMessage, signature)
        assertFalse("Signature verification must fail on tampered message", invalid)

        // Test persistence reload
        val reloadedIdentity = identityManager.getIdentity()
        assertNotNull(reloadedIdentity)
        assertEquals(identity.hexAddress, reloadedIdentity?.hexAddress)
        assertEquals(identity.keyFingerprint, reloadedIdentity?.keyFingerprint)
    }

    // -----------------------------------------------------------------------------------
    // 2. Successful Noise_XX Handshake Between Two Independent Identities
    // -----------------------------------------------------------------------------------

    @Test
    fun testSuccessfulNoiseXxHandshake() {
        val aliceStatic = CryptoPrimitives.generateX25519KeyPair()
        val bobStatic = CryptoPrimitives.generateX25519KeyPair()

        val aliceHandshake = NoiseHandshakeState(NoiseRole.INITIATOR, aliceStatic)
        val bobHandshake = NoiseHandshakeState(NoiseRole.RESPONDER, bobStatic)

        // Step 1: Alice -> Bob (Message 1: -> e)
        val msg1 = aliceHandshake.writeMessage("Hello from Alice (Msg1)".toByteArray())
        assertTrue("Message 1 size must be >= 32 bytes (ephemeral pk)", msg1.size >= 32)

        val bobReadMsg1Payload = bobHandshake.readMessage(msg1)
        assertEquals("Hello from Alice (Msg1)", String(bobReadMsg1Payload))

        // Step 2: Bob -> Alice (Message 2: <- e, ee, s, es)
        val msg2 = bobHandshake.writeMessage("Hello from Bob (Msg2)".toByteArray())
        assertTrue("Message 2 size must be >= 80 bytes (32 e.pk + 48 s.pk enc)", msg2.size >= 80)

        val aliceReadMsg2Payload = aliceHandshake.readMessage(msg2)
        assertEquals("Hello from Bob (Msg2)", String(aliceReadMsg2Payload))

        // Step 3: Alice -> Bob (Message 3: -> s, se)
        val msg3 = aliceHandshake.writeMessage("Confirm from Alice (Msg3)".toByteArray())
        assertTrue("Message 3 size must be >= 48 bytes (48 s.pk enc)", msg3.size >= 48)

        val bobReadMsg3Payload = bobHandshake.readMessage(msg3)
        assertEquals("Confirm from Alice (Msg3)", String(bobReadMsg3Payload))

        // Both parties must now be in complete state
        assertTrue("Alice handshake must be complete", aliceHandshake.isComplete())
        assertTrue("Bob handshake must be complete", bobHandshake.isComplete())

        // Mutual authentication verification:
        // Alice learned Bob's static public key; Bob learned Alice's static public key
        assertArrayEquals(bobStatic.publicKey, aliceHandshake.remoteStaticPublicKey)
        assertArrayEquals(aliceStatic.publicKey, bobHandshake.remoteStaticPublicKey)

        // Split into transport cipher states
        val (aliceSend, aliceRecv) = aliceHandshake.split()
        val (bobSend, bobRecv) = bobHandshake.split()

        // -------------------------------------------------------------------------------
        // 3. Successful Bi-Directional Authenticated Encryption & Decryption
        // -------------------------------------------------------------------------------

        // Alice sends to Bob
        val plain1 = "Top secret mesh packet payload from Alice"
        val ct1 = aliceSend.encryptWithAd("ad-test".toByteArray(), plain1.toByteArray())
        val dec1 = bobRecv.decryptWithAd("ad-test".toByteArray(), ct1)
        assertEquals(plain1, String(dec1))

        // Bob sends to Alice
        val plain2 = "Secret response from Bob over forward-secret channel"
        val ct2 = bobSend.encryptWithAd("ad-test-2".toByteArray(), plain2.toByteArray())
        val dec2 = aliceRecv.decryptWithAd("ad-test-2".toByteArray(), ct2)
        assertEquals(plain2, String(dec2))
    }

    // -----------------------------------------------------------------------------------
    // 4. Failed Handshake with Invalid or Tampered Messages
    // -----------------------------------------------------------------------------------

    @Test
    fun testFailedHandshakeWithCorruptedMessage() {
        val aliceStatic = CryptoPrimitives.generateX25519KeyPair()
        val bobStatic = CryptoPrimitives.generateX25519KeyPair()

        val aliceHandshake = NoiseHandshakeState(NoiseRole.INITIATOR, aliceStatic)
        val bobHandshake = NoiseHandshakeState(NoiseRole.RESPONDER, bobStatic)

        val msg1 = aliceHandshake.writeMessage()
        bobHandshake.readMessage(msg1)

        val msg2 = bobHandshake.writeMessage()

        // Attacker corrupts 1 byte of Message 2 (the encrypted static key or MAC tag)
        msg2[45] = (msg2[45] + 1).toByte()

        try {
            aliceHandshake.readMessage(msg2)
            fail("Handshake with corrupted Message 2 must fail MAC verification")
        } catch (e: Exception) {
            assertTrue("Expected MAC verification failure or SecurityException", e is SecurityException || e is IllegalArgumentException)
        }
    }

    // -----------------------------------------------------------------------------------
    // 5. Authentication-Tag Tampering Rejection
    // -----------------------------------------------------------------------------------

    @Test
    fun testAuthenticationTagTamperingRejection() {
        val key = ByteArray(32) { 0x42 }
        val nonce = 12L
        val ad = "bound-header-ad".toByteArray()
        val plaintext = "Uncorrupted data".toByteArray()

        val ciphertext = CryptoPrimitives.encryptChaCha20Poly1305(key, nonce, ad, plaintext)

        // Mutate last byte (part of Poly1305 16-byte tag)
        val tamperedCiphertext = ciphertext.copyOf()
        tamperedCiphertext[tamperedCiphertext.size - 1] = (tamperedCiphertext[tamperedCiphertext.size - 1] + 1).toByte()

        try {
            CryptoPrimitives.decryptChaCha20Poly1305(key, nonce, ad, tamperedCiphertext)
            fail("Decryption of tampered ciphertext must throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("MAC tag verification failed") == true)
        }

        // Mutate Associated Data (AD) -> Must also fail tag check
        val tamperedAd = "tampered-header-ad".toByteArray()
        try {
            CryptoPrimitives.decryptChaCha20Poly1305(key, nonce, tamperedAd, ciphertext)
            fail("Decryption with tampered AD must throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("MAC tag verification failed") == true)
        }
    }

    // -----------------------------------------------------------------------------------
    // 6. Replay Attack Rejection & Sliding Window Filter
    // -----------------------------------------------------------------------------------

    @Test
    fun testReplayFilterSlidingWindow() {
        val filter = ReplayFilter(windowSize = 128)

        // First packet seq = 0 -> accept
        assertTrue(filter.validateAndAdvance(0))

        // Same packet seq = 0 replayed -> reject!
        assertFalse("Replayed sequence 0 must be rejected", filter.validateAndAdvance(0))

        // In-order advancement
        assertTrue(filter.validateAndAdvance(1))
        assertTrue(filter.validateAndAdvance(2))
        assertTrue(filter.validateAndAdvance(5)) // Packet leap

        // Out-of-order within window (e.g. seq 3 and 4 arrived late)
        assertTrue("Sequence 3 arrived late within window -> accept", filter.validateAndAdvance(3))
        assertTrue("Sequence 4 arrived late within window -> accept", filter.validateAndAdvance(4))

        // Duplicate replay of late packet -> reject!
        assertFalse(filter.validateAndAdvance(3))

        // Advance window far ahead
        assertTrue(filter.validateAndAdvance(200))

        // Sequence 5 is now older than (200 - 128 = 72) -> reject!
        assertFalse("Sequence older than 128 window boundary must be dropped", filter.validateAndAdvance(5))

        // Negative sequence
        assertFalse(filter.validateAndAdvance(-1))
    }

    @Test
    fun testNoiseSessionReplayRejection() {
        val aliceSend = NoiseCipherState()
        aliceSend.initializeKey(ByteArray(32) { 0x11 })
        val bobRecv = NoiseCipherState()
        bobRecv.initializeKey(ByteArray(32) { 0x11 })

        val session = NoiseSession(
            sessionId = "test_session",
            peerHexAddress = "0x8F4C21B9A45E7D12",
            remoteStaticPublicKey = ByteArray(32),
            sendCipher = aliceSend,
            recvCipher = bobRecv
        )

        val encrypted = session.encryptPayload("Hello".toByteArray())
        assertEquals(0L, encrypted.sequenceNumber)

        val decrypted = session.decryptPayload(encrypted.sequenceNumber, encrypted.ciphertext)
        assertEquals("Hello", String(decrypted))

        // Replay same message with same sequence number
        try {
            session.decryptPayload(encrypted.sequenceNumber, encrypted.ciphertext)
            fail("Replaying same sequence number must throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Replay attack detected") == true)
        }
    }

    // -----------------------------------------------------------------------------------
    // 7. Key Rotation & Forward Secrecy Ratchet
    // -----------------------------------------------------------------------------------

    @Test
    fun testKeyRotationAndRekey() {
        val sendCipher = NoiseCipherState()
        val initialKey = ByteArray(32) { 0xAA.toByte() }
        sendCipher.initializeKey(initialKey)

        val pt = "Message before rekey".toByteArray()
        val ct = sendCipher.encryptWithAd(ByteArray(0), pt)

        // Advance ratchet with rekey()
        sendCipher.rekey()
        val keyAfterRekey = sendCipher.getKeyBytes()

        assertNotNull(keyAfterRekey)
        assertFalse("Rekeyed key must be different from initial key", initialKey.contentEquals(keyAfterRekey))
        assertEquals(32, keyAfterRekey?.size)

        // Secure destroy wipes memory
        sendCipher.destroy()
        assertFalse(sendCipher.hasKey())
        assertNull(sendCipher.getKeyBytes())
    }

    // -----------------------------------------------------------------------------------
    // 8. Full End-to-End NoiseSessionManager Integration
    // -----------------------------------------------------------------------------------

    @Test
    fun testNoiseSessionManagerEndToEnd() {
        val aliceIdManager = IdentityManager(InMemoryKeyStorage())
        val bobIdManager = IdentityManager(InMemoryKeyStorage())

        val aliceIdentity = aliceIdManager.getOrCreateIdentity("alice", "Alice")
        val bobIdentity = bobIdManager.getOrCreateIdentity("bob", "Bob")

        val aliceSessionManager = NoiseSessionManager(aliceIdManager)
        val bobSessionManager = NoiseSessionManager(bobIdManager)

        // Alice initiates handshake to Bob
        val msg1 = aliceSessionManager.startHandshake(bobIdentity.hexAddress)

        // Bob processes Msg 1, returns Msg 2
        val bobRes1 = bobSessionManager.processHandshakeMessage(aliceIdentity.hexAddress, msg1)
        assertTrue(bobRes1 is HandshakeProcessResult.Reply)
        val msg2 = (bobRes1 as HandshakeProcessResult.Reply).messageBytes

        // Alice processes Msg 2, returns Msg 3
        val aliceRes1 = aliceSessionManager.processHandshakeMessage(bobIdentity.hexAddress, msg2)
        assertTrue(aliceRes1 is HandshakeProcessResult.Reply)
        val msg3 = (aliceRes1 as HandshakeProcessResult.Reply).messageBytes

        // Bob processes Msg 3 -> Handshake established!
        val bobRes2 = bobSessionManager.processHandshakeMessage(aliceIdentity.hexAddress, msg3)
        assertTrue(bobRes2 is HandshakeProcessResult.Established)

        // Both managers now have active encrypted sessions!
        assertTrue(aliceSessionManager.hasActiveSession(bobIdentity.hexAddress))
        assertTrue(bobSessionManager.hasActiveSession(aliceIdentity.hexAddress))

        // Alice encrypts direct message
        val payload = "Autonomous offline BLE mesh communication".toByteArray()
        val enc = aliceSessionManager.encryptDirectMessage(bobIdentity.hexAddress, payload)

        // Bob decrypts direct message
        val dec = bobSessionManager.decryptDirectMessage(aliceIdentity.hexAddress, enc.sequenceNumber, enc.ciphertext)
        assertEquals("Autonomous offline BLE mesh communication", String(dec))

        // Session termination
        aliceSessionManager.terminateSession(bobIdentity.hexAddress)
        assertFalse(aliceSessionManager.hasActiveSession(bobIdentity.hexAddress))
    }

    // -----------------------------------------------------------------------------------
    // 9. RFC 7748 Official X25519 Curve25519 Test Vectors (§5.2)
    // -----------------------------------------------------------------------------------

    @Test
    fun testRfc7748X25519TestVector() {
        fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        val alicePriv = hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePubExpected = "8520f0098930a754748b7dcd32ec24401066488a0b021d603a10e8284687d004"
        val bobPriv = hexToBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPub = hexToBytes("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expectedSharedSecret = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        // Compute agreement Alice -> Bob
        val sharedAlice = CryptoPrimitives.calculateX25519(alicePriv, bobPub)
        assertEquals("RFC 7748 shared secret must match expected", expectedSharedSecret, bytesToHex(sharedAlice))
    }

    // -----------------------------------------------------------------------------------
    // 10. RFC 7693 Official BLAKE2s-256 Test Vector
    // -----------------------------------------------------------------------------------

    @Test
    fun testRfc7693Blake2sEmptyString() {
        fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
        val hash = CryptoPrimitives.blake2s(ByteArray(0))
        val expected = "69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9"
        assertEquals("BLAKE2s hash of empty input must match RFC 7693", expected, bytesToHex(hash))
    }

    // -----------------------------------------------------------------------------------
    // 11. RFC 7748 §6.1 Contributory Behavior: Weak/Low-Order Point Rejection
    // -----------------------------------------------------------------------------------

    @Test
    fun testX25519LowOrderAllZeroWeakPointRejection() {
        val alicePriv = ByteArray(32) { 0x42 }
        val lowOrderZeroPub = ByteArray(32) { 0x00 } // Point at infinity / order 8 low-order point

        try {
            CryptoPrimitives.calculateX25519(alicePriv, lowOrderZeroPub)
            fail("X25519 agreement with all-zero low-order point must throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue("Expected contributory check failure message", e.message?.contains("contributory check failed") == true)
        }
    }

    // -----------------------------------------------------------------------------------
    // 12. Nonce Validation: Negative Nonce Rejection
    // -----------------------------------------------------------------------------------

    @Test
    fun testNegativeNonceRejection() {
        val key = ByteArray(32) { 0x11 }
        val pt = "Test data".toByteArray()

        try {
            CryptoPrimitives.encryptChaCha20Poly1305(key, -10L, ByteArray(0), pt)
            fail("Negative nonces must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Nonce must be non-negative") == true)
        }
    }

    // -----------------------------------------------------------------------------------
    // 13. Out-of-Order Packet Rekeying & Forward Secrecy Ratchet
    // -----------------------------------------------------------------------------------

    @Test
    fun testOutOfOrderPacketRekeyingAcrossEpochs() {
        val sendCipher = NoiseCipherState()
        sendCipher.initializeKey(ByteArray(32) { 0xAA.toByte() })
        val recvCipher = NoiseCipherState()
        recvCipher.initializeKey(ByteArray(32) { 0xAA.toByte() })

        val aliceSession = NoiseSession(
            sessionId = "sess_alice",
            peerHexAddress = "0xBBBB0002",
            remoteStaticPublicKey = ByteArray(32),
            sendCipher = sendCipher,
            recvCipher = NoiseCipherState()
        )

        val bobSession = NoiseSession(
            sessionId = "sess_bob",
            peerHexAddress = "0xAAAA0001",
            remoteStaticPublicKey = ByteArray(32),
            sendCipher = NoiseCipherState(),
            recvCipher = recvCipher
        )

        // 1. Alice encrypts 999 messages in epoch 0
        var lastEnc: EncryptedMessagePayload? = null
        for (i in 0 until 999) {
            lastEnc = aliceSession.encryptPayload("Msg $i".toByteArray())
        }
        assertEquals(998L, lastEnc?.sequenceNumber)

        // Bob decrypts message 998
        val dec998 = bobSession.decryptPayload(lastEnc!!.sequenceNumber, lastEnc.ciphertext)
        assertEquals("Msg 998", String(dec998))

        // 2. Alice encrypts message 999 (last of epoch 0)
        val enc999 = aliceSession.encryptPayload("Msg 999 (Epoch 0)".toByteArray())
        assertEquals(999L, enc999.sequenceNumber)

        // 3. Alice encrypts message 1000 (first of epoch 1)
        val enc1000 = aliceSession.encryptPayload("Msg 1000 (Epoch 1)".toByteArray())
        assertEquals(1000L, enc1000.sequenceNumber)

        // 4. Alice encrypts message 1001 (second of epoch 1)
        val enc1001 = aliceSession.encryptPayload("Msg 1001 (Epoch 1)".toByteArray())
        assertEquals(1001L, enc1001.sequenceNumber)

        // OUT-OF-ORDER TEST: Packet 1001 arrives at Bob BEFORE packet 1000!
        val dec1001 = bobSession.decryptPayload(enc1001.sequenceNumber, enc1001.ciphertext)
        assertEquals("Msg 1001 (Epoch 1)", String(dec1001))

        // LATE PACKET TEST: Packet 999 arrives LATE after epoch 1 has started!
        val dec999 = bobSession.decryptPayload(enc999.sequenceNumber, enc999.ciphertext)
        assertEquals("Msg 999 (Epoch 0)", String(dec999))

        // IN-BETWEEN PACKET TEST: Packet 1000 arrives!
        val dec1000 = bobSession.decryptPayload(enc1000.sequenceNumber, enc1000.ciphertext)
        assertEquals("Msg 1000 (Epoch 1)", String(dec1000))
    }

    // -----------------------------------------------------------------------------------
    // 14. Handshake Glare Race Condition Resolution
    // -----------------------------------------------------------------------------------

    @Test
    fun testHandshakeGlareSimultaneousInitiation() {
        val aliceId = IdentityManager(InMemoryKeyStorage())
        val bobId = IdentityManager(InMemoryKeyStorage())

        val alice = aliceId.getOrCreateIdentity("alice", "Alice")
        val bob = bobId.getOrCreateIdentity("bob", "Bob")

        val aliceManager = NoiseSessionManager(aliceId)
        val bobManager = NoiseSessionManager(bobId)

        // Simultaneous initiation: Alice starts to Bob, Bob starts to Alice
        val aliceMsg1 = aliceManager.startHandshake(bob.hexAddress)
        val bobMsg1 = bobManager.startHandshake(alice.hexAddress)

        // Alice receives Bob's Msg 1; Bob receives Alice's Msg 1
        // One of them will yield and reply based on deterministic tie-breaking!
        val resAlice = aliceManager.processHandshakeMessage(bob.hexAddress, bobMsg1)
        val resBob = bobManager.processHandshakeMessage(alice.hexAddress, aliceMsg1)

        val yieldingReply = when {
            resAlice is HandshakeProcessResult.Reply -> {
                // Alice yielded, sent Msg 2 to Bob
                val msg2 = resAlice.messageBytes
                val bobReply = bobManager.processHandshakeMessage(alice.hexAddress, msg2)
                assertTrue("Bob must complete with EstablishedWithReply", bobReply is HandshakeProcessResult.EstablishedWithReply)
                val msg3 = (bobReply as HandshakeProcessResult.EstablishedWithReply).messageBytes
                val aliceFinal = aliceManager.processHandshakeMessage(bob.hexAddress, msg3)
                assertTrue("Alice must complete with Established", aliceFinal is HandshakeProcessResult.Established)
                true
            }
            resBob is HandshakeProcessResult.Reply -> {
                // Bob yielded, sent Msg 2 to Alice
                val msg2 = resBob.messageBytes
                val aliceReply = aliceManager.processHandshakeMessage(bob.hexAddress, msg2)
                assertTrue("Alice must complete with EstablishedWithReply", aliceReply is HandshakeProcessResult.EstablishedWithReply)
                val msg3 = (aliceReply as HandshakeProcessResult.EstablishedWithReply).messageBytes
                val bobFinal = bobManager.processHandshakeMessage(alice.hexAddress, msg3)
                assertTrue("Bob must complete with Established", bobFinal is HandshakeProcessResult.Established)
                true
            }
            else -> false
        }

        assertTrue("One node must yield and successfully complete handshake glare", yieldingReply)
        assertTrue(aliceManager.hasActiveSession(bob.hexAddress))
        assertTrue(bobManager.hasActiveSession(alice.hexAddress))
    }

    // -----------------------------------------------------------------------------------
    // 15. Cross-Key Mutual Identity Proof Verification & Spoofing Rejection
    // -----------------------------------------------------------------------------------

    @Test
    fun testIdentityProofVerificationAndTamperingRejection() {
        val storage = InMemoryKeyStorage()
        val idManager = IdentityManager(storage)
        val identity = idManager.getOrCreateIdentity("carol", "Carol")

        val transcriptHash = ByteArray(32) { 0x55 }
        val validProof = IdentityProofHelper.createProof(identity, transcriptHash)
        assertEquals(IdentityProofHelper.PROOF_SIZE, validProof.size)

        // 1. Valid proof verification
        val valid = IdentityProofHelper.verifyProof(
            proofBytes = validProof,
            expectedPeerHex = identity.hexAddress,
            remoteStaticKey = identity.x25519KeyPair.publicKey,
            handshakeHash = transcriptHash,
            strictAddressCheck = true
        )
        assertTrue("Authentic proof must verify successfully", valid)

        // 2. Tampered signature
        val tamperedProof = validProof.copyOf()
        tamperedProof[60] = (tamperedProof[60] + 1).toByte()
        val invalidSig = IdentityProofHelper.verifyProof(
            proofBytes = tamperedProof,
            expectedPeerHex = identity.hexAddress,
            remoteStaticKey = identity.x25519KeyPair.publicKey,
            handshakeHash = transcriptHash,
            strictAddressCheck = true
        )
        assertFalse("Tampered proof must fail signature check", invalidSig)

        // 3. Spoofed address (different expected peer)
        val invalidAddr = IdentityProofHelper.verifyProof(
            proofBytes = validProof,
            expectedPeerHex = "0x9999888877776666",
            remoteStaticKey = identity.x25519KeyPair.publicKey,
            handshakeHash = transcriptHash,
            strictAddressCheck = true
        )
        assertFalse("Proof for different expected address must fail address binding", invalidAddr)
    }

    // -----------------------------------------------------------------------------------
    // 16. EncryptedKeyStorage Serialization & Deserialization
    // -----------------------------------------------------------------------------------

    @Test
    fun testEncryptedKeyStorageSerializationRoundtrip() {
        val xPair = CryptoPrimitives.generateX25519KeyPair()
        val edPair = CryptoPrimitives.generateEd25519KeyPair()

        val original = NodeIdentity(
            username = "eve_node",
            displayName = "Eve Vanguard",
            hexAddress = "0x1122334455667788",
            keyFingerprint = "HEX:11:22:33:44:55:66:77:88",
            x25519KeyPair = xPair,
            ed25519KeyPair = edPair,
            createdAtEpochMs = 1715000000000L
        )

        val bytes = EncryptedKeyStorage.Serializer.serializeIdentity(original)
        val restored = EncryptedKeyStorage.Serializer.deserializeIdentity(bytes)

        assertEquals(original.username, restored.username)
        assertEquals(original.displayName, restored.displayName)
        assertEquals(original.hexAddress, restored.hexAddress)
        assertEquals(original.keyFingerprint, restored.keyFingerprint)
        assertArrayEquals(original.x25519KeyPair.privateKey, restored.x25519KeyPair.privateKey)
        assertArrayEquals(original.x25519KeyPair.publicKey, restored.x25519KeyPair.publicKey)
        assertArrayEquals(original.ed25519KeyPair.privateKey, restored.ed25519KeyPair.privateKey)
        assertArrayEquals(original.ed25519KeyPair.publicKey, restored.ed25519KeyPair.publicKey)
        assertEquals(original.createdAtEpochMs, restored.createdAtEpochMs)
    }
}

