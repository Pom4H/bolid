package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.domain.EventRecord
import ru.bolid.testdpls.core.domain.JournalTimeAnchor

private fun journalTimestampIsUtc(timestampSeconds: Long): Boolean =
    eventTimestampBasis(timestampSeconds) == "utc"

/**
 * Возвращает uptime события внутри boot-сессии.
 *
 * Старый/текущий протокол намеренно допускает два basis в одном журнале:
 * до TIME_SYNC firmware пишет uptime, после TIME_SYNC — Unix UTC. Поэтому UTC
 * нельзя использовать как uptime и тем более прибавлять к boot epoch второй раз.
 */
internal fun journalRecordUptimeSeconds(record: EventRecord, bootEpochSeconds: Long?): Long? {
    if (!journalTimestampIsUtc(record.timestampSeconds)) return record.timestampSeconds.coerceAtLeast(0L)
    val epoch = bootEpochSeconds ?: return null
    val delta = record.timestampSeconds - epoch
    /* Секундное округление телефона/STATE_REPORT может дать -1..-2 с у самого boot. */
    return if (delta >= -2L) delta.coerceAtLeast(0L) else null
}

internal fun journalBootFirstSequences(records: List<EventRecord>): List<Long> {
    if (records.isEmpty()) return emptyList()
    val chronological = records.sortedBy { it.sequence }
    val starts = ArrayList<Long>()
    var previous: EventRecord? = null
    for (record in chronological) {
        val prev = previous
        val reboot = record.type == 1 || when {
            prev == null -> false
            /* До синхронизации uptime -> UTC — это смена basis, не reboot. */
            !journalTimestampIsUtc(prev.timestampSeconds) && journalTimestampIsUtc(record.timestampSeconds) -> false
            /* Обратный переход внутри одного boot невозможен: после UTC firmware
             * уже не возвращается к uptime. Значит это новый boot, даже если EVT_BOOT
             * уже выпал из кольцевого журнала. */
            journalTimestampIsUtc(prev.timestampSeconds) && !journalTimestampIsUtc(record.timestampSeconds) -> true
            /* Для чистого uptime оставляем старый fallback по падению счётчика. */
            !journalTimestampIsUtc(prev.timestampSeconds) && !journalTimestampIsUtc(record.timestampSeconds) ->
                record.timestampSeconds + 1L < prev.timestampSeconds
            /* UTC может корректироваться повторным TIME_SYNC; его уменьшение само по
             * себе не является доказательством reboot. */
            else -> false
        }
        if (starts.isEmpty() || reboot) starts += record.sequence
        previous = record
    }
    return starts
}

internal fun journalBootFirstSequence(sequence: Long, bootStarts: List<Long>): Long {
    var found = bootStarts.firstOrNull() ?: sequence
    for (start in bootStarts) {
        if (start <= sequence) found = start else break
    }
    return found
}

internal fun journalWallSeconds(
    record: EventRecord,
    currentBootFirst: Long?,
    currentBootEpoch: Long?,
    anchors: List<JournalTimeAnchor>,
): Long? {
    /* После TIME_SYNC timestamp уже Unix UTC. Это и был баг 2083 года: раньше
     * сюда ещё раз прибавлялся boot epoch (~2026 + ~2026). */
    if (journalTimestampIsUtc(record.timestampSeconds)) return record.timestampSeconds

    if (currentBootFirst != null && currentBootEpoch != null && record.sequence >= currentBootFirst) {
        return currentBootEpoch + record.timestampSeconds
    }
    val anchor = anchors
        .filter { record.sequence >= it.bootFirstSequence && record.sequence <= it.lastSequence }
        .maxByOrNull { it.bootFirstSequence }
        ?: return null
    return anchor.bootEpochSeconds + record.timestampSeconds
}

