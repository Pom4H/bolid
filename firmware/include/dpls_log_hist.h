#ifndef DPLS_LOG_HIST_H
#define DPLS_LOG_HIST_H

#include <stdint.h>

#define DPLS_LOG_HIST_MAX_BUCKETS 48u
#define DPLS_LOG_HIST_DEFAULT_BARS 24u
#define DPLS_LOG_HIST_HEADER 23u

/* Keep in lockstep with mobile niceLogBucketSeconds(). */
static inline uint32_t dpls_log_hist_bucket_seconds(uint32_t span_seconds, uint8_t target_bars)
{
    static const uint32_t steps[] = {
        1u, 2u, 5u, 10u, 15u, 30u,
        60u, 120u, 300u, 600u, 900u, 1800u,
        3600u, 7200u, 10800u, 21600u, 43200u,
        86400u, 172800u, 604800u
    };
    uint32_t span = span_seconds < 1u ? 1u : span_seconds;
    uint8_t bars = target_bars;
    uint32_t raw;
    unsigned i;
    if (bars < 1u) bars = DPLS_LOG_HIST_DEFAULT_BARS;
    if (bars > DPLS_LOG_HIST_MAX_BUCKETS) bars = (uint8_t)DPLS_LOG_HIST_MAX_BUCKETS;
    raw = (span + (uint32_t)bars - 1u) / (uint32_t)bars;
    for (i = 0; i < (unsigned)(sizeof(steps) / sizeof(steps[0])); ++i) {
        if (steps[i] >= raw) return steps[i];
    }
    return ((raw + 86399u) / 86400u) * 86400u;
}

static inline uint8_t dpls_log_hist_bucket_count(uint32_t span_seconds, uint32_t bucket_seconds)
{
    uint32_t span = span_seconds < 1u ? 1u : span_seconds;
    uint32_t bucket = bucket_seconds < 1u ? 1u : bucket_seconds;
    uint32_t count = (span + bucket) / bucket;
    if (count < 1u) count = 1u;
    if (count > DPLS_LOG_HIST_MAX_BUCKETS) count = DPLS_LOG_HIST_MAX_BUCKETS;
    return (uint8_t)count;
}

static inline uint8_t dpls_log_hist_index(uint32_t timestamp, uint32_t first_ts,
                                          uint32_t bucket_seconds, uint8_t bucket_count)
{
    uint32_t delta;
    uint32_t idx;
    if (bucket_count == 0u || bucket_seconds == 0u) return 0;
    if (timestamp <= first_ts) return 0;
    delta = timestamp - first_ts;
    idx = delta / bucket_seconds;
    if (idx >= (uint32_t)bucket_count) idx = (uint32_t)bucket_count - 1u;
    return (uint8_t)idx;
}

#endif
