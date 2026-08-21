#ifndef DPLS_PHY6252_OUTPUTS_H
#define DPLS_PHY6252_OUTPUTS_H

#include "dpls_server.h"
#include "types.h"

void dpls_phy6252_outputs_init(void);
bool dpls_phy6252_outputs_apply_mode(void *context, dpls_mode_t mode);
void dpls_phy6252_outputs_safe_normal(void *context);
void dpls_phy6252_outputs_identify(void *context, bool enabled);
uint32 dpls_phy6252_outputs_led_tick(uint32 now_ms, dpls_mode_t mode,
                                     bool reserve, bool auto_isolation);

/* Physical commissioning reset input belongs to the board-I/O adapter. */
bool dpls_phy6252_outputs_factory_reset_active(void);
void dpls_phy6252_outputs_factory_reset_latched(void);

#endif
