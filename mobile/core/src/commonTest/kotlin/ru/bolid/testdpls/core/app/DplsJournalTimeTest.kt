package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import ru.bolid.testdpls.core.domain.EventRecord
import ru.bolid.testdpls.core.domain.JournalTimeAnchor

class DplsJournalTimeTest {
    @Test
    fun bootStartsOnTypeOneAndUptimeDrop() {
        val records = listOf(
            EventRecord(1261, 3808, 4, 0),
            EventRecord(1100, 0, 1, 6),
            EventRecord(1076, 3187, 3, 0),
            EventRecord(1077, 0, 1, 6),
        )
        assertEquals(listOf(1076L, 1077L, 1100L), journalBootFirstSequences(records))
    }

    @Test
    fun currentBootGetsPhoneClock() {
        val current = EventRecord(1261, 100, 4, 0)
        assertEquals(
            1_000L + 100L,
            journalWallSeconds(current, currentBootFirst = 1100L, currentBootEpoch = 1_000L, anchors = emptyList()),
        )
    }

    @Test
    fun utcTimestampIsAlreadyWallClockAndMustNotBeAnchoredTwice() {
        val bootEpoch = 1_777_000_000L
        val utc = bootEpoch + 120L
        val synced = EventRecord(3, utc, 4, 0)

        assertEquals(
            utc,
            journalWallSeconds(
                synced,
                currentBootFirst = 1L,
                currentBootEpoch = bootEpoch,
                anchors = emptyList(),
            ),
        )
        assertEquals(
            utc.toString(),
            journalEventTimeCaption(
                record = synced,
                records = listOf(
                    EventRecord(1, 0, 1, 0),
                    EventRecord(2, 30, 2, 0),
                    synced,
                ),
                currentBootFirst = 1L,
                currentBootEpoch = bootEpoch,
                anchors = emptyList(),
                formatWall = { it.toString() },
            ),
        )
    }

    @Test
    fun timeSyncBasisChangeIsNotMistakenForReboot() {
        val bootEpoch = 1_777_000_000L
        val records = listOf(
            EventRecord(4, bootEpoch + 120L, 4, 0),
            EventRecord(3, bootEpoch + 90L, 2, 0),
            EventRecord(2, 30, 2, 0),
            EventRecord(1, 0, 1, 0),
        )
        assertEquals(listOf(1L), journalBootFirstSequences(records))

        val sessions = journalBootSessions(
            records,
            currentBootFirst = 1L,
            currentBootEpoch = bootEpoch,
            anchors = emptyList(),
        )
        assertEquals(1, sessions.size)
        assertEquals(120L, sessions.single().lastUptimeSeconds)
    }

    @Test
    fun unsyncedPreviousBootHasNoCalendar() {
        val previous = EventRecord(1076, 3187, 3, 0)
        assertNull(
            journalWallSeconds(previous, currentBootFirst = 1100L, currentBootEpoch = 1_000L, anchors = emptyList()),
        )
    }

    @Test
    fun previouslySyncedBootKeepsItsEpoch() {
        val previous = EventRecord(1050, 40, 3, 0)
        val anchors = listOf(JournalTimeAnchor(bootFirstSequence = 1000L, bootEpochSeconds = 50_000L, lastSequence = 1076L))
        assertEquals(
            50_040L,
            journalWallSeconds(previous, currentBootFirst = 1100L, currentBootEpoch = 1_000L, anchors = anchors),
        )
        assertNull(
            journalWallSeconds(
                EventRecord(1080, 12, 2, 0),
                currentBootFirst = 1100L,
                currentBootEpoch = 1_000L,
                anchors = anchors,
            ),
        )
    }

