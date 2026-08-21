#ifndef DPLS_PHY6252_RUNTIME_H
#define DPLS_PHY6252_RUNTIME_H

#include "bcomdef.h"
#include "types.h"

void dpls_phy6252_runtime_init(uint8 task_id);
void dpls_phy6252_runtime_connected(uint16 conn_handle);
void dpls_phy6252_runtime_disconnected(void);
void dpls_phy6252_runtime_process_rx(void);
void dpls_phy6252_runtime_process_adc(void);
void dpls_phy6252_runtime_process_tx(void);
void dpls_phy6252_runtime_process_storage(void);
void dpls_phy6252_runtime_tx_confirmed(void);
void dpls_phy6252_runtime_tick(void);
uint32 dpls_phy6252_runtime_led_tick(void);

/* Target shell никогда не хранит shadow link state: спрашивает runtime/transport. */
bool dpls_phy6252_runtime_link_active(void);

/* Пока true, advertising не включаем: persistence ещё не закончена. */
bool dpls_phy6252_runtime_flash_pending(void);

#endif
