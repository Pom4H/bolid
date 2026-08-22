from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text()


def write(path: str, text: str) -> None:
    Path(path).write_text(text)


def one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)


# Firmware: journal timestamp is UTC or zero, never uptime.
p = "firmware/src/dpls_server.c"
s = read(p)
s = one(
    s,
    "return s->clock.valid ? s->clock.unix_seconds : s->now_ms / 1000u;",
    "return s->clock.valid ? s->clock.unix_seconds : 0u;",
    "firmware timestamp",
)
write(p, s)

p = "firmware/include/dpls_server.h"
s = read(p)
s = one(
    s,
    "    uint32_t timestamp_seconds;\n",
    "    /* 0 = calendar time unknown; otherwise Unix UTC seconds. */\n    uint32_t timestamp_seconds;\n",
    "event timestamp comment",
)
write(p, s)

p = "firmware/tests/test_server_v2.c"
s = read(p)
s = one(
    s,
    "    unsigned apply_count;\n\n    init_server(&fake, &server, true);",
    "    unsigned apply_count;\n    uint16_t i;\n\n    init_server(&fake, &server, true);",
    "firmware test local",
)
s = one(
    s,
    "    authenticate(&server, &fake, buf);\n\n    auth_payload(&server, payload);",
    "    authenticate(&server, &fake, buf);\n    assert(fake.event_count >= 3u);\n    for (i = 0u; i < fake.event_count; ++i) {\n        assert(fake.events[i].timestamp_seconds == 0u);\n    }\n\n    auth_payload(&server, payload);",
    "pre-sync assertion",
)
s = one(
    s,
    "    assert(r.type == DPLS_MSG_TIME_SYNC && r.sequence == 30u && r.payload_length == 0u);\n    assert(server.clock.valid);",
    "    assert(r.type == DPLS_MSG_TIME_SYNC && r.sequence == 30u && r.payload_length == 0u);\n    assert(server.clock.valid);\n    dpls_server_log(&server, 12u, 0u);\n    assert(fake.events[fake.event_count - 1u].timestamp_seconds == 0x6a7c7780u);",
    "post-sync assertion",
)
write(p, s)

# Domain: remove boot-epoch/time-anchor reconstruction state.
p = "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/domain/DeviceModel.kt"
s = read(p)
event_start = s.index("data class EventRecord(")
anchor_start = s.index("data class JournalTimeAnchor(", event_start)
anchor_end = s.index("\n\n", anchor_start)
event_block = """data class EventRecord(
    val sequence: Long,
    /** 0 means that UTC was unknown when firmware wrote the event. */
    val timestampSeconds: Long,
    val type: Int,
    val parameter: Int,
)"""
s = s[:event_start] + event_block + s[anchor_end:]
write(p, s)

p = "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/domain/AppState.kt"
s = read(p)
s = one(s, "    val deviceBootEpochSeconds: Long? = null,\n", "", "boot epoch state")
s = one(s, "    val journalTimeAnchors: List<JournalTimeAnchor> = emptyList(),\n", "", "anchor state")
write(p, s)

p = "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsController.kt"
s = read(p)
s = one(s, "    fun formatEventInstant(uptimeSeconds: Long): String\n", "", "obsolete formatter")
write(p, s)

write(
    "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/EventTime.kt",
    '''package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.protocol.DplsProtocol

internal fun eventTimestampBasis(timestampSeconds: Long): String =
    if (timestampSeconds in DplsProtocol.TIME_MIN_UNIX_SECONDS..DplsProtocol.TIME_MAX_UNIX_SECONDS) "utc" else "unknown"

internal fun eventTimestampText(timestampSeconds: Long): String =
    if (eventTimestampBasis(timestampSeconds) == "utc") formatUnixUtc(timestampSeconds)
    else "Время не установлено"

private fun formatUnixUtc(seconds: Long): String {
    val days = seconds / 86_400L
    val secondOfDay = seconds % 86_400L
    val z = days + 719_468L
    val era = z / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    var year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    if (month <= 2L) year += 1L
    val hour = secondOfDay / 3_600L
    val minute = (secondOfDay % 3_600L) / 60L
    val second = secondOfDay % 60L
    fun two(value: Long): String = value.toString().padStart(2, '0')
    return "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)} ${two(hour)}:${two(minute)}:${two(second)} UTC"
}
''',
)

