package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.domain.EventRecord

class DplsJournalMixedTimeTest {
    @Test
    fun graphDoesNotStretchToDecadesAfterTimeSync() {
        val bootEpoch = 1_777_000_000L
        val records = listOf(
            EventRecord(4, bootEpoch + 120L, 4, 0),
            EventRecord(3, bootEpoch + 90L, 2, 0),
            EventRecord(2, 30L, 2, 0),
            EventRecord(1, 0L, 1, 0),
        )
        val sessions = journalBootSessions(
            records,
            currentBootFirst = 1L,
            currentBootEpoch = bootEpoch,
            anchors = emptyList(),
        )
        val timeline = buildJournalTimeline(records, sessions)

        assertEquals(listOf(120L, 90L, 30L, 0L), timeline.seconds)
        assertEquals(120L, timeline.span)
        assertTrue(timeline.newest < 1_000L)
    }

    @Test
    fun legacyUtcWithoutAnchorStillUsesRelativeUnixDelta() {
        val records = listOf(
            EventRecord(4, 1_777_000_120L, 4, 0),
            EventRecord(3, 1_777_000_090L, 2, 0),
            EventRecord(2, 30L, 2, 0),
            EventRecord(1, 0L, 1, 0),
        )
        val timeline = buildJournalTimeline(records)

        assertEquals(120L, timeline.span)
        assertTrue(timeline.newest < 1_000L)
    }
}
