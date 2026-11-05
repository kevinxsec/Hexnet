# Hexnet — Decentralized Offline Mesh for Android

Hexnet is an Android-native, offline-first communication platform designed to enable secure peer-to-peer messaging via Bluetooth Low Energy (BLE) multi-hop mesh networking without reliance on central servers, internet connectivity, or cell infrastructure.

---

## Architecture Overview

Hexnet follows modern Android Architecture Components adhering to Clean Architecture principles:

```
net.hexnet.app/
├── HexnetApplication.kt              # App initialization, database & datastore singletons, mesh service triggers
├── MainActivity.kt                   # Single Activity Compose entry point with BLE permission launcher
├── navigation/                       # Type-safe Android Compose Navigation
│   ├── Screen.kt                     # Destination routes & icons
│   └── HexnetNavigation.kt           # NavigationBar & state host
├── core/
│   ├── transport/ble/                # [PHASE 1 COMPLETE] Native Bluetooth LE Stack
│   │   ├── BleConstants.kt           # 128-bit UUIDs, MTUs (517B), manufacturer IDs & timeouts
│   │   ├── GattOperationQueue.kt     # Serialized FIFO queue for asynchronous Android BluetoothGatt calls
│   │   ├── HexnetGattServer.kt       # Peripheral role: GATT server, service setup & BLE advertising
│   │   ├── HexnetGattClientPool.kt   # Central role: connection pool (max 7), MTU negotiation & writes
│   │   ├── HexnetBleScanner.kt       # Central scanner: debouncing & manufacturer data parsing
│   │   ├── BlePermissionManager.kt   # API 26–35 runtime permissions (neverForLocation flag)
│   │   └── HexnetMeshForegroundService.kt # Android 14+ connectedDevice foreground service
│   ├── protocol/                     # [PHASE 2 COMPLETE] Binary Wire Protocol & Fragmentation
│   │   ├── PacketType.kt             # ANNOUNCE, DIRECT_MSG, ROOM_BROADCAST, LINK_ACK, ROUTE_REQ
│   │   ├── BinaryPacket.kt           # Big-Endian 34B header + CRC32 checksum serializer/deserializer
│   │   ├── compression/              # Deflate/Inflate compression utility (>100B threshold)
│   │   └── fragmentation/            # 13B fragment headers, MTU-safe chunking & timeout reassembly
│   ├── crypto/                       # [PHASE 3 COMPLETE] Cryptographic Identity & Security Engine
│   │   ├── CryptoPrimitives.kt       # RFC 7748 X25519, RFC 8032 Ed25519, RFC 8439 ChaCha20-Poly1305, BLAKE2s
│   │   ├── NoiseCipherState.kt       # 32B key + 64-bit nonces, encryptWithAd, decryptWithAd, rekey()
│   │   ├── NoiseSymmetricState.kt    # Chaining key (ck), handshake hash (h), mixKey, mixHash, split()
│   │   ├── NoiseHandshakeState.kt    # Noise_XX_25519_ChaChaPoly_BLAKE2s state machine (3-step handshake)
│   │   ├── ReplayFilter.kt           # 128-packet sliding-window bitmask (RFC 6479 replay attack protection)
│   │   ├── NoiseSession.kt           # Bidirectional transport session, AEAD Associated Data, forward secrecy
│   │   ├── IdentityManager.kt        # Persistent Ed25519 + X25519 identities, fingerprint & signing
│   │   └── NoiseSessionManager.kt    # Concurrent session pool by peer Hex Address & automated handshakes
│   ├── model/                        # Domain entities (Message, Peer, ChatRoom, MeshPacket)
│   ├── designsystem/                 # Hexnet design tokens, colors & typography
│   │   ├── theme/                    # Hexnet Dark & Light Material3 schemes
│   │   └── components/               # HexAvatar, DeliveryStatusIcon, SignalMeter
│   └── database/                     # Room local persistence
│       ├── entity/                   # Room entities
│       ├── dao/                      # Room DAOs (Messages, Peers, Rooms, Events)
│       └── HexnetDatabase.kt         # Database instance & type converters
├── data/
│   ├── local/                        # Jetpack DataStore Preferences
│   └── repository/                   # Identity, Messaging, Contacts, Mesh Repositories
├── domain/                           # Pure business use cases & routing algorithms
│   ├── ValidateUsernameUseCase.kt    # Username syntax & reserve validation
│   ├── GenerateHexIdentityUseCase.kt # Deterministic SHA-256 local hex identity
│   └── MeshRoutingEngine.kt          # Loop suppression, TTL & relay forwarding
└── feature/                          # UI features built with Jetpack Compose & MVVM
    ├── onboarding/                   # Identity onboarding & validation
    ├── dashboard/                    # Real-time node & mesh metrics
    ├── chats/                        # Direct messages & public rooms
    ├── conversation/                 # Threaded messaging & delivery states
    ├── peers/                        # Discovered BLE peers & contact trust
    ├── mesh/                         # Visual topology & live routing logs
    └── settings/                     # Cryptographic profile & routing limits
```

