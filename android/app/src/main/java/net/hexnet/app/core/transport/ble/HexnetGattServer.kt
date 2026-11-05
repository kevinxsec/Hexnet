package net.hexnet.app.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles the Peripheral role of the Hexnet BLE stack.
 * Runs a local GATT server hosting the Hexnet Mesh Service and advertises node presence.
 */
@SuppressLint("MissingPermission")
class HexnetGattServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val onFragmentReceived: (deviceAddress: String, data: ByteArray) -> Unit,
    private val onClientConnected: (deviceAddress: String) -> Unit = {},
    private val onClientDisconnected: (deviceAddress: String) -> Unit = {}
) {
    companion object {
        private const val TAG = "HexnetGattServer"
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    // Track active connected centrals: MAC address -> BluetoothDevice
    private val connectedCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private var isAdvertising = false

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (connectedCentrals.size >= BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS && !connectedCentrals.containsKey(address)) {
                    Log.w(TAG, "Rejecting central $address: Max GATT connections (${BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS}) reached")
                    try {
                        gattServer?.cancelConnection(device)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Missing permission to cancel connection", e)
                    }
                    return
                }

                Log.d(TAG, "Central connected to GATT server: $address (status=$status)")
                connectedCentrals[address] = device
                onClientConnected(address)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Central disconnected from GATT server: $address")
                connectedCentrals.remove(address)
                onClientDisconnected(address)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            if (characteristic.uuid == BleConstants.HEXNET_WRITE_CHAR_UUID && value != null) {
                onFragmentReceived(device.address, value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == BleConstants.CCCD_DESCRIPTOR_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                Log.d(TAG, "Central $device configured notification descriptor CCCD")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, "GATT Server MTU negotiated with $device: $mtu bytes")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.d(TAG, "Hexnet BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Hexnet BLE advertising failed with code: $errorCode")
        }
    }

    /**
     * Initializes and starts the GATT server and BLE advertising with local truncated hex identity.
     */
    fun start(localNodeHex: String): Boolean {
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "BluetoothAdapter disabled or unavailable")
            return false
        }

        // 1. Open GATT Server
        val server = bluetoothManager.openGattServer(context, gattServerCallback)
        if (server == null) {
            Log.e(TAG, "Unable to open BluetoothGattServer")
            return false
        }
        gattServer = server

        // 2. Build Hexnet Service & Characteristics
        val service = BluetoothGattService(
            BleConstants.HEXNET_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val writeChar = BluetoothGattCharacteristic(
            BleConstants.HEXNET_WRITE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val notifyChar = BluetoothGattCharacteristic(
            BleConstants.HEXNET_NOTIFY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccd = BluetoothGattDescriptor(
            BleConstants.CCCD_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        )
        notifyChar.addDescriptor(cccd)

        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)

        server.addService(service)
        this.writeCharacteristic = writeChar
        this.notifyCharacteristic = notifyChar

        // 3. Start BLE Advertising
        startAdvertising(adapter, localNodeHex)
        return true
    }

    private fun startAdvertising(adapter: BluetoothAdapter, localNodeHex: String) {
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            Log.e(TAG, "Device does not support BLE Peripheral advertising")
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .build()

        // Truncate local node hex into 8-byte manufacturer payload
        val nodeBytes = try {
            val cleanHex = localNodeHex.removePrefix("0x").take(16).padEnd(16, '0')
            cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            ByteArray(8)
        }

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.HEXNET_SERVICE_UUID))
            .addManufacturerData(BleConstants.HEXNET_MANUFACTURER_ID, nodeBytes)
            .build()

        adv.startAdvertising(settings, data, advertiseCallback)
    }

    /**
     * Sends binary data notification to a specific connected central device.
     */
    fun sendNotification(deviceAddress: String, data: ByteArray): Boolean {
        val device = connectedCentrals[deviceAddress] ?: return false
        val char = notifyCharacteristic ?: return false
        char.value = data
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gattServer?.notifyCharacteristicChanged(device, char, false, data) == BluetoothStatusCodes.SUCCESS
        } else {
            gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
        }
    }

    /**
     * Sends binary data notification to all currently connected centrals.
     */
    fun broadcastNotification(data: ByteArray): Int {
        val char = notifyCharacteristic ?: return 0
        char.value = data
        var sentCount = 0
        for ((_, device) in connectedCentrals) {
            val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(device, char, false, data) == BluetoothStatusCodes.SUCCESS
            } else {
                gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
            }
            if (success) sentCount++
        }
        return sentCount
    }

    /**
     * Restarts the GATT server and BLE advertising after adapter state change or recovery.
     */
    fun restart(localNodeHex: String): Boolean {
        stop()
        return start(localNodeHex)
    }

    /**
     * Stops GATT server, cancels advertising, and disconnects all clients.
     */
    fun stop() {
        try {
            if (isAdvertising && advertiser != null) {
                try {
                    advertiser?.stopAdvertising(advertiseCallback)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Missing BLUETOOTH_ADVERTISE permission to stop advertising", e)
                }
                isAdvertising = false
            }
            advertiser = null

            try {
                gattServer?.clearServices()
                gattServer?.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing BLUETOOTH_CONNECT permission to close GATT server", e)
            }
            gattServer = null
            writeCharacteristic = null
            notifyCharacteristic = null
            connectedCentrals.clear()
            Log.d(TAG, "HexnetGattServer stopped cleanly")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping HexnetGattServer", e)
        }
    }

    fun getConnectedCentralCount(): Int = connectedCentrals.size
}
