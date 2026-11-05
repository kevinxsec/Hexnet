package net.hexnet.app.core.transport.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents a discrete, asynchronous GATT operation on Android.
 */
sealed class GattOperation(val description: String, val timeoutMs: Long = BleConstants.GATT_OPERATION_TIMEOUT_MS) {
    class RequestMtu(val mtu: Int) : GattOperation("Request MTU $mtu")
    class DiscoverServices : GattOperation("Discover Services", 6000L)
    class WriteCharacteristic(val data: ByteArray, val writeType: Int) : GattOperation("Write Characteristic (${data.size} bytes)")
    class EnableNotification(val enable: Boolean) : GattOperation("Enable Notification: $enable")
    class Custom(description: String, val action: () -> Unit) : GattOperation(description)
}

/**
 * Thread-safe, coroutine-backed FIFO queue ensuring sequential execution of Android BluetoothGatt operations.
 * Prevents concurrent GATT invocation collisions (which cause status 133 / internal stack freezes).
 */
class GattOperationQueue(
    private val scope: CoroutineScope,
    private val onExecute: suspend (GattOperation) -> Unit,
    private val onTimeout: (GattOperation) -> Unit = {}
) {
    private data class QueueItem(
        val operation: GattOperation,
        val completion: CompletableDeferred<Boolean>
    )

    private val queue = ConcurrentLinkedQueue<QueueItem>()
    private val isExecuting = AtomicBoolean(false)
    private var activeItem: QueueItem? = null
    private var activeJob: Job? = null

    /**
     * Enqueues an operation and waits asynchronously until it completes or times out.
     * Returns true if operation completed successfully, false on error/timeout.
     */
    suspend fun enqueue(operation: GattOperation): Boolean {
        val completion = CompletableDeferred<Boolean>()
        val item = QueueItem(operation, completion)
        queue.add(item)
        triggerProcess()
        return completion.await()
    }

    /**
     * Must be called by GATT callback handlers (e.g. onCharacteristicWrite, onMtuChanged, onServicesDiscovered)
     * when Android signals that the pending operation has finished.
     */
    fun notifyCompleted(success: Boolean = true) {
        val current = activeItem
        if (current != null) {
            current.completion.complete(success)
        }
    }

    private fun triggerProcess() {
        if (!isExecuting.compareAndSet(false, true)) {
            return
        }

        activeJob = scope.launch {
            try {
                while (true) {
                    val item = queue.poll()
                    if (item == null) {
                        activeItem = null
                        isExecuting.set(false)
                        // Double-check if a new item was enqueued concurrently
                        if (queue.isNotEmpty() && isExecuting.compareAndSet(false, true)) {
                            continue
                        }
                        break
                    }

                    activeItem = item
                    val op = item.operation

                    try {
                        withTimeout(op.timeoutMs) {
                            onExecute(op)
                            item.completion.await()
                        }
                    } catch (e: TimeoutCancellationException) {
                        onTimeout(op)
                        item.completion.complete(false)
                    } catch (e: Exception) {
                        item.completion.complete(false)
                    }
                }
            } finally {
                activeItem = null
                isExecuting.set(false)
            }
        }
    }

    /**
     * Clears all pending operations and cancels active jobs (used on disconnect or error).
     */
    fun clear() {
        activeJob?.cancel()
        activeItem?.completion?.complete(false)
        activeItem = null
        while (queue.isNotEmpty()) {
            val item = queue.poll()
            item?.completion?.complete(false)
        }
        isExecuting.set(false)
    }

    fun pendingCount(): Int = queue.size
}