p = "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
s = read(p)
s = one(s, "import ru.bolid.testdpls.core.domain.JournalTimeAnchor\n", "", "anchor import")
s = one(s, "    private var timeAnchors: List<JournalTimeAnchor> = emptyList()\n", "", "anchor property")
formatter_start = s.index("    override fun formatEventTime(record: EventRecord): String =")
formatter_end = s.index("\n\n    fun close()", formatter_start)
formatter = '''    override fun formatEventTime(record: EventRecord): String =
        if (eventTimestampBasis(record.timestampSeconds) == "utc") {
            platform.formatLocalDateTime(record.timestampSeconds)
        } else {
            "Время не установлено"
        }'''
s = s[:formatter_start] + formatter + s[formatter_end:]
s = one(s, "        loadTimeAnchors()\n        persistTimeAnchors()\n", "", "anchor load")
s = one(s, "                deviceBootEpochSeconds = now / 1000 - device.uptimeSeconds,\n", "", "boot epoch projection")
s = one(s, "        if (snap.records.isNotEmpty()) rememberCurrentBootAnchor(snap.records)\n", "", "remember anchor")
s = one(s, "                journalTimeAnchors = timeAnchors,\n", "", "anchor projection")
helpers_start = s.index("    private fun storageKeys(): List<String> =")
helpers_end = s.index("    private fun retainedUiState(", helpers_start)
s = s[:helpers_start] + s[helpers_end:]
write(p, s)

# Timeline remains useful for navigation but never invents calendar time.
p = "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsLogHistogram.kt"
s = read(p)
timeline_start = s.index("internal fun buildJournalTimeline(")
timeline_end = s.index("internal data class JournalStrip(", timeline_start)
timeline = '''internal fun buildJournalTimeline(records: List<EventRecord>): JournalTimeline {
    if (records.isEmpty()) return JournalTimeline(emptyList())
    val timeline = LongArray(records.size)
    val order = records.indices.sortedBy { records[it].sequence }
    var cursor = 0L
    var previousUtc: Long? = null
    var hasPrevious = false
    for (index in order) {
        val timestamp = records[index].timestampSeconds
        val utc = timestamp.takeIf { eventTimestampBasis(it) == "utc" }
        if (hasPrevious) {
            cursor += if (utc != null && previousUtc != null) {
                (utc - previousUtc).coerceAtLeast(1L)
            } else {
                1L
            }
        }
        timeline[index] = cursor
        previousUtc = utc
        hasPrevious = true
    }
    return JournalTimeline(timeline.toList())
}

'''
s = s[:timeline_start] + timeline + s[timeline_end:]
hist1 = s.index("internal fun buildLogHistogram(")
hist2 = s.index("internal fun buildLogHistogram(", hist1 + 1)
hist_overload = '''internal fun buildLogHistogram(
    records: List<EventRecord>,
    targetBars: Int = 32,
    bucketSeconds: Long? = null,
): LogTimeHistogram? {
    val dated = records.filter { eventTimestampBasis(it.timestampSeconds) == "utc" }
    if (dated.isEmpty()) return null
    return buildLogHistogram(
        dated,
        dated.minOf { it.timestampSeconds },
        dated.maxOf { it.timestampSeconds },
        targetBars,
        bucketSeconds,
    )
}

'''
s = s[:hist1] + hist_overload + s[hist2:]
write(p, s)

# UI: explicit BOOT event is the only boot divider; no inferred downtime.
p = "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsApp.kt"
s = read(p)
log_page = s.index("private fun LogPage(")
records_start = s.index("    val records = state.eventLog", log_page)
bucket_start = s.index("    var bucketOverride", records_start)
s = s[:records_start] + "    val records = state.eventLog\n    val timeline = remember(records) { buildJournalTimeline(records) }\n" + s[bucket_start:]
items = s.index("                itemsIndexed(records, key = { _, e -> e.sequence }) { index, e ->", log_page)
boot_if = s.index("                    if (index > 0) {", items)
card = s.index("                    EventLogCard(", boot_if)
s = s[:boot_if] + "                    if (index > 0 && e.type == 1) {\n                        JournalBootDivider(colors)\n                    }\n" + s[card:]
divider_start = s.index("@Composable\nprivate fun JournalBootDivider(")
divider_end = s.index("@Composable\nprivate fun JournalListScrollbar(", divider_start)
divider = '''@Composable
private fun JournalBootDivider(colors: BolidColors) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.line))
        Text("включение устройства", color = colors.muted, fontSize = 11.sp, maxLines = 1)
        Box(Modifier.weight(1f).height(1.dp).background(colors.line))
    }
}

'''
s = s[:divider_start] + divider + s[divider_end:]
write(p, s)

for path in (
    "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsJournalTime.kt",
    "mobile/core/src/commonTest/kotlin/ru/bolid/testdpls/core/app/DplsJournalTimeTest.kt",
):
    Path(path).unlink()

