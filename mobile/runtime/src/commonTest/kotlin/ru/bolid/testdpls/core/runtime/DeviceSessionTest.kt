package ru.bolid.testdpls.core.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSessionTest {
    @Test
    fun lifecycleOwnsChallengeAndAuthMaterial() {
        val endpoint = LinkEndpoint.Ble("ble-1")
        val clientNonce = ByteArray(16) { it.toByte() }
        val challenge = SessionChallenge(
            sessionId = 0x78563412,
            clientNonce = clientNonce,
            deviceNonce = ByteArray(16) { (0x20 + it).toByte() },
            authSalt = ByteArray(16) { (0x40 + it).toByte() },
            initialized = true,
        )

        val authenticating: DeviceSession = DeviceSession.Authenticating(endpoint, challenge)
        assertFalse(authenticating.isAuthenticated)
        assertTrue(authenticating.credentialsReady)
        assertEquals(challenge, authenticating.challengeOrNull)
        assertNull(authenticating.authOrNull)

        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val online: DeviceSession = DeviceSession.Online(
            nodeId = NodeId(0x1234),
            endpoint = endpoint,
            auth = AuthSession(challenge.sessionId, token, challenge.authSalt),
        )
        assertTrue(online.isAuthenticated)
        assertNull(online.challengeOrNull)
        assertEquals(NodeId(0x1234), online.nodeIdOrNull)

        val payload = online.authOrNull!!.authenticatedPayload()
        assertEquals(12, payload.size)
        assertContentEquals(token, payload.copyOfRange(4, 12))
    }

    @Test
    fun recoveringDropsAuthenticationButRetainsIdentityAndRoute() {
        val endpoint = LinkEndpoint.Routed(NodeId(0x10), NodeId(0x20))
        val recovering: DeviceSession = DeviceSession.Recovering(NodeId(0x20), endpoint, attempt = 3)

        assertFalse(recovering.isAuthenticated)
        assertEquals(NodeId(0x20), recovering.nodeIdOrNull)
        assertEquals(endpoint, recovering.endpointOrNull)
        assertNull(recovering.authOrNull)
    }
}
