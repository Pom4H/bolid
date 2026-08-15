package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.bolid.testdpls.core.domain.EventRecord
import ru.bolid.testdpls.core.domain.JournalTimeAnchor
import ru.bolid.testdpls.core.protocol.parseLogHistogramReport
import ru.bolid.testdpls.core.protocol.putU16
import ru.bolid.testdpls.core.protocol.putU32

class DplsLogHistogramTest {
    @Test
    fun shortSpanUsesMinuteBuckets() {
        val hist = buildLogHistogram(
            records = listOf(
                EventRecord(1, 100, 1, 0),
                EventRecord(2, 160, 8, 0),
                EventRecord(3, 400, 7, 0),
            ),
            firstSeconds = 100,
            lastSeconds = 400,
        )
        assertEquals(15L, hist.bucketSeconds)
        assertEquals(100L, hist.startSeconds)
        assertEquals(1, hist.counts[0])
        assertEquals(1, hist.counts[(160 - 100) / 15])
        assertEquals(1, hist.counts.last())
    }

    @Test
    fun daySpanUsesHourBuckets() {
        val hist = buildLogHistogram(
            records = listOf(EventRecord(1, 0, 1, 0), EventRecord(2, 20 * 3_600L, 2, 0)),
            firstSeconds = 0,
            lastSeconds = 20 * 3_600L,
        )
        assertEquals(3_600L, hist.bucketSeconds)
        assertEquals("по часам", logPeriodCaption(hist.bucketSeconds))
    }

    @Test
    fun selectedRangeCoversWholeBuckets() {
        val hist = LogTimeHistogram(startSeconds = 10, bucketSeconds = 5, counts = listOf(1, 2, 3, 4))
        assertEquals(10L to 20L, hist.rangeSeconds(0, 1))
        assertEquals(15L to 30L, hist.rangeSeconds(3, 1))
    }

    @Test
    fun histogramReportParsesWirePayload() {
        val raw = ByteArray(26)
        putU32(raw, 0, 10)
        putU32(raw, 4, 40)
        putU32(raw, 8, 1)
        putU32(raw, 12, 3)
        putU16(raw, 16, 3)
        putU32(raw, 18, 15)
        raw[22] = 3
        raw[23] = 1
        raw[24] = 0
        raw[25] = 2
        val report = parseLogHistogramReport(raw)
        assertEquals(10L, report?.firstTimestampSeconds)
        assertEquals(40L, report?.lastTimestampSeconds)
        assertEquals(3, report?.eventCount)
        assertEquals(15L, report?.bucketSeconds)
        assertEquals(listOf(1, 0, 2), report?.counts)
    }

    @Test
    fun histogramUsesMinAndMaxTimestampsNotSequenceOrder() {
        val records = listOf(
            EventRecord(3, 10, 1, 0),
            EventRecord(2, 100_000, 2, 0),
            EventRecord(1, 50_000, 3, 0),
        )
        val hist = buildLogHistogram(records) ?: error("histogram")
        assertEquals(10L, hist.startSeconds)
        assertEquals(3, hist.counts.sum())
        assertEquals(true, hist.counts.size > 1)
    }

    @Test
    fun journalStripLeavesEmptyBucketsBetweenEvents() {
        val records = listOf(
            EventRecord(3, 400, 4, 0),
            EventRecord(2, 20, 2, 0),
            EventRecord(1, 0, 1, 0),
        )
        val timeline = buildJournalTimeline(records)
        val strip = buildJournalStrip(records, timeline, bucketSeconds = 20L) ?: error("strip")
        assertEquals(3, strip.counts.sum())
        assertEquals(true, strip.counts.any { it == 0 })
        assertEquals(0.0, strip.fractionAtTime(timeline.newest).toDouble(), 0.001)
        assertEquals(1.0, strip.fractionAtTime(timeline.oldest).toDouble(), 0.001)
        assertEquals(0, journalListIndexAtTime(timeline.seconds, strip.timeAtFraction(0f)))
        assertEquals(records.lastIndex, journalListIndexAtTime(timeline.seconds, strip.timeAtFraction(1f)))
    }

    @Test
    fun journalTimelineKeepsOrderAcrossReboot() {
        val records = listOf(
            EventRecord(3, 10, 2, 0),
            EventRecord(2, 0, 1, 0),
            EventRecord(1, 100, 2, 0),
        )
        val timeline = buildJournalTimeline(records)
        assertEquals(true, timeline.seconds[0] > timeline.seconds[1])
        assertEquals(true, timeline.seconds[1] > timeline.seconds[2])
        val strip = buildJournalStrip(records, timeline) ?: error("strip")
        assertEquals(0, journalListIndexAtTime(timeline.seconds, strip.timeAtFraction(0f)))
        assertEquals(2, journalListIndexAtTime(timeline.seconds, strip.timeAtFraction(1f)))
    }

    @Test
    fun journalTimelineIncludesKnownDowntime() {
        val records = listOf(
            EventRecord(3, 10, 2, 0),
            EventRecord(2, 0, 1, 0),
            EventRecord(1, 100, 2, 0),
        )
        val sessions = journalBootSessions(
            records,
            currentBootFirst = 2L,
            currentBootEpoch = 1_000L,
            anchors = listOf(JournalTimeAnchor(1L, 100L, 1L)),
        )
        val timeline = buildJournalTimeline(records, sessions)
        assertEquals(listOf(910L, 900L, 100L), timeline.seconds)
        assertEquals(810L, timeline.span)
    }

    @Test
    fun journalScrollbarMapsViewportAndFinger() {
        val range = journalScrollbarRange(0f, 5f, 21)
        assertEquals(0.0, range.start.toDouble(), 0.001)
        assertEquals(5.0 / 20.0, range.endInclusive.toDouble(), 0.001)
        assertEquals(0.0, journalIndexForScrollbar(0f, 200).toDouble(), 0.001)
        assertEquals(199.0, journalIndexForScrollbar(1f, 200).toDouble(), 0.001)
        assertEquals(99.5, journalIndexForScrollbar(0.5f, 200).toDouble(), 0.001)
    }

    @Test
    fun verticalSwipeStepsBucketPeriod() {
        val span = 20 * 3_600L
        assertEquals(3_600L, niceLogBucketSeconds(span, 24))
        assertEquals(7_200L, nextLogBucketSeconds(3_600L, span, coarser = true))
        assertEquals(1_800L, nextLogBucketSeconds(3_600L, span, coarser = false))
        val coarsest = allowedLogBuckets(span).last()
        assertEquals(coarsest, nextLogBucketSeconds(coarsest, span, coarser = true))
    }

    @Test
    fun alertsAreCountedSeparately() {
        val hist = buildLogHistogram(
            records = listOf(
                EventRecord(1, 0, 2, 0),
                EventRecord(2, 0, 14, 1),
                EventRecord(3, 90, 5, 2),
            ),
            firstSeconds = 0,
            lastSeconds = 90,
        )
        assertEquals(3, hist.counts.sum())
        assertEquals(2, hist.alerts.sum())
        assertEquals(4, niceCountAxis(4))
        assertEquals(10, niceCountAxis(9))
    }

    @Test
    fun explicitBucketIsKeptWhenAllowed() {
        val hist = buildLogHistogram(
            records = listOf(EventRecord(1, 0, 1, 0), EventRecord(2, 20 * 3_600L, 2, 0)),
            firstSeconds = 0,
            lastSeconds = 20 * 3_600L,
            bucketSeconds = 7_200L,
        )
        assertEquals(7_200L, hist.bucketSeconds)
        assertEquals("по 2 ч", logPeriodCaption(hist.bucketSeconds))
    }
}
