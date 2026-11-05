package net.hexnet.app.core.crypto

/**
 * Sliding-window replay protection filter (RFC 6479 / IPsec model).
 * Prevents replay attacks over untrusted mesh links by tracking seen sequence numbers.
 *
 * Window size: 128 packets (two 64-bit Long bitmasks).
 */
class ReplayFilter(
    private val windowSize: Int = 128
) {
    private var maxSeenSeq: Long = -1L
    private var windowBitmap0: Long = 0L // Packets [maxSeenSeq-63 .. maxSeenSeq]
    private var windowBitmap1: Long = 0L // Packets [maxSeenSeq-127 .. maxSeenSeq-64]

    /**
     * Checks if a sequence number is acceptable (not a duplicate and within acceptable window bounds),
     * and if valid, updates the window state.
     *
     * @return true if packet is accepted, false if packet is a duplicate or too old (replayed).
     */
    @Synchronized
    fun validateAndAdvance(seq: Long): Boolean {
        if (seq < 0) return false

        // First packet ever seen
        if (maxSeenSeq == -1L) {
            maxSeenSeq = seq
            windowBitmap0 = 1L
            windowBitmap1 = 0L
            return true
        }

        // Case 1: Sequence number is greater than max seen so far (new packet advancing the window)
        if (seq > maxSeenSeq) {
            val diff = seq - maxSeenSeq
            if (diff >= windowSize) {
                // Large leap: reset window
                windowBitmap0 = 1L
                windowBitmap1 = 0L
            } else if (diff >= 64) {
                // Shift window by diff
                val shift = (diff - 64).toInt()
                windowBitmap1 = windowBitmap0 shl shift
                windowBitmap0 = 1L
            } else {
                val shift = diff.toInt()
                windowBitmap1 = (windowBitmap1 shl shift) or (windowBitmap0 ushr (64 - shift))
                windowBitmap0 = (windowBitmap0 shl shift) or 1L
            }
            maxSeenSeq = seq
            return true
        }

        // Case 2: Sequence number is within or older than the window
        val diff = maxSeenSeq - seq
        if (diff >= windowSize) {
            // Packet is older than the window boundary -> drop as potential replay
            return false
        }

        // Check if bit is already set (duplicate)
        return if (diff < 64) {
            val mask = 1L shl diff.toInt()
            if ((windowBitmap0 and mask) != 0L) {
                false // Duplicate
            } else {
                windowBitmap0 = windowBitmap0 or mask
                true
            }
        } else {
            val mask = 1L shl (diff - 64).toInt()
            if ((windowBitmap1 and mask) != 0L) {
                false // Duplicate
            } else {
                windowBitmap1 = windowBitmap1 or mask
                true
            }
        }
    }

    @Synchronized
    fun reset() {
        maxSeenSeq = -1L
        windowBitmap0 = 0L
        windowBitmap1 = 0L
    }

    fun getMaxSeenSequence(): Long = maxSeenSeq
}
