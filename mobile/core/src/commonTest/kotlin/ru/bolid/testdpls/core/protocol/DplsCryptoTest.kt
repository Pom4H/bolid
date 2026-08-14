package ru.bolid.testdpls.core.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals

class DplsCryptoTest {
    @Test
    fun sha256MatchesKnownAnswer() {
        assertContentEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".hexToBytesOrNull(),
            DplsCrypto.sha256("abc".encodeToByteArray()),
        )
    }

    @Test
    fun hmacSha256MatchesKnownAnswer() {
        assertContentEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8".hexToBytesOrNull(),
            DplsCrypto.hmacSha256("key".encodeToByteArray(), "The quick brown fox jumps over the lazy dog".encodeToByteArray()),
        )
    }

    @Test
    fun pbkdf2Sha256MatchesKnownAnswer() {
        assertContentEquals(
            "5ec02b91a4b59c6f59dd5fbe4ca649ece4fa8568cdb8ba36cf41426e8805522b".hexToBytesOrNull(),
            DplsCrypto.pbkdf2HmacSha256("password".encodeToByteArray(), "salt".encodeToByteArray(), 10_000, 32),
        )
    }

    @Test
    fun proofMessageHasSharedWireLayout() {
        val device = ByteArray(16) { it.toByte() }
        val client = ByteArray(16) { (it + 16).toByte() }
        val proof = DplsAuth.proofMessage(device, client, 0x78563412)
        assertContentEquals(device, proof.copyOfRange(0, 16))
        assertContentEquals(client, proof.copyOfRange(16, 32))
        assertContentEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), proof.copyOfRange(32, 36))
    }
}
