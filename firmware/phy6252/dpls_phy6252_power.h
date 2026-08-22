#ifndef DPLS_PHY6252_POWER_H
#define DPLS_PHY6252_POWER_H

#include "types.h"

/* One module owns every pwrmgr lock/unlock. This makes a power constraint a
 * resource fact instead of an incidental side effect scattered across drivers.
 *
 * Connected sleep is enabled for the RC9 low-power candidate that will be
 * measured on PB-03F. Define DPLS_CONNECTED_SLEEP=0 to restore the historical
 * link-wide MOD_USR0 guard for an A/B reliability/current comparison. */
#ifndef DPLS_CONNECTED_SLEEP
#define DPLS_CONNECTED_SLEEP 1
#endif

#ifndef DPLS_DEBUG_UART_ROM
#define DPLS_DEBUG_UART_ROM 0
#endif

typedef enum {
    DPLS_POWER_LINK = 0,
    DPLS_POWER_OUTPUT,
    DPLS_POWER_ADC,
#if DPLS_DEBUG_UART_ROM
    DPLS_POWER_DEBUG_UART,
#endif
    DPLS_POWER_REASON_COUNT
} dpls_power_reason_t;

typedef struct {
    uint32 acquire_count[DPLS_POWER_REASON_COUNT];
    uint32 held_ms[DPLS_POWER_REASON_COUNT];
    uint32 held_since_ms[DPLS_POWER_REASON_COUNT];
    uint8 held_mask;
} dpls_power_diag_t;

void dpls_phy6252_power_init(void);
bool dpls_phy6252_power_acquire(dpls_power_reason_t reason);
bool dpls_phy6252_power_release(dpls_power_reason_t reason);
void dpls_phy6252_power_link_connected(void);
void dpls_phy6252_power_link_disconnected(void);
bool dpls_phy6252_power_connected_sleep_enabled(void);
void dpls_phy6252_power_snapshot(dpls_power_diag_t *out);

#endif
