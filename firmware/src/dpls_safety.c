#include "dpls_safety.h"

static bool elapsed(uint32_t now, uint32_t deadline)
{
    return (int32_t)(now - deadline) >= 0;
}

void dpls_safety_init(dpls_safety_t *s)
{
    s->mode = DPLS_SAFE_NORMAL;
    s->mode_deadline_ms = 0u;
    s->revision = 1u;
}

static dpls_safety_return_t physical_failure(const dpls_safety_inputs_t *in)
{
    if (!in->connected) return DPLS_SAFETY_RETURN_DISCONNECT;
    if (!in->measurements_ready) return DPLS_SAFETY_RETURN_MEASUREMENT_LOST;
    if (in->real_short) return DPLS_SAFETY_RETURN_REAL_SHORT;
    if (in->reserve_low) return DPLS_SAFETY_RETURN_LOW_RESERVE;
    return DPLS_SAFETY_RETURN_NONE;
}

static bool session_expired(const dpls_safety_inputs_t *in, uint32_t now_ms)
{
    return !in->authenticated ||
           elapsed(now_ms, in->last_authenticated_activity_ms + DPLS_SAFETY_SESSION_TIMEOUT_MS);
}

dpls_safety_return_t dpls_safety_admission_reason(
    dpls_safety_mode_t mode,
    const dpls_safety_inputs_t *in,
    uint32_t now_ms)
{
    dpls_safety_return_t failure;

    /* NORMAL — безусловное безопасное состояние: никакой вход не может
     * помешать оператору или автоматике вернуться в него. */
    if ((unsigned)mode > (unsigned)DPLS_SAFE_SHORT_T)
        return DPLS_SAFETY_RETURN_MEASUREMENT_LOST;
    if (mode == DPLS_SAFE_NORMAL) return DPLS_SAFETY_RETURN_NONE;

    failure = physical_failure(in);
    if (failure != DPLS_SAFETY_RETURN_NONE) return failure;
    if (session_expired(in, now_ms)) return DPLS_SAFETY_RETURN_SESSION_TIMEOUT;
    return DPLS_SAFETY_RETURN_NONE;
}

void dpls_safety_commit_mode(dpls_safety_t *s, dpls_safety_mode_t mode, uint32_t now_ms)
{
    if (s->mode != mode) ++s->revision;
    s->mode = mode;
    s->mode_deadline_ms = mode == DPLS_SAFE_NORMAL ? 0u : now_ms + DPLS_SAFETY_MODE_MAX_MS;
}

void dpls_safety_force_normal(dpls_safety_t *s)
{
    if (s->mode != DPLS_SAFE_NORMAL) ++s->revision;
    s->mode = DPLS_SAFE_NORMAL;
    s->mode_deadline_ms = 0u;
}

dpls_safety_return_t dpls_safety_required_return(
    const dpls_safety_t *s,
    const dpls_safety_inputs_t *in,
    uint32_t now_ms)
{
    dpls_safety_return_t failure;

    if (s->mode == DPLS_SAFE_NORMAL) return DPLS_SAFETY_RETURN_NONE;

    /* Сначала реальные аппаратные причины. Затем абсолютный deadline режима.
     * Session timeout идёт последним: при совпадении двух таймаутов причина
     * возврата остаётся детерминированной — истёк именно лимит опасного режима. */
    failure = physical_failure(in);
    if (failure != DPLS_SAFETY_RETURN_NONE) return failure;
    if (s->mode_deadline_ms && elapsed(now_ms, s->mode_deadline_ms))
        return DPLS_SAFETY_RETURN_MODE_TIMEOUT;
    if (session_expired(in, now_ms)) return DPLS_SAFETY_RETURN_SESSION_TIMEOUT;
    return DPLS_SAFETY_RETURN_NONE;
}

uint16_t dpls_safety_remaining_seconds(const dpls_safety_t *s, uint32_t now_ms)
{
    uint32_t left;
    if (s->mode == DPLS_SAFE_NORMAL || s->mode_deadline_ms == 0u || elapsed(now_ms, s->mode_deadline_ms))
        return 0u;
    left = s->mode_deadline_ms - now_ms;
    return (uint16_t)((left + 999u) / 1000u);
}
