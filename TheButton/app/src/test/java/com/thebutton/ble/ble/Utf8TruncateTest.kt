package com.thebutton.ble.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Utf8TruncateTest {

    @Test
    fun ascii_fitsExactly() {
        val out = utf8Truncate("abcdef", 6)
        assertEquals("abcdef", out.decodeToString())
    }

    @Test
    fun ascii_truncatesToLimit() {
        val out = utf8Truncate("abcdefgh", 4)
        assertEquals("abcd", out.decodeToString())
    }

    @Test
    fun cyrillic_neverSplitsACharacter() {
        // Each Cyrillic letter is 2 bytes in UTF-8. A byte-slice at 31 would cut
        // the 16th letter in half; the truncation must stop at 30 bytes instead.
        val name = "Устройство-ДПЛС-Один"
        for (limit in 1..name.encodeToByteArray().size) {
            val out = utf8Truncate(name, limit)
            assertTrue("limit=$limit produced ${out.size} bytes", out.size <= limit)
            // Round-trips cleanly — i.e. no broken trailing sequence.
            assertTrue(name.startsWith(out.decodeToString()))
        }
    }

    @Test
    fun mixed_stops_on_character_boundary() {
        val out = utf8Truncate("AБВ", 2) // A=1 byte, Б=2 bytes: only "A" fits
        assertEquals("A", out.decodeToString())
    }

    @Test
    fun surrogatePair_keptWhole() {
        val emoji = "ab😀cd" // 😀 is 4 bytes in UTF-8
        assertEquals("ab", utf8Truncate(emoji, 5).decodeToString())
        assertEquals("ab😀", utf8Truncate(emoji, 6).decodeToString())
    }
}
