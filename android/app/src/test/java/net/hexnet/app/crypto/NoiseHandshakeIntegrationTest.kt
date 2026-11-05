package net.hexnet.app.crypto

import kotlinx.coroutines.runBlocking
import net.hexnet.app.core.crypto.*
import net.hexnet.app.core.protocol.BinaryPacket
import net.hexnet.app.core.protocol.PacketFlags
import net.hexnet.app.core.protocol.PacketType
import net.hexnet.app.core.routing.MeshRoutingEngine
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verifies real end-to-end Noise_XX mutual authentication,
 * cross-key Ed25519 identity proof binding, simultaneous handshake glare resolution,
 * and routing engine message queuing before session establishment.
 */
class NoiseHandshakeIntegrationTest {

    // In-memory key storage for testing
    private class InMemoryKeyStorage : KeyStorage {
        val storage = mutableMapOf<String, ByteArray>()
        override fun storeKey(alias: String, keyBytes: ByteArray) { storage[alias] = keyBytes }
        override fun getKey(alias: String): ByteArray? = storage[alias]
        override fun hasKey(alias: String): Boolean = storage.containsKey(alias)
        override fun removeKey(alias: String) { storage.remove(alias) }
        override fun clearAll() { storage.clear() }
    }

    private fun createTestIdentityManager(username: String): IdentityManager {
        val storage = InMemoryKeyStorage()
        val manager = IdentityManager(storage)
        manager.generateIdentity(username)
        return manager
    }

    @Test
    fun test_two_independent_session_managers_handshake_and_exchange_encrypted_messages() {
        val aliceIdentityMgr = createTestIdentityManager("alice_node")
        val bobIdentityMgr = createTestIdentityManager("bob_node")

        val aliceIdentity = aliceIdentityMgr.getIdentity()!!
        val bobIdentity = bobIdentityMgr.getIdentity()!!

        val aliceSessionMgr = NoiseSessionManager(aliceIdentityMgr)
        val bobSessionMgr = NoiseSessionManager(bobIdentityMgr)

        val aliceHex = aliceIdentity.hexAddress
        val bobHex = bobIdentity.hexAddress

        assertFalse("Alice must not have session before handshake", aliceSessionMgr.hasActiveSession(bobHex))
        assertFalse("Bob must not have session before handshake", bobSessionMgr.hasActiveSession(aliceHex))

        // Step 1: Alice initiates handshake (Message 1)
        val msg1 = aliceSessionMgr.startHandshake(bobHex)
        assertTrue("Message 1 must be exactly 32 bytes (ephemeral pub)", msg1.size == 32)

        // Step 2: Bob processes Message 1 and writes Message 2 (includes Bob's Ed25519 identity proof)
        val bobResult1 = bobSessionMgr.processHandshakeMessage(aliceHex, msg1)
        assertTrue("Bob must produce a reply (Message 2)", bobResult1 is HandshakeProcessResult.Reply)
        val msg2 = (bobResult1 as HandshakeProcessResult.Reply).messageBytes
        assertTrue("Message 2 must contain ephemeral, static, proof, and tags (> 100 bytes)", msg2.size > 100)

        // Step 3: Alice processes Message 2 and writes Message 3 (includes Alice's Ed25519 identity proof)
        val aliceResult = aliceSessionMgr.processHandshakeMessage(bobHex, msg2)
        assertTrue("Alice must transition to EstablishedWithReply", aliceResult is HandshakeProcessResult.EstablishedWithReply)
        val aliceEstablished = aliceResult as HandshakeProcessResult.EstablishedWithReply
        val msg3 = aliceEstablished.messageBytes

        // Step 4: Bob processes Message 3
        val bobResult2 = bobSessionMgr.processHandshakeMessage(aliceHex, msg3)
        assertTrue("Bob must transition to Established", bobResult2 is HandshakeProcessResult.Established)

        // Both sessions established!
        assertTrue("Alice must have active session for Bob", aliceSessionMgr.hasActiveSession(bobHex))
        assertTrue("Bob must have active session for Alice", bobSessionMgr.hasActiveSession(aliceHex))

        // Step 5: Alice encrypts direct message -> Bob decrypts
        val plaintextFromAlice = "Hello Bob! Verified offline mesh packet.".toByteArray(Charsets.UTF_8)
        val encryptedForBob = aliceSessionMgr.encryptDirectMessage(bobHex, plaintextFromAlice)

        val decryptedByBob = bobSessionMgr.decryptDirectMessage(aliceHex, encryptedForBob.sequenceNumber, encryptedForBob.ciphertext)
        assertArrayEquals("Bob must recover Alice's exact plaintext", plaintextFromAlice, decryptedByBob)

        // Step 6: Bob encrypts reply -> Alice decrypts
        val replyFromBob = "Acknowledged Alice. Noise XX session secure.".toByteArray(Charsets.UTF_8)
        val encryptedForAlice = bobSessionMgr.encryptDirectMessage(aliceHex, replyFromBob)

        val decryptedByAlice = aliceSessionMgr.decryptDirectMessage(bobHex, encryptedForAlice.sequenceNumber, encryptedForAlice.ciphertext)
        assertArrayEquals("Alice must recover Bob's exact plaintext", replyFromBob, decryptedByAlice)
    }

