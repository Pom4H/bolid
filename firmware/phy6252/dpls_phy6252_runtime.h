#ifndef DPLS_PHY6252_RUNTIME_H
#define DPLS_PHY6252_RUNTIME_H

#include "bcomdef.h"

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

#endif
