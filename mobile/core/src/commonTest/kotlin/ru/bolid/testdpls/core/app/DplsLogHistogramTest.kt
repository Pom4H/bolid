package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.domain.EventRecord
import ru.bolid.testdpls.core.protocol.parseLogHistogramReport
import ru.bolid.testdpls.core.protocol.putU16
import ru.bolid.testdpls.core.protocol.putU32

class DplsLogHistogramTest {
    private val base = 1_700_000_000L

    @Test fun shortSpanUsesMinuteBuckets() {
        val hist = buildLogHistogram(listOf(EventRecord(1, base, 1, 0), EventRecord(2, base + 60, 8, 0), EventRecord(3, base + 300, 7, 0)), base, base + 300)
        assertEquals(15L, hist.bucketSeconds)
        assertEquals(3, hist.counts.sum())
    }

    @Test fun daySpanUsesHourBuckets() {
        val hist = buildLogHistogram(listOf(EventRecord(1, base, 1, 0), EventRecord(2, base + 20 * 3_600L, 2, 0)), base, base + 20 * 3_600L)
        assertEquals(3_600L, hist.bucketSeconds)
        assertEquals("по часам", logPeriodCaption(hist.bucketSeconds))
    }

    @Test fun histogramReportParsesWirePayload() {
        val raw = ByteArray(26)
        putU32(raw, 0, 10); putU32(raw, 4, 40); putU32(raw, 8, 1); putU32(raw, 12, 3); putU16(raw, 16, 3); putU32(raw, 18, 15)
        raw[22] = 3; raw[23] = 1; raw[24] = 0; raw[25] = 2
        val report = parseLogHistogramReport(raw)
        assertEquals(3, report?.eventCount)
        assertEquals(listOf(1, 0, 2), report?.counts)
    }

    @Test fun calendarHistogramIgnoresUnknownTimestamps() {
        val hist = buildLogHistogram(listOf(EventRecord(3, base + 100, 4, 0), EventRecord(2, 0, 2, 0), EventRecord(1, base, 3, 0))) ?: error("histogram")
        assertEquals(base, hist.startSeconds)
        assertEquals(2, hist.counts.sum())
    }

    @Test fun journalTimelineUsesRealGapsBetweenConsecutiveUtcEvents() {
        val records = listOf(EventRecord(3, base + 400, 4, 0), EventRecord(2, base + 20, 2, 0), EventRecord(1, base, 1, 0))
        assertEquals(listOf(400L, 20L, 0L), buildJournalTimeline(records).seconds)
    }

    @Test fun unknownTimestampBreaksCalendarInferenceWithoutBreakingOrder() {
        val records = listOf(EventRecord(3, base + 400, 2, 0), EventRecord(2, 0, 1, 0), EventRecord(1, base, 2, 0))
        val timeline = buildJournalTimeline(records)
        assertEquals(listOf(2L, 1L, 0L), timeline.seconds)
        assertTrue(timeline.seconds[0] > timeline.seconds[1] && timeline.seconds[1] > timeline.seconds[2])
    }

    @Test fun journalScrollbarMapsViewportAndFinger() {
        val range = journalScrollbarRange(0f, 5f, 21)
        assertEquals(0.0, range.start.toDouble(), 0.001)
        assertEquals(5.0 / 20.0, range.endInclusive.toDouble(), 0.001)
        assertEquals(199.0, journalIndexForScrollbar(1f, 200).toDouble(), 0.001)
    }

    @Test fun bucketPeriodStillSteps() {
        val span = 20 * 3_600L
        assertEquals(3_600L, niceLogBucketSeconds(span, 24))
        assertEquals(7_200L, nextLogBucketSeconds(3_600L, span, true))
        assertEquals(1_800L, nextLogBucketSeconds(3_600L, span, false))
    }
}