write(
    "mobile/core/src/commonTest/kotlin/ru/bolid/testdpls/core/app/EventTimeTest.kt",
    '''package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertEquals

class EventTimeTest {
    @Test
    fun syncedTimestampFormatsAsUtc() {
        assertEquals("utc", eventTimestampBasis(1_577_836_800L))
        assertEquals("2020-01-01 00:00:00 UTC", eventTimestampText(1_577_836_800L))
    }

    @Test
    fun missingAndLegacyRelativeTimestampsAreUnknown() {
        assertEquals("unknown", eventTimestampBasis(0L))
        assertEquals("Время не установлено", eventTimestampText(0L))
        assertEquals("unknown", eventTimestampBasis(3_661L))
        assertEquals("Время не установлено", eventTimestampText(3_661L))
    }
}
''',
)

write(
    "mobile/core/src/commonTest/kotlin/ru/bolid/testdpls/core/app/DplsLogHistogramTest.kt",
    '''package ru.bolid.testdpls.core.app

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
''',
)

p = "mobile/interop/src/jvmTest/kotlin/ru/bolid/testdpls/interop/SoftBleBridgeTest.kt"
s = read(p)
test_start = s.index("        val first = dpls.uiState.value", s.index("fun journalPostSyncDatesAndIncrementalRefreshAreEndToEnd"))
head = s.index("        val previousHead =", test_start)
checks = '''        val first = dpls.uiState.value
        val unknown = first.eventLog.firstOrNull { event -> event.timestampSeconds == 0L }
            ?: fail("journal contains no pre-TIME_SYNC event: ${first.eventLog}")
        assertEquals("Время не установлено", dpls.formatEventTime(unknown))
        assertTrue(
            first.eventLog.all { event ->
                event.timestampSeconds == 0L ||
                    event.timestampSeconds in DplsProtocol.TIME_MIN_UNIX_SECONDS..DplsProtocol.TIME_MAX_UNIX_SECONDS
            },
            "journal must contain only UTC or zero: ${first.eventLog}",
        )
        val synced = first.eventLog.firstOrNull { event ->
            event.timestampSeconds in DplsProtocol.TIME_MIN_UNIX_SECONDS..DplsProtocol.TIME_MAX_UNIX_SECONDS
        } ?: fail("journal contains no post-TIME_SYNC event: ${first.eventLog}")
        val caption = dpls.formatEventTime(synced)
        assertEquals(services.formatLocalDateTime(synced.timestampSeconds), caption)
        assertFalse(caption.contains("2083"), "post-sync timestamp was transformed twice: $caption")

'''
s = s[:test_start] + checks + s[head:]
write(p, s)

write(
    "docs/timekeeping.md",
    '''# Время и отметки событий

Test-DPLS разделяет монотонное время для safety/deadlines и календарное UTC для журнала.

## Монотонное время

`osal_GetSystemClock()` используется только для внутренних интервалов: таймаутов опасного режима, сессии, identify, factory reset и BLE TX. `TIME_SYNC` на эти интервалы не влияет.

## UTC для журнала

После успешной аутентификации мобильный клиент отправляет `TIME_SYNC (0x0B)` с Unix UTC. Firmware принимает диапазон 2020-01-01…2099-12-31. UTC anchor живёт только в RAM и продвигается монотонным счётчиком.

У `timestamp_seconds` ровно один смысл:

- `0` — календарное время события неизвестно;
- ненулевое допустимое значение — Unix UTC.

До первого `TIME_SYNC` после boot firmware пишет `0`, а не uptime. После `TIME_SYNC` новые события получают Unix UTC. После полного снятия питания UTC снова неизвестен до следующего подключения телефона.

Приложение не реконструирует календарные даты из uptime, boot epoch или соседних записей. Старые записи предыдущих RC с относительными timestamp отображаются как `Время не установлено`.

Порядок событий определяется `sequence`. CSV export использует `time_basis=utc` или `time_basis=unknown`.
''',
)

for token in (
    "JournalTimeAnchor",
    "deviceBootEpochSeconds",
    "journalTimeAnchors",
    "journalBootSessions",
    "journalSessionFor",
    "journalDowntimeSeconds",
    "journalWallSeconds",
    "formatEventInstant",
):
    hits = []
    for root in (Path("mobile/core/src/commonMain"), Path("mobile/core/src/commonTest")):
        for file in root.rglob("*.kt"):
            if token in file.read_text():
                hits.append(str(file))
    if hits:
        raise SystemExit(f"obsolete journal time symbol {token}: {hits}")
