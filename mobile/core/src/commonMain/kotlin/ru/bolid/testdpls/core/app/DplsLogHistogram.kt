package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.domain.EventRecord

internal data class JournalTimeline(
    val seconds: List<Long>,
) {
    val oldest: Long get() = seconds.minOrNull() ?: 0L
    val newest: Long get() = seconds.maxOrNull() ?: 0L
    val span: Long get() = (newest - oldest).coerceAtLeast(1L)

    fun at(exactIndex: Float): Long {
        if (seconds.isEmpty()) return 0L
        val last = seconds.lastIndex
        val index = exactIndex.toInt().coerceIn(0, last)
        val frac = (exactIndex - index).coerceIn(0f, 1f)
        val start = seconds[index]
        val end = seconds[(index + 1).coerceAtMost(last)]
        return start + ((end - start) * frac).toLong()
    }
}

internal fun buildJournalTimeline(
    records: List<EventRecord>,
    sessions: List<JournalBootSession> = emptyList(),
): JournalTimeline {
    if (records.isEmpty()) return JournalTimeline(emptyList())
    val timeline = LongArray(records.size)
    val order = records.indices.sortedBy { records[it].sequence }
    val gaps = journalSessionGaps(sessions)
    var bootOffset = 0L
    var prevUptime = records[order.first()].timestampSeconds
    var prevSessionFirst = journalSessionFor(records[order.first()].sequence, sessions)?.firstSequence
    for (index in order) {
        val record = records[index]
        val uptime = record.timestampSeconds
        val session = journalSessionFor(record.sequence, sessions)
        val sessionFirst = session?.firstSequence
        if (sessions.isNotEmpty() && sessionFirst != null && prevSessionFirst != null && sessionFirst != prevSessionFirst) {
            val olderIndex = sessions.indexOfFirst { it.firstSequence == prevSessionFirst }
            val gap = gaps.getOrNull(olderIndex) ?: 1L
            bootOffset += prevUptime + gap
            prevSessionFirst = sessionFirst
        } else if (sessions.isEmpty() && uptime + 1L < prevUptime) {
            bootOffset += prevUptime + 1L
        }
        timeline[index] = bootOffset + uptime
        prevUptime = uptime
    }
    return JournalTimeline(timeline.toList())
}

