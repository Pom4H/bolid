#ifndef DPLS_SAFETY_H
#define DPLS_SAFETY_H

#include <stdbool.h>
#include <stdint.h>

#define DPLS_SAFETY_MODE_MAX_MS 300000u
#define DPLS_SAFETY_SESSION_TIMEOUT_MS 10000u

typedef enum {
    DPLS_SAFE_NORMAL = 0,
    DPLS_SAFE_OPEN_T = 1,
    DPLS_SAFE_OPEN_MAIN = 2,
    DPLS_SAFE_SHORT_1 = 3,
    DPLS_SAFE_SHORT_2 = 4,
    DPLS_SAFE_SHORT_T = 5
} dpls_safety_mode_t;

typedef enum {
    DPLS_SAFETY_RETURN_NONE = 0,
    DPLS_SAFETY_RETURN_MODE_TIMEOUT,
    DPLS_SAFETY_RETURN_SESSION_TIMEOUT,
    DPLS_SAFETY_RETURN_LOW_RESERVE,
    DPLS_SAFETY_RETURN_DISCONNECT,
    DPLS_SAFETY_RETURN_REAL_SHORT,
    DPLS_SAFETY_RETURN_INTERNAL_ERROR
} dpls_safety_return_t;

typedef struct {
    dpls_safety_mode_t mode;
    uint32_t mode_deadline_ms;
    uint32_t revision;
} dpls_safety_t;

typedef struct {
    bool connected;
    bool authenticated;
    bool reserve_low;
    bool real_short;
    uint32_t last_authenticated_activity_ms;
} dpls_safety_inputs_t;

void dpls_safety_init(dpls_safety_t *s);
bool dpls_safety_can_enter(dpls_safety_mode_t mode, bool real_short);
void dpls_safety_applied(dpls_safety_t *s, dpls_safety_mode_t mode, uint32_t now_ms);
void dpls_safety_force_normal(dpls_safety_t *s);
dpls_safety_return_t dpls_safety_required_return(
    const dpls_safety_t *s,
    const dpls_safety_inputs_t *in,
    uint32_t now_ms
);
uint16_t dpls_safety_remaining_seconds(const dpls_safety_t *s, uint32_t now_ms);

#endif