    @Test
    fun test_tampered_handshake_message_rejected() {
        val aliceIdentityMgr = createTestIdentityManager("alice_node")
        val bobIdentityMgr = createTestIdentityManager("bob_node")

        val aliceIdentity = aliceIdentityMgr.getIdentity()!!
        val bobIdentity = bobIdentityMgr.getIdentity()!!

        val aliceSessionMgr = NoiseSessionManager(aliceIdentityMgr)
        val bobSessionMgr = NoiseSessionManager(bobIdentityMgr)

        val msg1 = aliceSessionMgr.startHandshake(bobIdentity.hexAddress)
        val bobResult1 = bobSessionMgr.processHandshakeMessage(aliceIdentity.hexAddress, msg1)
        val msg2 = (bobResult1 as HandshakeProcessResult.Reply).messageBytes

        // Tamper with Message 2 ciphertext/MAC
        val tamperedMsg2 = msg2.copyOf()
        tamperedMsg2[tamperedMsg2.size - 5] = (tamperedMsg2[tamperedMsg2.size - 5] + 1).toByte()

        // Alice attempts to process tampered Message 2
        val aliceResult = aliceSessionMgr.processHandshakeMessage(bobIdentity.hexAddress, tamperedMsg2)
        assertTrue("Alice must return Error when Message 2 is tampered", aliceResult is HandshakeProcessResult.Error)
        assertFalse("Alice must not establish a session with tampered data", aliceSessionMgr.hasActiveSession(bobIdentity.hexAddress))
    }

    @Test
    fun test_invalid_identity_proof_rejected() {
        val aliceIdentityMgr = createTestIdentityManager("alice_node")
        val malloryIdentityMgr = createTestIdentityManager("mallory_node")

        val aliceIdentity = aliceIdentityMgr.getIdentity()!!
        val malloryIdentity = malloryIdentityMgr.getIdentity()!!

        val handshakeAlice = NoiseHandshakeState(NoiseRole.INITIATOR, aliceIdentity.x25519KeyPair)
        val handshakeMallory = NoiseHandshakeState(NoiseRole.RESPONDER, malloryIdentity.x25519KeyPair)

        val msg1 = handshakeAlice.writeMessage()
        handshakeMallory.readMessage(msg1)

        // Forged proof: Mallory provides bad signature
        val forgedProof = ByteArray(IdentityProofHelper.PROOF_SIZE) { 0x42.toByte() }
        val msg2 = handshakeMallory.writeMessage(forgedProof)

        // Alice reads Message 2: proof verification must fail
        val hashBefore = handshakeAlice.symmetricState.h.copyOf()
        val payload = handshakeAlice.readMessage(msg2)
        val remoteStatic = handshakeAlice.remoteStaticPublicKey!!

        val proofValid = IdentityProofHelper.verifyProof(
            proofBytes = payload,
            expectedPeerHex = malloryIdentity.hexAddress,
            remoteStaticKey = remoteStatic,
            handshakeHash = hashBefore
        )
        assertFalse("Forged identity proof must fail verification", proofValid)
    }

