package net.hexnet.app.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredBlePeer(
    val device: BluetoothDevice,
    val hexAddress: String?,
    val rssi: Int,
    val lastSeenEpochMs: Long = System.currentTimeMillis()
)

/**
 * Manages continuous, low-latency, and battery-efficient duty-cycled BLE scanning
 * for nearby Hexnet mesh nodes. Implements screen-off duty cycling to prevent
 * excessive battery consumption and Bluedroid radio throttling.
 */
@SuppressLint("MissingPermission")
class HexnetBleScanner(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val onPeerDiscovered: (DiscoveredBlePeer) -> Unit
) {
    companion object {
        private const val TAG = "HexnetBleScanner"
        private const val DEBOUNCE_INTERVAL_MS = 2500L
    }

    private var scanner: BluetoothLeScanner? = null
    @Volatile private var isScanning = false
    @Volatile private var isDutyCycling = false
    private var dutyCycleJob: Job? = null
    private val seenPeers = ConcurrentHashMap<String, Long>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device ?: return
            val address = device.address

            // Debounce rapid duplicate advertisement bursts
            val now = System.currentTimeMillis()
            val lastSeen = seenPeers[address] ?: 0L
            if (now - lastSeen < DEBOUNCE_INTERVAL_MS) {
                return
            }
            seenPeers[address] = now

            // Extract Hex Address from manufacturer data if present
            var hexAddress: String? = null
            val scanRecord = result.scanRecord
            if (scanRecord != null) {
                val mfgData = scanRecord.getManufacturerSpecificData(BleConstants.HEXNET_MANUFACTURER_ID)
                if (mfgData != null && mfgData.isNotEmpty()) {
                    hexAddress = "0x" + mfgData.joinToString("") { "%02x".format(it) }
                }
            }

            val peer = DiscoveredBlePeer(
                device = device,
                hexAddress = hexAddress,
                rssi = result.rssi,
                lastSeenEpochMs = now
            )
            onPeerDiscovered(peer)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed with errorCode: $errorCode")
            isScanning = false
        }
    }

    /**
     * Starts continuous BLE scanning with specified latency mode.
     */
    fun startScan(lowLatency: Boolean = true): Boolean {
        stopDutyCycle()
        return startHardwareScan(lowLatency = lowLatency)
    }

    /**
     * Starts adaptive battery-saving duty-cycled scanning for background / screen-off operation.
     * Alternates between active scanning (e.g. 6s) and idle quiet periods (e.g. 14s).
     */
    fun startDutyCycleScan(
        scope: CoroutineScope,
        scanDurationMs: Long = BleConstants.SCAN_PERIOD_BALANCED_MS,
        pauseDurationMs: Long = BleConstants.SCAN_PAUSE_BALANCED_MS
    ) {
        if (isDutyCycling) return
        isDutyCycling = true

        dutyCycleJob = scope.launch {
            Log.d(TAG, "Starting duty-cycle BLE scan: active=${scanDurationMs}ms, pause=${pauseDurationMs}ms")
            while (isActive && isDutyCycling) {
                startHardwareScan(lowLatency = false, lowPower = true)
                delay(scanDurationMs)
                stopHardwareScan()
                delay(pauseDurationMs)
            }
        }
    }

    /**
     * Cancels duty cycling loop.
     */
    fun stopDutyCycle() {
        isDutyCycling = false
        dutyCycleJob?.cancel()
        dutyCycleJob = null
    }

    private fun startHardwareScan(lowLatency: Boolean = false, lowPower: Boolean = false): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled or unavailable")
            return false
        }
        if (isScanning) return true

        val leScanner = try {
            bluetoothAdapter.bluetoothLeScanner
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission", e)
            return false
        }

        if (leScanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null")
            return false
        }
        scanner = leScanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.HEXNET_SERVICE_UUID))
            .build()

        val mode = when {
            lowPower -> ScanSettings.SCAN_MODE_LOW_POWER
            lowLatency -> ScanSettings.SCAN_MODE_LOW_LATENCY
            else -> ScanSettings.SCAN_MODE_BALANCED
        }

        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        return try {
            leScanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            Log.d(TAG, "BLE Scan started (mode=$mode)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting BLE scan", e)
            isScanning = false
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE scan", e)
            isScanning = false
            false
        }
    }

    private fun stopHardwareScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "Hardware BLE scan paused")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException stopping BLE scan", e)
            isScanning = false
        } catch (e: Exception) {
            Log.w(TAG, "Exception stopping BLE scan", e)
            isScanning = false
        }
    }

    /**
     * Stops all BLE scanning and duty cycling.
     */
    fun stopScan() {
        stopDutyCycle()
        stopHardwareScan()
    }

    fun isScanning(): Boolean = isScanning || isDutyCycling
    fun isDutyCycling(): Boolean = isDutyCycling

    /**
     * Clear peer debounce cache.
     */
    fun clearSeenPeers() {
        seenPeers.clear()
    }
}