    @Test
    fun anchorsRoundTripAndMerge() {
        val first = JournalTimeAnchor(1000L, 50_000L, 1076L)
        val encoded = encodeJournalTimeAnchors(listOf(first))
        assertEquals(listOf(first), decodeJournalTimeAnchors(encoded))
        val grown = mergeJournalTimeAnchor(listOf(first), JournalTimeAnchor(1000L, 50_100L, 1100L))
        assertEquals(1, grown.size)
        assertEquals(1100L, grown[0].lastSequence)
        assertEquals(50_100L, grown[0].bootEpochSeconds)
        val two = mergeJournalTimeAnchor(grown, JournalTimeAnchor(1200L, 80_000L, 1261L))
        assertEquals(listOf(1200L, 1000L), two.map { it.bootFirstSequence })
    }

    @Test
    fun uptimeCaptionIsRelative() {
        assertEquals("12 с от включения", journalUptimeCaption(12))
        assertEquals("3 мин от включения", journalUptimeCaption(180))
        assertEquals("1 ч 3 мин от включения", journalUptimeCaption(3780))
        assertEquals("12 мин", journalDurationCaption(720))
    }

    @Test
    fun downtimeUsesJournalLastUptimeNotStaleAnchor() {
        val records = listOf(
            EventRecord(20, 10, 2, 0),
            EventRecord(11, 0, 1, 0),
            EventRecord(10, 100, 2, 0),
            EventRecord(1, 0, 1, 0),
        )
        val sessions = journalBootSessions(
            records,
            currentBootFirst = 11L,
            currentBootEpoch = 10_000L,
            anchors = listOf(JournalTimeAnchor(1L, 5_000L, 8L)),
        )
        assertEquals(2, sessions.size)
        assertEquals(100L, sessions[0].lastUptimeSeconds)
        assertEquals(10L, sessions[0].lastSequence)
        assertEquals(4_900L, journalDowntimeSeconds(sessions[0], sessions[1]))
    }

    @Test
    fun downtimeUnknownWithoutOlderEpoch() {
        val records = listOf(
            EventRecord(11, 5, 2, 0),
            EventRecord(10, 100, 2, 0),
            EventRecord(1, 0, 1, 0),
        )
        val sessions = journalBootSessions(
            records,
            currentBootFirst = 11L,
            currentBootEpoch = 10_000L,
            anchors = emptyList(),
        )
        assertEquals(2, sessions.size)
        assertNull(sessions[0].epochSeconds)
        assertNull(journalDowntimeSeconds(sessions[0], sessions[1]))
    }

    @Test
    fun unsyncedMiddleBootGetsWallRangeNotExactDate() {
        val records = listOf(
            EventRecord(20, 5, 2, 0),
            EventRecord(16, 0, 1, 0),
            EventRecord(15, 20, 2, 0),
            EventRecord(11, 0, 1, 0),
            EventRecord(10, 50, 2, 0),
            EventRecord(1, 0, 1, 0),
        )
        val sessions = journalBootSessions(
            records,
            currentBootFirst = 16L,
            currentBootEpoch = 2_000L,
            anchors = listOf(JournalTimeAnchor(1L, 1_000L, 10L)),
        )
        assertEquals(3, sessions.size)
        assertNull(sessions[1].epochSeconds)
        assertEquals(1_050L..1_980L, journalBootEpochRange(sessions[1], sessions))
        assertEquals(1_070L..2_000L, journalEventWallRange(EventRecord(15, 20, 2, 0), sessions))
        assertEquals(listOf(465L, 465L), journalSessionGaps(sessions))
        assertEquals(
            "между 1070 и 2000",
            journalEventTimeCaption(
                record = EventRecord(15, 20, 2, 0),
                records = records,
                currentBootFirst = 16L,
                currentBootEpoch = 2_000L,
                anchors = listOf(JournalTimeAnchor(1L, 1_000L, 10L)),
                formatWall = { it.toString() },
            ),
        )
        assertNull(
            journalWallSeconds(
                EventRecord(15, 20, 2, 0),
                currentBootFirst = 16L,
                currentBootEpoch = 2_000L,
                anchors = listOf(JournalTimeAnchor(1L, 1_000L, 10L)),
            ),
        )
    }
}