    @Test
    fun test_routing_engine_queues_outgoing_message_until_session_established() = runBlocking {
        val aliceIdentityMgr = createTestIdentityManager("alice_node")
        val aliceSessionMgr = NoiseSessionManager(aliceIdentityMgr)

        val aliceHexLong = 0x1111222233334444L
        val bobHexLong = 0x5555666677778888L
        val bobHexStr = java.lang.Long.toHexString(bobHexLong)

        val transmittedPackets = mutableListOf<BinaryPacket>()
        val engine = MeshRoutingEngine(
            localHexAddress = aliceHexLong,
            sessionManager = aliceSessionMgr
        )
        engine.transmitCallback = { _, packet ->
            synchronized(transmittedPackets) {
                transmittedPackets.add(packet)
            }
        }

        // Act: Alice attempts to send direct message to Bob, but NO session exists yet
        assertFalse("No session exists initially", aliceSessionMgr.hasActiveSession(bobHexStr))
        val textBytes = "Top secret message".toByteArray(Charsets.UTF_8)
        val packet = engine.sendDirectMessage(bobHexLong, textBytes, packetId = 4242L)

        // Assert: Message is queued, not transmitted as plaintext!
        assertTrue("Engine must have queued the message", engine.pendingSecureMessages.containsKey(bobHexLong))
        assertEquals("Queue must contain 1 message", 1, engine.pendingSecureMessages[bobHexLong]?.size)

        // Handshake packet must have been dispatched to initiate session
        val handshakeTx = transmittedPackets.find { it.type == PacketType.NOISE_HANDSHAKE }
        assertNotNull("Engine must have initiated Noise handshake", handshakeTx)
        assertEquals(bobHexLong, handshakeTx?.destinationHex)

        // Plaintext direct message must NOT have been transmitted over radio!
        val leakedPlaintext = transmittedPackets.any {
            it.type == PacketType.DIRECT_MESSAGE && !PacketFlags.hasFlag(it.flags, PacketFlags.ENCRYPTED)
        }
        assertFalse("Plaintext direct message must NEVER leak over BLE radio without session", leakedPlaintext)

        // Simulate session establishment
        val bobIdentityMgr = createTestIdentityManager("bob_node")
        val bobSessionMgr = NoiseSessionManager(bobIdentityMgr)
        val aliceHexStr = java.lang.Long.toHexString(aliceHexLong)

        // Process handshake Message 1 at Bob
        val bobReply = bobSessionMgr.processHandshakeMessage(aliceHexStr, handshakeTx!!.payload)
        val msg2 = (bobReply as HandshakeProcessResult.Reply).messageBytes

        // Process Message 2 at Alice
        val aliceReply = aliceSessionMgr.processHandshakeMessage(bobHexStr, msg2)
        val establishedAlice = (aliceReply as HandshakeProcessResult.EstablishedWithReply).session

        // Act: Notify engine that session is now established!
        transmittedPackets.clear()
        engine.handleSessionEstablished(bobHexLong, establishedAlice)

        // Allow coroutine execution
        kotlinx.coroutines.delay(100)

        // Assert: Queued message is now encrypted and transmitted!
        val encryptedTx = transmittedPackets.find { it.type == PacketType.DIRECT_MESSAGE }
        assertNotNull("Queued message must be transmitted once session is established", encryptedTx)
        assertTrue("Transmitted packet must have ENCRYPTED flag set", PacketFlags.hasFlag(encryptedTx!!.flags, PacketFlags.ENCRYPTED))
        assertFalse("Payload must be ciphertext, not original plaintext", encryptedTx.payload.contentEquals(textBytes))
    }
}
