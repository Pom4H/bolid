#ifndef DPLS_PHY6252_APP_H
#define DPLS_PHY6252_APP_H

#include "bcomdef.h"

#define DPLS_PHY6252_RX_EVT 0x0040

void dpls_phy6252_init(uint8 task_id);
void dpls_phy6252_connected(uint16 conn_handle);
void dpls_phy6252_disconnected(void);
void dpls_phy6252_process_rx(void);
void dpls_phy6252_tick(void);
/* Render one LED step and return the milliseconds until the next call. */
uint32 dpls_phy6252_led_tick(void);

#endif
