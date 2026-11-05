package net.hexnet.app.core.transport.ble

import java.util.UUID

/**
 * Protocol constants, UUIDs, and hardware limits for Hexnet BLE mesh networking.
 */
object BleConstants {
    /**
     * Primary Hexnet Mesh GATT Service UUID.
     * 128-bit UUID reserved for Hexnet mesh peer discovery and data exchange.
     */
    val HEXNET_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /**
     * GATT Characteristic for incoming data packets (Write with or without response).
     * Remote Central writes binary fragments to this characteristic on the Peripheral.
     */
    val HEXNET_WRITE_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /**
     * GATT Characteristic for outgoing notifications / indications.
     * Peripheral notifies connected Centrals with data updates or ACKs.
     */
    val HEXNET_NOTIFY_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /**
     * Standard Client Characteristic Configuration Descriptor (CCCD).
     */
    val CCCD_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Hexnet Manufacturer Identifier for BLE Advertising Service Data.
     * 0x4858 = ASCII 'H', 'X' (Hexnet).
     */
    const val HEXNET_MANUFACTURER_ID: Int = 0x4858

    /**
     * Default ATT MTU size on Bluetooth LE (23 bytes total - 3 bytes ATT header = 20 bytes payload).
     */
    const val DEFAULT_ATT_MTU: Int = 23
    const val DEFAULT_PAYLOAD_MTU: Int = DEFAULT_ATT_MTU - 3 // 20 bytes

    /**
     * Target negotiated ATT MTU on modern Android hardware (517 bytes - 3 bytes header = 514 bytes payload).
     */
    const val TARGET_NEGOTIATED_MTU: Int = 517
    const val TARGET_PAYLOAD_MTU: Int = TARGET_NEGOTIATED_MTU - 3 // 514 bytes

    /**
     * Safety limit for simultaneous active GATT connections on Android.
     * Android chipset hardware limits range from 7 to 15 concurrent connections.
     * We clamp active pool connections to 7 to prevent radio stack freezes.
     */
    const val MAX_CONCURRENT_GATT_CONNECTIONS: Int = 7

    /**
     * Timeout for a single serialized GATT operation (connect, service discovery, write, MTU request) in ms.
     */
    const val GATT_OPERATION_TIMEOUT_MS: Long = 4000L

    /**
     * Timeout for in-flight packet fragment reassembly in milliseconds.
     */
    const val FRAGMENT_REASSEMBLY_TIMEOUT_MS: Long = 10000L

    /**
     * Duty cycling intervals for BLE scanning.
     */
    const val SCAN_PERIOD_ACTIVE_MS: Long = 10000L
    const val SCAN_PAUSE_ACTIVE_MS: Long = 2000L
    const val SCAN_PERIOD_BALANCED_MS: Long = 6000L
    const val SCAN_PAUSE_BALANCED_MS: Long = 14000L

    /**
     * Reconnection and error backoff constants.
     */
    const val MAX_RECONNECT_ATTEMPTS: Int = 3
    const val INITIAL_RECONNECT_BACKOFF_MS: Long = 1500L
    const val PEER_COOLDOWN_PERIOD_MS: Long = 30000L
}
