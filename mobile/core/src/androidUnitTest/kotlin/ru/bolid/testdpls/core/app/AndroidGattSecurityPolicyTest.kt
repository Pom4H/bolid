package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidGattSecurityPolicyTest {
    @Test
    fun encryptedRxErrorsRequirePairing() {
        assertTrue(AndroidGattSecurityPolicy.requiresPairing(5))
        assertTrue(AndroidGattSecurityPolicy.requiresPairing(15))
        assertEquals(
            WriteDisposition.PAIRING_REQUIRED,
            AndroidGattSecurityPolicy.classifyWrite(5, retryCount = 0, maxRetries = 3),
        )
        assertEquals(
            WriteDisposition.PAIRING_REQUIRED,
            AndroidGattSecurityPolicy.classifyWrite(15, retryCount = 0, maxRetries = 3),
        )
    }

    @Test
    fun securityErrorsAreNeverOrdinaryRetries() {
        assertEquals(
            WriteDisposition.PAIRING_REQUIRED,
            AndroidGattSecurityPolicy.classifyWrite(15, retryCount = 3, maxRetries = 3),
        )
    }

    @Test
    fun transientBackpressureIsBounded() {
        assertEquals(
            WriteDisposition.RETRY,
            AndroidGattSecurityPolicy.classifyWrite(143, retryCount = 0, maxRetries = 3),
        )
        assertEquals(
            WriteDisposition.FAIL,
            AndroidGattSecurityPolicy.classifyWrite(143, retryCount = 3, maxRetries = 3),
        )
    }

    @Test
    fun successAndPermanentFailureStayDistinct() {
        assertEquals(
            WriteDisposition.COMPLETE,
            AndroidGattSecurityPolicy.classifyWrite(0, retryCount = 0, maxRetries = 3),
        )
        assertEquals(
            WriteDisposition.FAIL,
            AndroidGattSecurityPolicy.classifyWrite(42, retryCount = 0, maxRetries = 3),
        )
        assertFalse(AndroidGattSecurityPolicy.requiresPairing(42))
    }
}
