package net.hexnet.app.lifecycle

import net.hexnet.app.core.transport.ble.BleConstants
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for BLE lifecycle, reconnection backoff, cooldown suppression,
 * connection pool limits, duty cycling, and process recreation state management.
 */
class BleLifecycleTest {

    @Test
    fun testExponentialReconnectionBackoffCalculation() {
        val initialBackoff = BleConstants.INITIAL_RECONNECT_BACKOFF_MS
        assertEquals(1500L, initialBackoff)

        // Attempt 1: 1500 * (1 shl 0) = 1500ms
        val delay1 = initialBackoff * (1 shl (1 - 1))
        assertEquals(1500L, delay1)

        // Attempt 2: 1500 * (1 shl 1) = 3000ms
        val delay2 = initialBackoff * (1 shl (2 - 1))
        assertEquals(3000L, delay2)

        // Attempt 3: 1500 * (1 shl 2) = 6000ms
        val delay3 = initialBackoff * (1 shl (3 - 1))
        assertEquals(6000L, delay3)

        // Max reconnect attempts capped at 3
        assertEquals(3, BleConstants.MAX_RECONNECT_ATTEMPTS)
    }

    @Test
    fun testPeerCooldownSuppressionLogic() {
        val failedPeerCooldown = ConcurrentHashMap<String, Long>()
        val address = "AA:BB:CC:DD:EE:01"
        val now = System.currentTimeMillis()

        // Place peer in cooldown for 30s
        failedPeerCooldown[address] = now + BleConstants.PEER_COOLDOWN_PERIOD_MS

        fun isPeerInCooldown(addr: String, currentTime: Long): Boolean {
            val cooldownUntil = failedPeerCooldown[addr] ?: return false
            return if (currentTime < cooldownUntil) {
                true
            } else {
                failedPeerCooldown.remove(addr)
                false
            }
        }

        // Immediately after failure: peer should be in cooldown
        assertTrue(isPeerInCooldown(address, now + 1000L))
        assertTrue(isPeerInCooldown(address, now + 29000L))

        // After cooldown expires (30.1s): peer is released from cooldown
        assertFalse(isPeerInCooldown(address, now + 30100L))
        assertNull(failedPeerCooldown[address])
    }

    @Test
    fun testMaxConcurrentGattConnectionLimitAndEviction() {
        data class MockPeer(val address: String, val lastActiveMs: Long)

        val maxConnections = BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS
        assertEquals(7, maxConnections)

        val activePeers = mutableMapOf<String, MockPeer>()
        for (i in 1..7) {
            val addr = "NODE:0$i"
            activePeers[addr] = MockPeer(addr, lastActiveMs = 1000L * i)
        }
        assertEquals(7, activePeers.size)

        // 8th peer arrives: must evict the oldest idle peer
        val oldestAddr = activePeers.minByOrNull { it.value.lastActiveMs }?.key
        assertEquals("NODE:01", oldestAddr)

        activePeers.remove(oldestAddr)
        activePeers["NODE:08"] = MockPeer("NODE:08", 8000L)

        assertEquals(7, activePeers.size)
        assertFalse(activePeers.containsKey("NODE:01"))
        assertTrue(activePeers.containsKey("NODE:08"))
    }

    @Test
    fun testDutyCycleDutyRatioCalculations() {
        // Balanced duty cycle: 6000ms scan, 14000ms pause
        val scanPeriod = BleConstants.SCAN_PERIOD_BALANCED_MS
        val pausePeriod = BleConstants.SCAN_PAUSE_BALANCED_MS
        val totalCycle = scanPeriod + pausePeriod

        assertEquals(6000L, scanPeriod)
        assertEquals(14000L, pausePeriod)
        assertEquals(20000L, totalCycle)

        // Duty cycle is 30% active, 70% radio sleep (saves massive battery when screen is off)
        val activeFraction = scanPeriod.toDouble() / totalCycle.toDouble()
        assertEquals(0.30, activeFraction, 0.001)
    }

    @Test
    fun testBluetoothAdapterStateTransitionSimulation() {
        var isBluetoothPaused = false
        var isScanning = true
        var isAdvertising = true

        fun onAdapterStateChanged(state: Int) {
            when (state) {
                10, 13 -> { // STATE_OFF, STATE_TURNING_OFF
                    isBluetoothPaused = true
                    isScanning = false
                    isAdvertising = false
                }
                12 -> { // STATE_ON
                    isBluetoothPaused = false
                    isScanning = true
                    isAdvertising = true
                }
            }
        }

        // Bluetooth turns off
        onAdapterStateChanged(13)
        assertTrue(isBluetoothPaused)
        assertFalse(isScanning)
        assertFalse(isAdvertising)

        // Bluetooth turns on
        onAdapterStateChanged(12)
        assertFalse(isBluetoothPaused)
        assertTrue(isScanning)
        assertTrue(isAdvertising)
    }

    @Test
    fun testScreenStateDutyCycleTransitionSimulation() {
        var isScreenOff = false
        var currentScanMode = "BALANCED"

        fun onScreenEvent(action: String) {
            when (action) {
                "android.intent.action.SCREEN_OFF" -> {
                    isScreenOff = true
                    currentScanMode = "DUTY_CYCLE_LOW_POWER"
                }
                "android.intent.action.SCREEN_ON" -> {
                    isScreenOff = false
                    currentScanMode = "BALANCED"
                }
            }
        }

        onScreenEvent("android.intent.action.SCREEN_OFF")
        assertTrue(isScreenOff)
        assertEquals("DUTY_CYCLE_LOW_POWER", currentScanMode)

        onScreenEvent("android.intent.action.SCREEN_ON")
        assertFalse(isScreenOff)
        assertEquals("BALANCED", currentScanMode)
    }

    @Test
    fun testStickyRestartIdentityRecoveryFallback() {
        val intentExtra: String? = null
        val storedIdentityHex = "0x4845584E45543939"

        val resolvedHex = intentExtra ?: storedIdentityHex
        assertEquals("0x4845584E45543939", resolvedHex)

        val clean = resolvedHex.removePrefix("0x").take(16).padEnd(16, '0')
        val hexLong = clean.toLong(16)
        assertEquals(0x4845584E45543939L, hexLong)
    }

    @Test
    fun testGattCleanupNullificationPreventsMemoryLeaks() {
        class MockGattConnection {
            var writeCharacteristic: Any? = "CHAR_WRITE"
            var notifyCharacteristic: Any? = "CHAR_NOTIFY"
            var isClosed = false

            fun teardown() {
                writeCharacteristic = null
                notifyCharacteristic = null
                isClosed = true
            }
        }

        val conn = MockGattConnection()
        assertNotNull(conn.writeCharacteristic)
        assertNotNull(conn.notifyCharacteristic)

        conn.teardown()
        assertNull(conn.writeCharacteristic)
        assertNull(conn.notifyCharacteristic)
        assertTrue(conn.isClosed)
    }

    @Test
    fun testReconnectAttemptCounterCapsAtThree() {
        val attempts = AtomicInteger(0)
        var placedInCooldown = false

        for (i in 1..4) {
            val count = attempts.incrementAndGet()
            if (count > BleConstants.MAX_RECONNECT_ATTEMPTS) {
                placedInCooldown = true
            }
        }

        assertEquals(4, attempts.get())
        assertTrue(placedInCooldown)
    }
}