---

## Cryptographic Security Specification (Phase 3)

### 1. Protocol Parameters
* **Handshake Profile**: `Noise_XX_25519_ChaChaPoly_BLAKE2s`
  * **DH**: Curve25519 / X25519 (RFC 7748)
  * **Cipher**: ChaCha20-Poly1305 AEAD (RFC 8439) with 128-bit authentication tags
  * **Hash**: BLAKE2s-256 (32 bytes output, block size 64 bytes)
  * **Identity Signatures**: Ed25519 (RFC 8032)
* **Handshake Sequence**:
  ```
  Initiator                     Responder
    |                              |
    | ---------> Msg 1 ----------> |  -> e
    | <--------- Msg 2 <---------- |  <- e, ee, s, es
    | ---------> Msg 3 ----------> |  -> s, se
    |                              |
    | == Transport Session Active =|  (Split into sendCipher / recvCipher)
  ```
  * **Identity Hiding**: Static identity keys ($s$) are transmitted encrypted under ephemeral keys; passive eavesdroppers on BLE cannot learn who is communicating.
  * **Mutual Authentication**: Both initiator and responder cryptographically prove ownership of their static keys during the handshake.
  * **Forward Secrecy**: Compromise of long-term static identity keys does not compromise past communication sessions.

### 2. Transport Encryption & Replay Defense
* **Associated Data Binding**: Every encrypted message binds its 64-bit monotonic sequence number into ChaCha20-Poly1305's Additional Authenticated Data (AAD), preventing sequence tampering or header detachment.
* **Sliding-Window Replay Filter**: A 128-packet bitmask tracks received sequence numbers. Replayed packets and packets older than the window boundary are rejected.
* **Automatic Ratchet Rekeying**: Sessions automatically advance their forward-secrecy ratchet every 1,000 transmitted messages (`rekey()`).

---

## Mesh Routing Engine Specification (Phase 4)

### 1. Hybrid Routing Architecture
* **Directed Reverse-Path Routing**: When nodes observe packets flowing across the network, they record reverse routes (`RouteEntry(destinationHex -> nextHopAddress, hopDistance, expiresEpochMs)`). Unicast messages are routed strictly to known next hops rather than flooded.
* **Controlled Flood Discovery**: If no route is known in the route table, packets are broadcast with `DEFAULT_TTL` (5, max 7) to discover paths. When responses flow back, reverse paths are established on all nodes along the chain.
* **Bounded LRU Deduplication**: A 2,048-entry bounded LRU cache tracks `packetId` and hashes to drop duplicate copies and suppress packet reflection loops.
* **TTL & Hop Count**: Decremented at each relay hop; packets reaching TTL $\le 1$ are dropped before transmission.

