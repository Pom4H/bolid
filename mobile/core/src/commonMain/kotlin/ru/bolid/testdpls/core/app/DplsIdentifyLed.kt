package ru.bolid.testdpls.core.app

/**
 * Identify LED timeline. Must stay identical to firmware
 * `DPLS_LED_IDENTIFY_HALF_MS`: 1 Hz, 50 % duty, starts ON.
 */
internal object DplsIdentifyLed {
    const val HALF_MS = 500
    const val PERIOD_MS = HALF_MS * 2

    fun on(elapsedSinceStartMs: Long): Boolean {
        val elapsed = if (elapsedSinceStartMs < 0) 0 else elapsedSinceStartMs
        return (elapsed % PERIOD_MS) < HALF_MS
    }

    /** LED has already been running for about half the ATT write RTT when the write completes. */
    fun phaseAtAckMs(sentAtMs: Long, ackAtMs: Long): Long {
        val rtt = (ackAtMs - sentAtMs).coerceAtLeast(0)
        return rtt / 2
    }
}
