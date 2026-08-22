#ifndef DPLS_PHY6252_RUNTIME_H
#define DPLS_PHY6252_RUNTIME_H

#include "bcomdef.h"
#include "types.h"

typedef enum {
    DPLS_LINK_PROFILE_ACTIVE = 0,
    DPLS_LINK_PROFILE_IDLE
} dpls_link_profile_t;

void dpls_phy6252_runtime_init(uint8 task_id);
void dpls_phy6252_runtime_connected(uint16 conn_handle);
void dpls_phy6252_runtime_disconnected(void);
void dpls_phy6252_runtime_process_rx(void);
void dpls_phy6252_runtime_process_adc(void);
void dpls_phy6252_runtime_process_tx(void);
void dpls_phy6252_runtime_process_storage(void);
void dpls_phy6252_runtime_tx_confirmed(void);
/* One application timer owns physical sampling, LED edges and every semantic
 * deadline. The shell asks runtime when it next needs to wake. */
void dpls_phy6252_runtime_process_timer(void);
uint32 dpls_phy6252_runtime_next_wakeup_ms(void);

/* ACTIVE while pairing/authenticating or a dangerous mode is energized; IDLE
 * only for authenticated NORMAL. The target maps this semantic fact to BLE
 * connection parameters without duplicating domain state. */
dpls_link_profile_t dpls_phy6252_runtime_link_profile(void);

/* Target shell никогда не хранит shadow link state: спрашивает runtime/transport. */
bool dpls_phy6252_runtime_link_active(void);

/* Пока true, advertising не включаем: persistence ещё не закончена. */
bool dpls_phy6252_runtime_flash_pending(void);

#endif
