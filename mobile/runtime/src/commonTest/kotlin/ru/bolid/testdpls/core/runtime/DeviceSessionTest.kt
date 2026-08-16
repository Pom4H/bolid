package ru.bolid.testdpls.core.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSessionTest {
    @Test
    fun authenticationDoesNotImplyVerifiedIdentity() {
        val endpoint = LinkEndpoint.Ble("ble-1")
        val candidate = NodeId(0x1234)
        val challenge = SessionChallenge(
            sessionId = 0x78563412,
            clientNonce = ByteArray(16) { it.toByte() },
            deviceNonce = ByteArray(16) { (0x20 + it).toByte() },
            authSalt = ByteArray(16) { (0x40 + it).toByte() },
            initialized = true,
        )

        val authenticating: DeviceSession = DeviceSession.Authenticating(
            endpoint = endpoint,
            challenge = challenge,
            candidateNodeId = candidate,
        )
        assertFalse(authenticating.isAuthenticated)
        assertTrue(authenticating.credentialsReady)
        assertEquals(challenge, authenticating.challengeOrNull)
        assertEquals(candidate, authenticating.candidateNodeIdOrNull)
        assertNull(authenticating.nodeIdOrNull)

        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val auth = AuthSession(challenge.sessionId, token, challenge.authSalt)
        val synchronizing: DeviceSession = DeviceSession.Synchronizing(
            endpoint = endpoint,
            auth = auth,
            candidateNodeId = candidate,
        )
        assertTrue(synchronizing.isAuthenticated)
        assertEquals(candidate, synchronizing.candidateNodeIdOrNull)
        assertNull(synchronizing.nodeIdOrNull)

        val online: DeviceSession = DeviceSession.Online(
            nodeId = candidate,
            endpoint = endpoint,
            auth = auth,
        )
        assertTrue(online.isAuthenticated)
        assertNull(online.challengeOrNull)
        assertEquals(candidate, online.nodeIdOrNull)

        val payload = online.authOrNull!!.authenticatedPayload()
        assertEquals(12, payload.size)
        assertContentEquals(token, payload.copyOfRange(4, 12))
    }

    @Test
    fun recoveringDropsAuthenticationButRetainsVerifiedIdentityAndRoute() {
        val endpoint = LinkEndpoint.Ble("ble-1")
        val recovering: DeviceSession = DeviceSession.Recovering(
            nodeId = NodeId(0x20),
            endpoint = endpoint,
        )

        assertFalse(recovering.isAuthenticated)
        assertEquals(NodeId(0x20), recovering.nodeIdOrNull)
        assertEquals(endpoint, recovering.endpointOrNull)
        assertNull(recovering.authOrNull)
    }
}
