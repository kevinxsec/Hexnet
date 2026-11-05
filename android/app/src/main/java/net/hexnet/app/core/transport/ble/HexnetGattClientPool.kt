package net.hexnet.app.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class PeerConnectionState {
    CONNECTING,
    NEGOTIATING_MTU,
    DISCOVERING_SERVICES,
    ENABLING_NOTIFICATIONS,
    CONNECTED_READY,
    DISCONNECTING,
    DISCONNECTED
}

data class BlePeerConnection(
    val device: BluetoothDevice,
    val gatt: BluetoothGatt,
    val queue: GattOperationQueue,
    var state: PeerConnectionState = PeerConnectionState.CONNECTING,
    var negotiatedMtu: Int = BleConstants.DEFAULT_PAYLOAD_MTU,
    var lastActiveEpochMs: Long = System.currentTimeMillis(),
    var writeCharacteristic: BluetoothGattCharacteristic? = null,
    var notifyCharacteristic: BluetoothGattCharacteristic? = null
)

/**
 * Manages active GATT client connections to remote Hexnet mesh peers.
 * Enforces hardware connection limits and serializes all GATT operations.
 */
@SuppressLint("MissingPermission")
class HexnetGattClientPool(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onFragmentReceived: (deviceAddress: String, data: ByteArray) -> Unit,
    private val onPeerStateChanged: (deviceAddress: String, state: PeerConnectionState) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "HexnetGattClientPool"
    }

    private val connections = ConcurrentHashMap<String, BlePeerConnection>()
    private val reconnectAttempts = ConcurrentHashMap<String, AtomicInteger>()
    private val failedPeerCooldown = ConcurrentHashMap<String, Long>()

    /**
     * Returns true if peer is in cooldown.
     */
    fun isPeerInCooldown(address: String): Boolean {
        val cooldownUntil = failedPeerCooldown[address] ?: return false
        return if (System.currentTimeMillis() < cooldownUntil) {
            true
        } else {
            failedPeerCooldown.remove(address)
            false
        }
    }

    /**
     * Initiates connection to a discovered remote BLE peer device.
     */
    fun connectToPeer(device: BluetoothDevice): Boolean {
        val address = device.address
        val existing = connections[address]
        if (existing != null && existing.state == PeerConnectionState.CONNECTED_READY) {
            Log.d(TAG, "Already connected to peer: $address")
            return true
        }

        // Check if peer is in exponential backoff cooldown
        if (isPeerInCooldown(address)) {
            Log.d(TAG, "Peer $address is in reconnect cooldown; skipping connection attempt")
            return false
        }

        // Enforce max concurrent connection limit by evicting oldest idle connection if necessary
        if (connections.size >= BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS) {
            evictOldestIdleConnection()
        }

        Log.d(TAG, "Initiating GATT connection to peer: $address")
        onPeerStateChanged(address, PeerConnectionState.CONNECTING)

        var connection: BlePeerConnection? = null

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val conn = connections[address] ?: connection
                Log.d(TAG, "GATT state change for $address: status=$status, newState=$newState")

                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    conn?.state = PeerConnectionState.NEGOTIATING_MTU
                    onPeerStateChanged(address, PeerConnectionState.NEGOTIATING_MTU)
                    reconnectAttempts.remove(address)
                    failedPeerCooldown.remove(address)

                    // Serialized: Request high connection priority and MTU negotiation
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Missing BLUETOOTH_CONNECT for requestConnectionPriority", e)
                    }
                    coroutineScope.launch {
                        conn?.queue?.enqueue(GattOperation.RequestMtu(BleConstants.TARGET_NEGOTIATED_MTU))
                    }
                } else {
                    // Disconnected or connection error
                    handleDisconnect(address, gatt, status, conn?.device ?: device)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val conn = connections[address]
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val usablePayload = mtu - 3
                    conn?.negotiatedMtu = usablePayload
                    Log.d(TAG, "Negotiated MTU with $address: $mtu bytes ($usablePayload byte payload)")
                }
                conn?.queue?.notifyCompleted(status == BluetoothGatt.GATT_SUCCESS)

                // Next: Discover Hexnet services
                conn?.state = PeerConnectionState.DISCOVERING_SERVICES
                onPeerStateChanged(address, PeerConnectionState.DISCOVERING_SERVICES)
                coroutineScope.launch {
                    conn?.queue?.enqueue(GattOperation.DiscoverServices())
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val conn = connections[address]
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Service discovery failed with status: $status")
                    conn?.queue?.notifyCompleted(false)
                    disconnectPeer(address)
                    return
                }

                val service = gatt.getService(BleConstants.HEXNET_SERVICE_UUID)
                if (service == null) {
                    Log.w(TAG, "Peer $address does not host Hexnet Service UUID")
                    conn?.queue?.notifyCompleted(false)
                    disconnectPeer(address)
                    return
                }

                val writeChar = service.getCharacteristic(BleConstants.HEXNET_WRITE_CHAR_UUID)
                val notifyChar = service.getCharacteristic(BleConstants.HEXNET_NOTIFY_CHAR_UUID)

                conn?.writeCharacteristic = writeChar
                conn?.notifyCharacteristic = notifyChar
                conn?.queue?.notifyCompleted(true)

                // Enable notifications on remote notify characteristic
                if (notifyChar != null) {
                    conn?.state = PeerConnectionState.ENABLING_NOTIFICATIONS
                    onPeerStateChanged(address, PeerConnectionState.ENABLING_NOTIFICATIONS)
                    coroutineScope.launch {
                        conn?.queue?.enqueue(GattOperation.EnableNotification(true))
                    }
                } else {
                    markReady(conn)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val conn = connections[address]
                conn?.lastActiveEpochMs = System.currentTimeMillis()
                conn?.queue?.notifyCompleted(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                val conn = connections[address]
                conn?.queue?.notifyCompleted(status == BluetoothGatt.GATT_SUCCESS)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    markReady(conn)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == BleConstants.HEXNET_NOTIFY_CHAR_UUID) {
                    val data = characteristic.value ?: return
                    onFragmentReceived(address, data)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == BleConstants.HEXNET_NOTIFY_CHAR_UUID) {
                    onFragmentReceived(address, value)
                }
            }
        }

        val queue = GattOperationQueue(
            scope = coroutineScope,
            onExecute = { op ->
                val gatt = connections[address]?.gatt
                when (op) {
                    is GattOperation.RequestMtu -> gatt?.requestMtu(op.mtu)
                    is GattOperation.DiscoverServices -> gatt?.discoverServices()
                    is GattOperation.WriteCharacteristic -> {
                        val char = connections[address]?.writeCharacteristic
                        if (char != null && gatt != null) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeCharacteristic(char, op.data, op.writeType)
                            } else {
                                char.value = op.data
                                char.writeType = op.writeType
                                gatt.writeCharacteristic(char)
                            }
                        } else {
                            connections[address]?.queue?.notifyCompleted(false)
                        }
                    }
                    is GattOperation.EnableNotification -> {
                        val notifyChar = connections[address]?.notifyCharacteristic
                        if (notifyChar != null && gatt != null) {
                            gatt.setCharacteristicNotification(notifyChar, op.enable)
                            val desc = notifyChar.getDescriptor(BleConstants.CCCD_DESCRIPTOR_UUID)
                            if (desc != null) {
                                desc.value = if (op.enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                             else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(desc)
                            } else {
                                connections[address]?.queue?.notifyCompleted(false)
                            }
                        } else {
                            connections[address]?.queue?.notifyCompleted(false)
                        }
                    }
                    is GattOperation.Custom -> op.action()
                }
            },
            onTimeout = { op ->
                Log.w(TAG, "GATT Operation timed out for $address: ${op.description}")
            }
        )

        // Connect GATT with TRANSPORT_LE
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            Log.e(TAG, "device.connectGatt returned null")
            return false
        }

        connection = BlePeerConnection(
            device = device,
            gatt = gatt,
            queue = queue,
            state = PeerConnectionState.CONNECTING
        )
        connections[address] = connection
        return true
    }

    private fun markReady(conn: BlePeerConnection?) {
        conn?.state = PeerConnectionState.CONNECTED_READY
        conn?.lastActiveEpochMs = System.currentTimeMillis()
        if (conn != null) {
            onPeerStateChanged(conn.device.address, PeerConnectionState.CONNECTED_READY)
            Log.d(TAG, "Peer ${conn.device.address} is now fully CONNECTED and READY (MTU=${conn.negotiatedMtu})")
        }
    }

    private fun handleDisconnect(address: String, gatt: BluetoothGatt, status: Int, device: BluetoothDevice) {
        val conn = connections.remove(address)
        conn?.state = PeerConnectionState.DISCONNECTED
        conn?.writeCharacteristic = null
        conn?.notifyCharacteristic = null
        conn?.queue?.clear()

        try {
            gatt.disconnect()
            gatt.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing gatt for $address", e)
        }
        onPeerStateChanged(address, PeerConnectionState.DISCONNECTED)
        Log.d(TAG, "GATT connection closed for $address (status=$status)")

        // Reconnection policy: if disconnected abnormally (e.g. timeout, GATT 133, connection drop)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            val attempt = reconnectAttempts.computeIfAbsent(address) { AtomicInteger(0) }.incrementAndGet()
            if (attempt <= BleConstants.MAX_RECONNECT_ATTEMPTS) {
                val backoffMs = BleConstants.INITIAL_RECONNECT_BACKOFF_MS * (1 shl (attempt - 1))
                Log.d(TAG, "Scheduling exponential backoff reconnect attempt $attempt/${BleConstants.MAX_RECONNECT_ATTEMPTS} for $address in ${backoffMs}ms")
                coroutineScope.launch {
                    delay(backoffMs)
                    if (!isPeerInCooldown(address) && connections[address] == null) {
                        connectToPeer(device)
                    }
                }
            } else {
                Log.w(TAG, "Peer $address reached max reconnect attempts ($attempt). Placing in cooldown for ${BleConstants.PEER_COOLDOWN_PERIOD_MS}ms")
                failedPeerCooldown[address] = System.currentTimeMillis() + BleConstants.PEER_COOLDOWN_PERIOD_MS
                reconnectAttempts.remove(address)
            }
        } else {
            // Clean normal disconnect
            reconnectAttempts.remove(address)
        }
    }

    /**
     * Writes a binary fragment to a target connected peer using serialized queue.
     */
    suspend fun writeFragment(targetAddress: String, data: ByteArray): Boolean {
        val conn = connections[targetAddress]
        if (conn == null || conn.state != PeerConnectionState.CONNECTED_READY) {
            Log.w(TAG, "Cannot write to $targetAddress: peer not connected")
            return false
        }

        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return conn.queue.enqueue(GattOperation.WriteCharacteristic(data, writeType))
    }

    /**
     * Broadcasts a binary fragment to all currently connected and ready central client peers.
     */
    suspend fun broadcastFragment(data: ByteArray, exceptAddress: String? = null): Int {
        var sentCount = 0
        for ((address, conn) in connections) {
            if (address != exceptAddress && conn.state == PeerConnectionState.CONNECTED_READY) {
                val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val success = conn.queue.enqueue(GattOperation.WriteCharacteristic(data, writeType))
                if (success) {
                    sentCount++
                }
            }
        }
        return sentCount
    }

    fun disconnectPeer(address: String) {
        val conn = connections.remove(address)
        if (conn != null) {
            conn.state = PeerConnectionState.DISCONNECTING
            conn.writeCharacteristic = null
            conn.notifyCharacteristic = null
            conn.queue.clear()
            try {
                conn.gatt.disconnect()
                conn.gatt.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting $address", e)
            }
            onPeerStateChanged(address, PeerConnectionState.DISCONNECTED)
        }
    }

    private fun evictOldestIdleConnection() {
        var oldestAddress: String? = null
        var oldestTime = Long.MAX_VALUE

        for ((addr, conn) in connections) {
            if (conn.lastActiveEpochMs < oldestTime) {
                oldestTime = conn.lastActiveEpochMs
                oldestAddress = addr
            }
        }

        if (oldestAddress != null) {
            Log.i(TAG, "Evicting oldest idle connection to free GATT slot: $oldestAddress")
            disconnectPeer(oldestAddress)
        }
    }

    fun getActiveConnectionCount(): Int = connections.count { it.value.state == PeerConnectionState.CONNECTED_READY }

    fun getNegotiatedMtu(address: String): Int = connections[address]?.negotiatedMtu ?: BleConstants.DEFAULT_PAYLOAD_MTU

    fun closeAll() {
        for (addr in connections.keys()) {
            disconnectPeer(addr)
        }
        connections.clear()
        reconnectAttempts.clear()
        failedPeerCooldown.clear()
    }
}
