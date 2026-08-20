package ru.bolid.testdpls.core.app

/**
 * Pure Android GATT status policy.
 *
 * The transport owns timing and BluetoothDevice operations; this object owns the
 * meaning of write callback statuses so security failures can never be confused
 * with ordinary transient write backpressure.
 */
internal object AndroidGattSecurityPolicy {
    private const val GATT_SUCCESS = 0
    private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
    private const val GATT_INSUFFICIENT_ENCRYPTION = 15

    private val transientWriteStatuses = setOf(8, 14, 17, 143, 201)

    fun requiresPairing(status: Int): Boolean =
        status == GATT_INSUFFICIENT_AUTHENTICATION ||
            status == GATT_INSUFFICIENT_ENCRYPTION

    fun classifyWrite(status: Int, retryCount: Int, maxRetries: Int): WriteDisposition = when {
        status == GATT_SUCCESS -> WriteDisposition.COMPLETE
        requiresPairing(status) -> WriteDisposition.PAIRING_REQUIRED
        status in transientWriteStatuses && retryCount < maxRetries -> WriteDisposition.RETRY
        else -> WriteDisposition.FAIL
    }
}

internal enum class WriteDisposition {
    COMPLETE,
    PAIRING_REQUIRED,
    RETRY,
    FAIL,
}
