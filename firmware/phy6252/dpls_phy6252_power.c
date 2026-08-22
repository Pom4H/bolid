#include "dpls_phy6252_power.h"

#include "OSAL.h"
#include "error.h"
#include "pwrmgr.h"
#include <string.h>

static dpls_power_diag_t diag;

static uint8 module_for(dpls_power_reason_t reason)
{
    switch (reason) {
    case DPLS_POWER_LINK: return MOD_USR0;
    case DPLS_POWER_OUTPUT: return MOD_USR1;
    case DPLS_POWER_ADC: return MOD_USR2;
    default: return MOD_USR0;
    }
}

static uint8 bit_for(dpls_power_reason_t reason)
{
    return (uint8)(1u << (uint8)reason);
}

static uint32 now_ms(void)
{
    return (uint32)osal_GetSystemClock();
}

void dpls_phy6252_power_init(void)
{
    memset(&diag, 0, sizeof(diag));
    (void)hal_pwrmgr_register(MOD_USR0, NULL, NULL);
    (void)hal_pwrmgr_register(MOD_USR1, NULL, NULL);
    (void)hal_pwrmgr_register(MOD_USR2, NULL, NULL);
}

bool dpls_phy6252_power_acquire(dpls_power_reason_t reason)
{
    uint8 bit;
    if ((unsigned)reason >= DPLS_POWER_REASON_COUNT) return false;
    bit = bit_for(reason);
    if (diag.held_mask & bit) return true;
    if (hal_pwrmgr_lock(module_for(reason)) != PPlus_SUCCESS) return false;
    diag.held_mask |= bit;
    diag.held_since_ms[reason] = now_ms();
    ++diag.acquire_count[reason];
    return true;
}

bool dpls_phy6252_power_release(dpls_power_reason_t reason)
{
    uint8 bit;
    uint32 now;
    if ((unsigned)reason >= DPLS_POWER_REASON_COUNT) return false;
    bit = bit_for(reason);
    if ((diag.held_mask & bit) == 0u) return true;
    if (hal_pwrmgr_unlock(module_for(reason)) != PPlus_SUCCESS) return false;
    now = now_ms();
    diag.held_ms[reason] += now - diag.held_since_ms[reason];
    diag.held_since_ms[reason] = 0u;
    diag.held_mask &= (uint8)~bit;
    return true;
}

void dpls_phy6252_power_link_connected(void)
{
#if DPLS_CONNECTED_SLEEP
    /* BLE controller/OSAL owns radio wakeups. ADC and dangerous outputs still
     * hold their own short constraints. Monday hardware measurements compare
     * this candidate against DPLS_CONNECTED_SLEEP=0. */
#else
    (void)dpls_phy6252_power_acquire(DPLS_POWER_LINK);
#endif
}

void dpls_phy6252_power_link_disconnected(void)
{
    (void)dpls_phy6252_power_release(DPLS_POWER_LINK);
}

bool dpls_phy6252_power_connected_sleep_enabled(void)
{
#if DPLS_CONNECTED_SLEEP
    return true;
#else
    return false;
#endif
}

void dpls_phy6252_power_snapshot(dpls_power_diag_t *out)
{
    uint8 i;
    uint32 now;
    if (!out) return;
    *out = diag;
    now = now_ms();
    for (i = 0u; i < DPLS_POWER_REASON_COUNT; ++i) {
        if (diag.held_mask & (uint8)(1u << i))
            out->held_ms[i] += now - diag.held_since_ms[i];
    }
}
