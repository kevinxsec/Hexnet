package net.hexnet.app.core.transport.ble

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service ensuring the Hexnet BLE mesh network remains active
 * for packet relaying and peer discovery even when the app is minimized or the screen is locked.
 *
 * Implements:
 * - Dynamic BroadcastReceiver for Bluetooth adapter state changes and Screen ON/OFF
 * - Adaptive duty-cycling in background and screen-off states
 * - Process recreation and START_STICKY recovery from IdentityManager
 * - Interactive notification with "Stop Mesh" action and MainActivity launch PendingIntent
 * - Clean GATT connection teardown and resource cleanup
 */
class HexnetMeshForegroundService : Service() {

    companion object {
        private const val TAG = "HexnetMeshService"
        private const val NOTIFICATION_CHANNEL_ID = "hexnet_mesh_service_channel"
        private const val NOTIFICATION_ID = 4858 // 'H' 'X'

        const val ACTION_START_MESH = "net.hexnet.app.action.START_MESH"
        const val ACTION_STOP_MESH = "net.hexnet.app.action.STOP_MESH"
        const val EXTRA_LOCAL_HEX_ADDRESS = "extra_local_hex_address"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var gattServer: HexnetGattServer? = null
    private var clientPool: HexnetGattClientPool? = null
    private var scanner: HexnetBleScanner? = null
    private var reassembler: net.hexnet.app.core.protocol.fragmentation.PacketReassembler? = null
    var routingEngine: net.hexnet.app.core.routing.MeshRoutingEngine? = null
        private set

    @Volatile private var isRunning = false
    @Volatile private var isBluetoothPaused = false
    @Volatile private var isScreenOff = false
    private var isReceiverRegistered = false
    private var currentLocalHex: String? = null

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            Log.w(TAG, "Bluetooth turning off or disabled. Pausing Hexnet mesh subsystems.")
                            handleBluetoothDisabled()
                        }
                        BluetoothAdapter.STATE_ON -> {
                            Log.i(TAG, "Bluetooth enabled. Resuming Hexnet mesh subsystems.")
                            handleBluetoothEnabled()
                        }
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF detected. Switching BLE scanner to low-power duty cycling.")
                    handleScreenOff()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen ON detected. Restoring continuous BLE scanning.")
                    handleScreenOn()
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): HexnetMeshForegroundService = this@HexnetMeshForegroundService
        fun getRoutingEngine(): net.hexnet.app.core.routing.MeshRoutingEngine? = this@HexnetMeshForegroundService.routingEngine
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerSystemReceivers()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_MESH

        when (action) {
            ACTION_START_MESH -> {
                // Recover identity from intent extra, or retrieve from IdentityManager if restarted by OS via START_STICKY
                val localHex = intent?.getStringExtra(EXTRA_LOCAL_HEX_ADDRESS)
                    ?: (application as? net.hexnet.app.HexnetApplication)?.identityManager?.getIdentity()?.hexAddress
                    ?: "0xHEXNET_NODE"

                currentLocalHex = localHex
                startMeshSubsystems(localHex)
            }
            ACTION_STOP_MESH -> {
                stopMeshSubsystems()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun registerSystemReceivers() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(systemEventReceiver, filter)
        isReceiverRegistered = true
        Log.d(TAG, "Registered system event receivers (Bluetooth state, Screen ON/OFF)")
    }

    private fun unregisterSystemReceivers() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(systemEventReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering system receiver", e)
            }
            isReceiverRegistered = false
        }
    }

    private fun startMeshSubsystems(localHex: String) {
        if (isRunning) return

        val notification = buildForegroundNotification("Hexnet Mesh Active · Relaying BLE packets")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Verify runtime permissions before initializing Bluetooth operations
        if (!BlePermissionManager.hasAllPermissions(this)) {
            Log.e(TAG, "Cannot start mesh subsystems: Missing required BLE permissions")
            updateNotification("Hexnet Mesh Paused · Missing required Bluetooth permissions")
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (!BlePermissionManager.isBluetoothEnabled(this)) {
            Log.w(TAG, "Bluetooth adapter is disabled. Postponing BLE subsystem start.")
            isBluetoothPaused = true
            updateNotification("Hexnet Mesh Paused · Bluetooth is disabled")
            return
        }

        val hexApp = application as? net.hexnet.app.HexnetApplication
        val localHexLong = try {
            val clean = localHex.removePrefix("0x").take(16).padEnd(16, '0')
            clean.toLong(16)
        } catch (e: Exception) {
            0x4845584E45543031L // 'HEXNET01'
        }

        // 1. Initialize Reassembly & Mesh Routing Engine
        reassembler = net.hexnet.app.core.protocol.fragmentation.PacketReassembler()
        val engine = net.hexnet.app.core.routing.MeshRoutingEngine(
            localHexAddress = localHexLong,
            sessionManager = hexApp?.noiseSessionManager,
            outboundQueueDao = hexApp?.database?.outboundQueueDao(),
            relayQueueDao = hexApp?.database?.relayQueueDao(),
            scope = serviceScope
        )
        this.routingEngine = engine

        // Wire transmission from routing engine through fragmentation into BLE radios
        engine.transmitCallback = { targetDeviceAddress, packet ->
            val rawPacket = net.hexnet.app.core.protocol.PacketSerializer.serialize(packet)
            val mtu = if (targetDeviceAddress != null) {
                clientPool?.getNegotiatedMtu(targetDeviceAddress) ?: BleConstants.DEFAULT_PAYLOAD_MTU
            } else {
                BleConstants.DEFAULT_PAYLOAD_MTU
            }
            val maxPayload = (mtu - net.hexnet.app.core.protocol.fragmentation.PacketFragment.HEADER_SIZE).coerceAtLeast(20)
            val fragments = net.hexnet.app.core.protocol.fragmentation.PacketFragmenter.fragment(
                rawPacket,
                packet.packetId,
                maxPayload
            )

            serviceScope.launch {
                for (fragment in fragments) {
                    if (targetDeviceAddress != null) {
                        // Unicast: try client pool first, then GATT server central
                        val sentViaClient = clientPool?.writeFragment(targetDeviceAddress, fragment) ?: false
                        if (!sentViaClient) {
                            gattServer?.sendNotification(targetDeviceAddress, fragment)
                        }
                    } else {
                        // Broadcast: notify all connected centrals and write to connected peripherals
                        gattServer?.broadcastNotification(fragment)
                        clientPool?.broadcastFragment(fragment)
                    }
                }
            }
        }

        // Wire engine delivery state changes back to Room MessageDao and UI
        engine.onDeliveryStateChanged = { packetId, stateStr ->
            val msgId = "msg_$packetId"
            val deliveryState = when (stateStr) {
                "SENDING" -> net.hexnet.app.core.model.MessageDeliveryState.SENDING
                "RELAYED" -> net.hexnet.app.core.model.MessageDeliveryState.SENT
                "DELIVERED" -> net.hexnet.app.core.model.MessageDeliveryState.DELIVERED
                "FAILED", "FAILED_PERSISTENCE" -> net.hexnet.app.core.model.MessageDeliveryState.FAILED
                else -> net.hexnet.app.core.model.MessageDeliveryState.SENDING
            }
            hexApp?.messagingRepository?.updateMessageState(msgId, deliveryState)
        }

        // Wire outbound message pipeline from UI / MessagingRepository into MeshRoutingEngine
        hexApp?.messagingRepository?.outboundDispatcher = { conversationId, targetHex, text, messageId ->
            if (isBluetoothPaused || !isRunning) {
                false
            } else {
                val packetId = messageId.removePrefix("msg_").toLongOrNull()
                    ?: kotlin.math.abs(messageId.hashCode().toLong())
                try {
                    engine.sendDirectMessage(
                        destinationHex = targetHex,
                        plaintext = text.toByteArray(Charsets.UTF_8),
                        packetId = packetId
                    )
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to dispatch message $messageId via mesh engine", e)
                    false
                }
            }
        }

        // Shared handler for incoming fragment bytes
        val onIncomingFragment: (String, ByteArray) -> Unit = { fromAddress, data ->
            val completePacketBytes = reassembler?.processFragmentBytes(data)
            if (completePacketBytes != null) {
                val decision = engine.processIncomingPacket(fromAddress, completePacketBytes)
                Log.d(TAG, "Ingested mesh packet from $fromAddress: decision=${decision.javaClass.simpleName}")
                if (decision is net.hexnet.app.core.routing.RoutingDecision.AcceptLocally) {
                    handleAcceptedIncomingPacket(decision, fromAddress, localHexLong, hexApp)
                }
            }
        }

        // Recover durable queues and flush offline pending messages from Room
        serviceScope.launch {
            engine.recoverQueues()
            flushPendingQueuedMessages(engine, hexApp)
        }

        // 2. Initialize Peripheral GATT Server & Advertising
        gattServer = HexnetGattServer(
            context = this,
            bluetoothManager = bluetoothManager,
            onFragmentReceived = onIncomingFragment,
            onClientConnected = { centralAddress ->
                Log.d(TAG, "Central connected: $centralAddress")
                engine.handlePeerConnected(centralAddress, 0L)
                updateMeshNotificationStatus()
            },
            onClientDisconnected = { centralAddress ->
                Log.d(TAG, "Central disconnected: $centralAddress")
                engine.handlePeerDisconnected(centralAddress)
                updateMeshNotificationStatus()
            }
        )
        gattServer?.start(localHex)

        // 3. Initialize Central Client Connection Pool
        clientPool = HexnetGattClientPool(
            context = this,
            coroutineScope = serviceScope,
            onFragmentReceived = onIncomingFragment,
            onPeerStateChanged = { peerAddress, state ->
                Log.d(TAG, "GATT Client peer $peerAddress state changed: $state")
                if (state == PeerConnectionState.CONNECTED_READY) {
                    engine.handlePeerConnected(peerAddress, 0L)
                    updateMeshNotificationStatus()
                } else if (state == PeerConnectionState.DISCONNECTED) {
                    engine.handlePeerDisconnected(peerAddress)
                    updateMeshNotificationStatus()
                }
            }
        )

        // 4. Initialize BLE Scanner
        scanner = HexnetBleScanner(
            bluetoothAdapter = bluetoothManager.adapter,
            onPeerDiscovered = { peer ->
                Log.d(TAG, "Discovered Hexnet peer: ${peer.device.address} (${peer.rssi} dBm)")
                val peerId = "peer_${peer.device.address.replace(":", "").lowercase()}"
                val hexStr = "0x" + peer.device.address.replace(":", "").take(16).lowercase()
                hexApp?.contactsRepository?.upsertPeer(
                    net.hexnet.app.core.model.Peer(
                        peerId = peerId,
                        hexAddress = hexStr,
                        username = "node_" + hexStr.takeLast(6),
                        displayName = peer.device.name ?: ("Node " + hexStr.take(8)),
                        rssiDbm = peer.rssi,
                        hopDistance = 1,
                        isDirectNeighbor = true,
                        lastSeenEpochMs = System.currentTimeMillis(),
                        status = net.hexnet.app.core.model.PeerStatus.ACTIVE_DIRECT,
                        isContact = false,
                        isIdentityVerified = false,
                        isBlocked = false
                    )
                )
                hexApp?.meshNetworkRepository?.recordEvent(
                    type = net.hexnet.app.core.model.NetworkEventType.PEER_DISCOVERED,
                    summary = "Direct BLE Peer Discovered",
                    details = "Discovered peer ${peer.device.address} at ${peer.rssi} dBm RSSI",
                    hex = hexStr
                )
                clientPool?.connectToPeer(peer.device)
            }
        )

        if (isScreenOff) {
            scanner?.startDutyCycleScan(serviceScope)
        } else {
            scanner?.startScan(lowLatency = false)
        }

        isRunning = true
        isBluetoothPaused = false
        Log.i(TAG, "HexnetMeshForegroundService started for node: $localHex ($localHexLong)")
        updateMeshNotificationStatus()
    }

    private fun handleBluetoothDisabled() {
        isBluetoothPaused = true
        scanner?.stopScan()
        clientPool?.closeAll()
        gattServer?.stop()
        updateNotification("Hexnet Mesh Paused · Bluetooth is disabled")
    }

    private fun handleBluetoothEnabled() {
        if (!isRunning) return
        isBluetoothPaused = false
        val localHex = currentLocalHex ?: "0xHEXNET_NODE"

        if (BlePermissionManager.hasAllPermissions(this)) {
            gattServer?.restart(localHex)
            if (isScreenOff) {
                scanner?.startDutyCycleScan(serviceScope)
            } else {
                scanner?.startScan(lowLatency = false)
            }
            updateMeshNotificationStatus()
            val hexApp = application as? net.hexnet.app.HexnetApplication
            val engine = routingEngine
            if (engine != null && hexApp != null) {
                serviceScope.launch {
                    flushPendingQueuedMessages(engine, hexApp)
                }
            }
        } else {
            updateNotification("Hexnet Mesh Paused · Missing Bluetooth permissions")
        }
    }

    private fun handleScreenOff() {
        isScreenOff = true
        if (isRunning && !isBluetoothPaused) {
            scanner?.stopScan()
            scanner?.startDutyCycleScan(serviceScope)
            updateMeshNotificationStatus()
        }
    }

    private fun handleScreenOn() {
        isScreenOff = false
        if (isRunning && !isBluetoothPaused) {
            scanner?.stopDutyCycle()
            scanner?.startScan(lowLatency = false)
            updateMeshNotificationStatus()
        }
    }

    private fun updateMeshNotificationStatus() {
        if (!isRunning) return
        if (isBluetoothPaused) {
            updateNotification("Hexnet Mesh Paused · Bluetooth is disabled")
            return
        }

        val clients = clientPool?.getActiveConnectionCount() ?: 0
        val centrals = gattServer?.getConnectedCentralCount() ?: 0
        val totalConnections = clients + centrals

        val text = when {
            totalConnections > 0 -> "Hexnet Mesh Active · $totalConnections connected peer${if (totalConnections > 1) "s" else ""}"
            isScreenOff -> "Hexnet Mesh Active · Duty-cycle scanning (screen locked)"
            else -> "Hexnet Mesh Active · Searching for mesh peers"
        }
        updateNotification(text)
    }

    private fun handleAcceptedIncomingPacket(
        decision: net.hexnet.app.core.routing.RoutingDecision.AcceptLocally,
        fromAddress: String,
        localHexLong: Long,
        hexApp: net.hexnet.app.HexnetApplication?
    ) {
        val packet = decision.packet
        // 1. Destination verification:
        // For direct messages, destination must be local node (or broadcast)
        if (packet.type == net.hexnet.app.core.protocol.PacketType.DIRECT_MESSAGE) {
            if (packet.destinationHex != localHexLong && packet.destinationHex != net.hexnet.app.core.protocol.BinaryPacket.BROADCAST_DESTINATION) {
                Log.w(TAG, "Rejected packet with mismatched destination: ${packet.destinationHex}")
                return
            }

            // 2. Cryptographic validation & decryption check
            val isEncrypted = net.hexnet.app.core.protocol.PacketFlags.hasFlag(packet.flags, net.hexnet.app.core.protocol.PacketFlags.ENCRYPTED)
            val payloadBytes = if (isEncrypted) {
                // If packet was flagged encrypted, decision.decryptedPayload must be non-null and valid
                if (decision.decryptedPayload == null) {
                    Log.e(TAG, "Security rejection: Failed to decrypt/authenticate encrypted packet ${packet.packetId} from ${packet.sourceHex}. Discarding.")
                    return
                }
                decision.decryptedPayload
            } else {
                // Enforce encryption policy: If peer has an established Noise session, reject unencrypted direct messages
                val senderHexStr = "0x" + java.lang.Long.toHexString(packet.sourceHex).padStart(16, '0')
                if (hexApp?.noiseSessionManager?.hasActiveSession(senderHexStr) == true) {
                    Log.w(TAG, "Security rejection: Received unencrypted packet from peer $senderHexStr with active Noise session.")
                    return
                }
                packet.payload
            }

            // Validate UTF-8 content
            val content = try {
                String(payloadBytes, Charsets.UTF_8).trim()
            } catch (e: Exception) {
                Log.w(TAG, "Malformed UTF-8 payload in packet ${packet.packetId}")
                return
            }

            if (content.isEmpty()) {
                Log.w(TAG, "Discarded empty payload packet ${packet.packetId}")
                return
            }

            val senderHexStr = "0x" + java.lang.Long.toHexString(packet.sourceHex).padStart(16, '0')
            val msgId = "msg_${packet.packetId}"
            val convId = "conv_${java.lang.Long.toHexString(packet.sourceHex)}"

            // 3. Authenticate, verify destination, persist in Room, and ONLY THEN acknowledge E2E delivery
            serviceScope.launch {
                val messageDao = hexApp?.database?.messageDao()
                val alreadyExists = messageDao?.exists(msgId) ?: false

                if (alreadyExists) {
                    // Previously durably stored duplicate message: acknowledge idempotently!
                    Log.d(TAG, "Duplicate message $msgId already durably stored. Acknowledging delivery idempotently.")
                    routingEngine?.sendEndToEndAck(packet.sourceHex, packet.packetId)
                    return@launch
                }

                val inserted = hexApp?.messagingRepository?.receiveIncomingMessage(
                    messageId = msgId,
                    conversationId = convId,
                    senderHex = senderHexStr,
                    senderUsername = senderHexStr.take(10),
                    content = content,
                    hopCount = packet.hopCount.toInt(),
                    maxHops = (packet.ttl + packet.hopCount).toInt()
                ) ?: false

                if (inserted) {
                    Log.i(TAG, "Successfully persisted verified incoming message $msgId from $senderHexStr into Room")
                    // SEND END-TO-END DELIVERY ACK ONLY AFTER DURABLE ROOM PERSISTENCE SUCCEEDS!
                    routingEngine?.sendEndToEndAck(packet.sourceHex, packet.packetId)

                    hexApp?.meshNetworkRepository?.recordEvent(
                        type = net.hexnet.app.core.model.NetworkEventType.PACKET_RELAYED,
                        summary = "Direct Message Delivered",
                        details = "Verified & decrypted packet $msgId from $senderHexStr",
                        hex = senderHexStr
                    )
                } else {
                    Log.w(TAG, "Failed to persist incoming message $msgId. Delivery ACK suppressed.")
                }
            }
        }
    }

    private suspend fun flushPendingQueuedMessages(
        engine: net.hexnet.app.core.routing.MeshRoutingEngine,
        hexApp: net.hexnet.app.HexnetApplication?
    ) {
        if (hexApp == null) return
        try {
            val queued = hexApp.database.messageDao().getPendingQueuedMessages()
            if (queued.isEmpty()) return
            Log.i(TAG, "Flushing ${queued.size} pending queued messages from Room to mesh engine")
            for (msg in queued) {
                val targetHex = hexApp.messagingRepository.resolveTargetHexForConversation(msg.conversationId)
                if (targetHex == null) {
                    Log.w(TAG, "Cannot resolve recipient for queued message ${msg.id}. Kept as QUEUED.")
                    hexApp.database.messageDao().updateDeliveryState(msg.id, net.hexnet.app.core.model.MessageDeliveryState.QUEUED)
                    continue
                }
                // Prevent duplicate transmission: mark SENDING
                hexApp.database.messageDao().updateDeliveryState(msg.id, net.hexnet.app.core.model.MessageDeliveryState.SENDING)
                val packetId = msg.id.removePrefix("msg_").toLongOrNull()
                    ?: kotlin.math.abs(msg.id.hashCode().toLong())
                try {
                    engine.sendDirectMessage(
                        destinationHex = targetHex,
                        plaintext = msg.content.toByteArray(Charsets.UTF_8),
                        packetId = packetId
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to flush queued message ${msg.id}", e)
                    hexApp.database.messageDao().updateDeliveryState(msg.id, net.hexnet.app.core.model.MessageDeliveryState.QUEUED)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing pending queued messages", e)
        }
    }

    private fun stopMeshSubsystems() {
        val hexApp = application as? net.hexnet.app.HexnetApplication
        hexApp?.messagingRepository?.outboundDispatcher = null

        unregisterSystemReceivers()

        scanner?.stopScan()
        scanner = null

        clientPool?.closeAll()
        clientPool = null

        gattServer?.stop()
        gattServer = null

        reassembler?.clear()
        reassembler = null

        routingEngine?.stopQueueWorker()
        routingEngine = null
        isRunning = false
        Log.i(TAG, "HexnetMeshForegroundService stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Hexnet Decentralized Mesh Network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth LE mesh connectivity and packet relay active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        // Content Intent: Clicking notification opens Hexnet MainActivity
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, net.hexnet.app.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action Intent: "Stop Mesh" directly stops the foreground service
        val stopIntent = Intent(this, HexnetMeshForegroundService::class.java).apply {
            action = ACTION_STOP_MESH
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Hexnet Offline Mesh")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Mesh", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = buildForegroundNotification(contentText)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMeshSubsystems()
        serviceScope.cancel()
    }
}
