package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.domain.EventRecord
import ru.bolid.testdpls.core.protocol.parseLogChunk
import ru.bolid.testdpls.core.protocol.readU16
import ru.bolid.testdpls.core.protocol.readU32

internal class JournalMachine(
    private val pageSize: Int = 15,
    private val maxEvents: Int = 200,
) {
    sealed interface Effect {
        data class Ack(val index: Int) : Effect
        data object Pause : Effect
        data object Complete : Effect
        data class Error(val message: String) : Effect
        data object None : Effect
    }

    data class Snapshot(
        val records: List<EventRecord>,
        val total: Int,
        val hasMore: Boolean,
        val progress: Float?,
    )

    private val records = mutableMapOf<Int, EventRecord>()
    private var expected = 0
    private var from = 0
    private var until = 0
    private var incremental = false
    private var knownCount = 0
    private var knownMaxSequence = 0L
    private var active = false

    val isActive: Boolean get() = active
    val isEmpty: Boolean get() = records.isEmpty()

    fun begin(incremental: Boolean): Unit {
        this.incremental = incremental && records.isNotEmpty()
        active = true
        if (this.incremental) {
            knownCount = expected
            knownMaxSequence = records.values.maxOfOrNull(EventRecord::sequence) ?: 0L
        } else {
            expected = 0
            from = 0
            until = 0
            knownCount = 0
            knownMaxSequence = 0L
            records.clear()
        }
    }

    fun info(payload: ByteArray): Effect {
        if (payload.size < 10) return Effect.Error("Повреждённый LOG_INFO")
        val count = minOf(readU16(payload, 8), readU32(payload, 4).toInt().coerceAtLeast(0) / 10, maxEvents)
        if (incremental && records.isNotEmpty() && count >= knownCount && count >= records.size) {
            expected = count
            from = if (count > knownCount) (knownCount - 1).coerceAtLeast(0) else (count - 1).coerceAtLeast(0)
            until = count
        } else {
            incremental = false
            expected = count
            records.clear()
            from = maxOf(0, expected - pageSize)
            until = expected
        }
        if (expected == 0 || from >= until) return finish()
        return Effect.Ack(nextMissing())
    }

    fun chunk(payload: ByteArray): Effect {
        val batch = parseLogChunk(payload) ?: return Effect.Error("Повреждённый LOG_CHUNK")
        batch.records.forEachIndexed { offset, record ->
            val index = batch.firstIndex + offset
            if (index !in 0 until expected) return@forEachIndexed
            val previous = records[index]
            if (incremental && previous != null && previous.sequence != record.sequence) return restartFresh()
            if (incremental && previous == null && index >= knownCount && knownMaxSequence > 0 && record.sequence <= knownMaxSequence) {
                return restartFresh()
            }
            if (previous == null) records[index] = record
        }
        if (records.size >= expected) return finish()
        val missing = nextMissing()
        if (missing < until) return Effect.Ack(missing)
        active = false
        return Effect.Pause
    }

    fun more(): Effect {
        val missing = (0 until expected).firstOrNull { it !in records } ?: return Effect.Complete
        from = missing
        until = minOf(expected, missing + pageSize)
        active = true
        return Effect.Ack(nextMissing())
    }

    fun finish(): Effect {
        active = false
        incremental = false
        expected = maxOf(expected, records.size)
        return Effect.Complete
    }

    fun fail() {
        active = false
        incremental = false
    }

    fun snapshot(inFlight: Boolean = active): Snapshot {
        val sorted = records.values.sortedByDescending(EventRecord::sequence)
        val hasMore = sorted.size < expected
        val progress = if (inFlight && expected > 0) {
            (sorted.size.toFloat() / expected.toFloat()).coerceIn(0.05f, 1f)
        } else null
        return Snapshot(sorted, expected, hasMore, progress)
    }

    fun firstTimestamp(): Long? = records[0]?.timestampSeconds
    fun lastTimestamp(): Long? = records[(expected - 1).coerceAtLeast(0)]?.timestampSeconds

    private fun nextMissing(): Int = (from until until).firstOrNull { it !in records } ?: until

    private fun restartFresh(): Effect {
        val count = expected
        incremental = false
        records.clear()
        from = maxOf(0, count - pageSize)
        until = count
        active = count > 0
        return if (active) Effect.Ack(nextMissing()) else Effect.Complete
    }
}