internal fun mergeJournalTimeAnchor(
    anchors: List<JournalTimeAnchor>,
    incoming: JournalTimeAnchor,
    maxAnchors: Int = 16,
): List<JournalTimeAnchor> {
    val existing = anchors.find { it.bootFirstSequence == incoming.bootFirstSequence }
    val merged = if (existing == null) {
        incoming
    } else {
        incoming.copy(lastSequence = maxOf(existing.lastSequence, incoming.lastSequence))
    }
    return (anchors.filter { it.bootFirstSequence != incoming.bootFirstSequence } + merged)
        .sortedByDescending { it.bootFirstSequence }
        .take(maxAnchors)
}

internal fun encodeJournalTimeAnchors(anchors: List<JournalTimeAnchor>): String =
    anchors.joinToString(";") { "${it.bootFirstSequence}:${it.lastSequence}:${it.bootEpochSeconds}" }

internal fun decodeJournalTimeAnchors(raw: String?): List<JournalTimeAnchor> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(';').mapNotNull { part ->
        val bits = part.split(':')
        if (bits.size != 3) return@mapNotNull null
        val first = bits[0].toLongOrNull() ?: return@mapNotNull null
        val last = bits[1].toLongOrNull() ?: return@mapNotNull null
        val epoch = bits[2].toLongOrNull() ?: return@mapNotNull null
        if (last < first) return@mapNotNull null
        JournalTimeAnchor(first, epoch, last)
    }
}

internal data class JournalBootSession(
    val firstSequence: Long,
    val lastSequence: Long,
    val lastUptimeSeconds: Long,
    val epochSeconds: Long?,
) {
    val durationSeconds: Long get() = lastUptimeSeconds
}

internal fun journalBootSessions(
    records: List<EventRecord>,
    currentBootFirst: Long?,
    currentBootEpoch: Long?,
    anchors: List<JournalTimeAnchor>,
): List<JournalBootSession> {
    if (records.isEmpty()) return emptyList()
    val starts = journalBootFirstSequences(records)
    val chronological = records.sortedBy { it.sequence }
    return starts.mapIndexed { index, first ->
        val next = starts.getOrNull(index + 1)
        val inBoot = chronological.filter { record ->
            record.sequence >= first && (next == null || record.sequence < next)
        }
        val last = inBoot.last()
        val epoch = when {
            currentBootEpoch != null && first == currentBootFirst -> currentBootEpoch
            else -> anchors.find { it.bootFirstSequence == first }?.bootEpochSeconds
        }
        /* Не берём max(raw timestamp): после TIME_SYNC это Unix epoch и он превращал
         * продолжительность одной boot-сессии в десятки лет. */
        val lastUptime = inBoot
            .mapNotNull { journalRecordUptimeSeconds(it, epoch) }
            .maxOrNull()
            ?: 0L
        JournalBootSession(
            firstSequence = first,
            lastSequence = last.sequence,
            lastUptimeSeconds = lastUptime,
            epochSeconds = epoch,
        )
    }
}

internal fun journalSessionFor(sequence: Long, sessions: List<JournalBootSession>): JournalBootSession? =
    sessions.find { sequence >= it.firstSequence && sequence <= it.lastSequence }

internal fun journalDowntimeSeconds(older: JournalBootSession, newer: JournalBootSession): Long? {
    val olderEpoch = older.epochSeconds ?: return null
    val newerEpoch = newer.epochSeconds ?: return null
    return (newerEpoch - olderEpoch - older.lastUptimeSeconds).coerceAtLeast(0L)
}