### 2. Reliable Delivery & Acknowledgements
* **Hop-by-Hop Link ACKs**: Receivers transmit a lightweight 1-hop `LINK_ACK` directly back to the transmitter. Transmitters retry up to 3 times with exponential backoff on missing ACKs before invalidating routes or failing.
* **End-to-End Delivery ACKs**: When a `DIRECT_MESSAGE` reaches its final destination, the destination generates a signed/session E2E ACK back to the originator.
* **Delivery State Contract**: Intermediate relay nodes mark packet state as `RELAYED`. Only the origin node transitions state to `DELIVERED` upon receiving the destination's End-to-End ACK receipt!
* **Durable Store-and-Forward**: Room entities (`OutboundPacketEntity` and `RelayPacketEntity`) persist packets awaiting ACKs or awaiting downstream peer reconnections. When a disconnected neighbor reconnects, queued packets are automatically flushed.
* **Relay Payload Confidentiality**: Relays only inspect and mutate the 34-byte outer header (`ttl`, `hopCount`). The ChaCha20-Poly1305 payload ciphertext remains undecryptable by intermediate relays.

---

## Hostile Protocol Security, Fuzzing & Resource Limits (Phase 7)

Hexnet has undergone extensive fuzzing, boundary testing, and adversarial simulation across 20 attack vectors:

### 1. Hardened Bounds & Resource Caps
* **Max Safe Packet Payload**: Clamped to 8,192 bytes (`MAX_SAFE_PACKET_PAYLOAD`). Prevents radio saturation and heap exhaustion.
* **Max Allowed Fragments**: Capped at 64 fragments per packet (`MAX_ALLOWED_FRAGMENTS`), restricting worst-case reassembly allocation per packet to $< 64 \text{ KB}$ and $< 8 \text{ MB}$ system-wide.
* **Fragment Payload Limit**: 1,024 bytes maximum per fragment (`MAX_FRAGMENT_PAYLOAD_SIZE`).
* **In-Flight Reassembly Buffers**: Strictly capped at 128 concurrent buffers (`MAX_IN_FLIGHT_BUFFERS`) with 10-second timeout LRU eviction.
* **Directed Routing Table**: Capacity bounded at 256 routes (`MAX_ROUTES`). Invalidates expired routes and evicts oldest entries under saturation.
* **Route Poisoning Defense**: Only routes with hop distances $1 \le h \le 7$ are accepted. Route announcements claiming $0$, negative, or $>7$ hops are rejected immediately.
* **Neighbor Table**: Capped at 64 active 1-hop BLE neighbors (`MAX_NEIGHBORS`).
* **Connection Pool Bounds**: GATT Server enforces `MAX_CONCURRENT_GATT_CONNECTIONS = 7` to avoid Android Bluedroid stack exhaustion.
* **Broadcast Storm & Flooding Rate Limiting**: Token-bucket rate limiter per incoming peer interface (30 packets/second burst allowance) automatically throttles and drops malicious flood traffic.

### 2. Automated Test Coverage
Automated security, property-based, and fuzz testing is implemented in `ProtocolSecurityFuzzTest.kt`:
* Deterministic property-based roundtrip serialization across 100 pseudo-random valid packets.
* Byte-stream fuzzing feeding 500 arbitrary byte arrays (0 to 10,000 bytes) without unhandled exceptions or crashes.
* Bit-flip mutation testing across 100 corrupt headers and payloads verifying immediate CRC32/framing rejection.
* Out-of-order, duplicate, and fragmented packet reassembly verification.
* Reassembly DoS buffer flooding resilience against 1,000 distinct concurrent incomplete fragments.
* Boundary-value tests: 0-byte payloads, 8,192-byte safe payloads, 8,193-byte rejection, negative TTLs, and clamped TTLs.

---

## Building and Testing the Android Project

### Prerequisites
- Android Studio Ladybug (2024.2+) or newer
- JDK 17 or higher
- Android SDK 35 (minSdk 26)

### CLI Commands
```bash
# Run unit tests across domain, protocol, fragmentation, cryptography, lifecycle, and security fuzzing
./gradlew testDebugUnitTest

# Assemble Debug APK
./gradlew assembleDebug

# Install on connected physical device or emulator
./gradlew installDebug
```