internal data class JournalStrip(
    val oldestSeconds: Long,
    val newestSeconds: Long,
    val bucketSeconds: Long,
    val counts: List<Int>,
    val alertCounts: List<Int> = emptyList(),
) {
    val barCount: Int get() = counts.size
    val spanSeconds: Long get() = (newestSeconds - oldestSeconds).coerceAtLeast(1L)
    val alerts: List<Int>
        get() = if (alertCounts.size == counts.size) alertCounts else List(counts.size) { 0 }

    fun timeAtFraction(fraction: Float): Long {
        val t = newestSeconds - (fraction.coerceIn(0f, 1f).toDouble() * spanSeconds).toLong()
        return t.coerceIn(oldestSeconds, newestSeconds)
    }

    fun fractionAtTime(time: Long): Float {
        return ((newestSeconds - time).toDouble() / spanSeconds.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun windowForTimes(newerTime: Long, olderTime: Long): ClosedFloatingPointRange<Float> {
        val a = fractionAtTime(newerTime)
        val b = fractionAtTime(olderTime)
        return minOf(a, b)..maxOf(a, b)
    }
}

internal fun journalListIndexAtTime(timelines: List<Long>, time: Long): Int {
    if (timelines.isEmpty()) return 0
    val index = timelines.indexOfFirst { it <= time }
    return if (index >= 0) index else timelines.lastIndex
}

internal fun journalScrollbarRange(firstExact: Float, lastExact: Float, itemCount: Int): ClosedFloatingPointRange<Float> {
    if (itemCount <= 1) return 0f..1f
    val last = (itemCount - 1).toFloat()
    val start = (firstExact / last).coerceIn(0f, 1f)
    val end = (lastExact / last).coerceIn(0f, 1f)
    val lo = minOf(start, end)
    val hi = maxOf(start, end)
    val minSpan = (1f / itemCount).coerceIn(0.04f, 1f)
    return lo..hi.coerceAtLeast((lo + minSpan).coerceAtMost(1f))
}

internal fun journalIndexForScrollbar(startFraction: Float, itemCount: Int): Float {
    if (itemCount <= 1) return 0f
    return startFraction.coerceIn(0f, 1f) * (itemCount - 1)
}

internal fun nextLogBucketSeconds(current: Long, spanSeconds: Long, coarser: Boolean): Long {
    val allowed = allowedLogBuckets(spanSeconds)
    val exact = allowed.indexOf(current)
    val idx = if (exact >= 0) {
        exact
    } else {
        allowed.indices.minByOrNull { kotlin.math.abs(allowed[it] - current) } ?: 0
    }
    val next = if (coarser) idx + 1 else idx - 1
    return allowed[next.coerceIn(0, allowed.lastIndex)]
}

internal fun allowedLogBuckets(spanSeconds: Long): List<Long> {
    val span = spanSeconds.coerceAtLeast(1L)
    val minBucket = (span + 63L) / 64L
    return LOG_BUCKET_STEPS.filter { it >= minBucket && it <= span }.ifEmpty {
        listOf(niceLogBucketSeconds(span, 32))
    }
}

internal fun isJournalAlert(type: Int): Boolean = type == 5 || type == 6 || type == 7 || type == 13 || type == 14

internal fun buildJournalStrip(
    records: List<EventRecord>,
    timeline: JournalTimeline,
    bucketSeconds: Long? = null,
): JournalStrip? {
    if (records.isEmpty() || timeline.seconds.size != records.size) return null
    val oldest = timeline.oldest
    val newest = timeline.newest
    val span = (newest - oldest).coerceAtLeast(1L)
    val allowed = allowedLogBuckets(span)
    val bucket = when {
        bucketSeconds != null && bucketSeconds > 0L ->
            allowed.minByOrNull { kotlin.math.abs(it - bucketSeconds) } ?: allowed.first()
        else -> {
            val auto = niceLogBucketSeconds(span, 32)
            allowed.minByOrNull { kotlin.math.abs(it - auto) } ?: auto
        }
    }
    val count = (((span + bucket) / bucket).toInt()).coerceIn(1, 64)
    val counts = IntArray(count)
    val alerts = IntArray(count)
    records.forEachIndexed { index, record ->
        val chrono = ((timeline.seconds[index] - oldest) / bucket).toInt().coerceIn(0, count - 1)
        val left = count - 1 - chrono
        counts[left] += 1
        if (isJournalAlert(record.type)) alerts[left] += 1
    }
    return JournalStrip(oldest, newest, bucket, counts.toList(), alerts.toList())
}

internal val LOG_BUCKET_STEPS = longArrayOf(
    1, 2, 5, 10, 15, 30,
    60, 120, 300, 600, 900, 1_800,
    3_600, 7_200, 10_800, 21_600, 43_200,
    86_400, 172_800, 604_800,
)

internal data class LogTimeHistogram(
    val startSeconds: Long,
    val bucketSeconds: Long,
    val counts: List<Int>,
    val alertCounts: List<Int> = emptyList(),
) {
    val bucketCount: Int get() = counts.size
    val alerts: List<Int>
        get() = if (alertCounts.size == counts.size) alertCounts else List(counts.size) { 0 }

    fun bucketStart(index: Int): Long = startSeconds + bucketSeconds * index.coerceAtLeast(0)

    fun rangeSeconds(fromBucket: Int, toBucket: Int): Pair<Long, Long> {
        val from = minOf(fromBucket, toBucket).coerceIn(0, bucketCount - 1)
        val to = maxOf(fromBucket, toBucket).coerceIn(0, bucketCount - 1)
        return bucketStart(from) to bucketStart(to + 1)
    }

    fun bucketIndex(timestampSeconds: Long): Int {
        if (bucketCount <= 0 || bucketSeconds <= 0L) return 0
        return ((timestampSeconds - startSeconds) / bucketSeconds).toInt().coerceIn(0, bucketCount - 1)
    }

    fun firstIndexInRange(records: List<EventRecord>, fromBucket: Int, toBucket: Int): Int {
        if (records.isEmpty() || bucketCount <= 0) return 0
        val window = rangeSeconds(fromBucket, toBucket)
        val inside = records.indexOfFirst {
            it.timestampSeconds >= window.first && it.timestampSeconds < window.second
        }
        if (inside >= 0) return inside
        val mid = (window.first + window.second) / 2
        return records.indices.minByOrNull { index ->
            kotlin.math.abs(records[index].timestampSeconds - mid)
        } ?: 0
    }

    fun shiftedWindow(fromBucket: Int, toBucket: Int, newStart: Int): IntRange {
        val last = (bucketCount - 1).coerceAtLeast(0)
        val width = (maxOf(fromBucket, toBucket) - minOf(fromBucket, toBucket)).coerceAtLeast(0)
        val start = newStart.coerceIn(0, (last - width).coerceAtLeast(0))
        return start..(start + width).coerceAtMost(last)
    }
}

internal fun niceLogBucketSeconds(spanSeconds: Long, targetBars: Int = 24): Long {
    val span = spanSeconds.coerceAtLeast(1L)
    val raw = (span + targetBars - 1L) / targetBars
    return LOG_BUCKET_STEPS.firstOrNull { it >= raw } ?: ((raw + 86_399L) / 86_400L * 86_400L)
}

internal fun logPeriodCaption(bucketSeconds: Long): String = when {
    bucketSeconds < 60L -> "по секундам"
    bucketSeconds < 3_600L -> "по ${bucketSeconds / 60L} мин"
    bucketSeconds < 86_400L -> if (bucketSeconds == 3_600L) "по часам" else "по ${bucketSeconds / 3_600L} ч"
    else -> if (bucketSeconds == 86_400L) "по дням" else "по ${bucketSeconds / 86_400L} дн"
}

internal fun niceCountAxis(maxValue: Int): Int {
    val value = maxValue.coerceAtLeast(1)
    val steps = intArrayOf(1, 2, 4, 5, 8, 10, 15, 20, 25, 30, 40, 50, 75, 100, 150, 200, 250, 500, 1000)
    return steps.firstOrNull { it >= value } ?: ((value + 999) / 1000 * 1000)
}

internal fun buildLogHistogram(
    records: List<EventRecord>,
    targetBars: Int = 32,
    bucketSeconds: Long? = null,
): LogTimeHistogram? {
    if (records.isEmpty()) return null
    return buildLogHistogram(
        records,
        records.minOf { it.timestampSeconds },
        records.maxOf { it.timestampSeconds },
        targetBars,
        bucketSeconds,
    )
}

internal fun buildLogHistogram(
    records: List<EventRecord>,
    firstSeconds: Long,
    lastSeconds: Long,
    targetBars: Int = 24,
    bucketSeconds: Long? = null,
): LogTimeHistogram {
    val start = minOf(firstSeconds, lastSeconds)
    val end = maxOf(firstSeconds, lastSeconds)
    val span = (end - start).coerceAtLeast(1L)
    val minBucket = (span + 63L) / 64L
    val allowed = LOG_BUCKET_STEPS.filter { it >= minBucket && it <= span }.ifEmpty {
        listOf(niceLogBucketSeconds(span, targetBars))
    }
    val bucket = when {
        bucketSeconds != null && bucketSeconds > 0L ->
            allowed.minByOrNull { kotlin.math.abs(it - bucketSeconds) } ?: allowed.first()
        else -> {
            val auto = niceLogBucketSeconds(span, targetBars)
            allowed.minByOrNull { kotlin.math.abs(it - auto) } ?: auto
        }
    }
    val count = (((span + bucket) / bucket).toInt()).coerceIn(1, 64)
    val counts = IntArray(count)
    val alerts = IntArray(count)
    records.forEach { record ->
        val index = ((record.timestampSeconds - start) / bucket).toInt().coerceIn(0, count - 1)
        counts[index] += 1
        if (isJournalAlert(record.type)) alerts[index] += 1
    }
    return LogTimeHistogram(start, bucket, counts.toList(), alerts.toList())
}