internal fun journalSessionGaps(sessions: List<JournalBootSession>): List<Long> {
    if (sessions.size < 2) return emptyList()
    val gaps = MutableList(sessions.size - 1) { 1L }
    var known = 0
    while (known < sessions.size) {
        if (sessions[known].epochSeconds == null) {
            known += 1
            continue
        }
        var nextKnown = known + 1
        while (nextKnown < sessions.size && sessions[nextKnown].epochSeconds == null) nextKnown += 1
        if (nextKnown >= sessions.size) break
        val older = sessions[known]
        val newer = sessions[nextKnown]
        val olderEpoch = older.epochSeconds ?: break
        val newerEpoch = newer.epochSeconds ?: break
        var unsyncedRun = 0L
        for (index in (known + 1) until nextKnown) {
            unsyncedRun += sessions[index].durationSeconds
        }
        val slack = (newerEpoch - olderEpoch - older.lastUptimeSeconds - unsyncedRun).coerceAtLeast(0L)
        val unknownGaps = nextKnown - known
        val each = slack / unknownGaps
        val remainder = slack % unknownGaps
        for (offset in 0 until unknownGaps) {
            gaps[known + offset] = each + if (offset.toLong() < remainder) 1L else 0L
        }
        known = nextKnown
    }
    return gaps
}

internal fun journalBootEpochRange(
    session: JournalBootSession,
    sessions: List<JournalBootSession>,
): LongRange? {
    val known = session.epochSeconds
    if (known != null) return known..known
    val index = sessions.indexOfFirst { it.firstSequence == session.firstSequence }
    if (index < 0) return null
    val prevKnown = (index - 1 downTo 0).firstOrNull { sessions[it].epochSeconds != null } ?: return null
    val nextKnown = ((index + 1) until sessions.size).firstOrNull { sessions[it].epochSeconds != null } ?: return null
    val prev = sessions[prevKnown]
    val next = sessions[nextKnown]
    val prevEpoch = prev.epochSeconds ?: return null
    val nextEpoch = next.epochSeconds ?: return null
    var earliest = prevEpoch + prev.lastUptimeSeconds
    for (step in (prevKnown + 1) until index) {
        earliest += sessions[step].durationSeconds
    }
    var latest = nextEpoch
    for (step in (index + 1) until nextKnown) {
        latest -= sessions[step].durationSeconds
    }
    latest -= session.durationSeconds
    if (latest < earliest) latest = earliest
    return earliest..latest
}

internal fun journalEventWallRange(record: EventRecord, sessions: List<JournalBootSession>): LongRange? {
    if (journalTimestampIsUtc(record.timestampSeconds)) {
        return record.timestampSeconds..record.timestampSeconds
    }
    val session = journalSessionFor(record.sequence, sessions) ?: return null
    val bootRange = journalBootEpochRange(session, sessions) ?: return null
    return (bootRange.first + record.timestampSeconds)..(bootRange.last + record.timestampSeconds)
}

internal fun journalEventTimeCaption(
    record: EventRecord,
    records: List<EventRecord>,
    currentBootFirst: Long?,
    currentBootEpoch: Long?,
    anchors: List<JournalTimeAnchor>,
    formatWall: (Long) -> String,
): String {
    val wall = journalWallSeconds(record, currentBootFirst, currentBootEpoch, anchors)
    if (wall != null) return formatWall(wall)
    val sessions = journalBootSessions(records, currentBootFirst, currentBootEpoch, anchors)
    val range = journalEventWallRange(record, sessions)
    if (range != null) {
        if (range.last - range.first <= 2L) return formatWall(range.first)
        return "между ${formatWall(range.first)} и ${formatWall(range.last)}"
    }
    return "без синхронизации, ${journalUptimeCaption(record.timestampSeconds)}"
}

internal fun journalDurationCaption(seconds: Long): String {
    val value = seconds.coerceAtLeast(0L)
    val hours = value / 3_600L
    val minutes = (value % 3_600L) / 60L
    val secs = value % 60L
    return when {
        hours > 0L && minutes > 0L -> "$hours ч $minutes мин"
        hours > 0L -> "$hours ч"
        minutes > 0L && secs > 0L -> "$minutes мин $secs с"
        minutes > 0L -> "$minutes мин"
        else -> "$secs с"
    }
}

internal fun journalUptimeCaption(seconds: Long): String = "${journalDurationCaption(seconds)} от включения"
