#ifndef DPLS_PHY6252_APP_H
#define DPLS_PHY6252_APP_H

#include "bcomdef.h"

#define DPLS_PHY6252_RX_EVT 0x0040
#define DPLS_PHY6252_TX_EVT 0x0400
#define DPLS_PHY6252_ADC_EVT 0x0800

void dpls_phy6252_init(uint8 task_id);
void dpls_phy6252_connected(uint16 conn_handle);
void dpls_phy6252_disconnected(void);
void dpls_phy6252_process_rx(void);
/* Convert the raw ADC samples captured by the (minimal) ISR into calibrated
 * millivolts. Runs in the OSAL task — this is where the soft-float scaling and
 * window averaging live, off the interrupt path. */
void dpls_phy6252_process_adc(void);
void dpls_phy6252_process_tx(void);
void dpls_phy6252_tx_confirmed(void);
void dpls_phy6252_tick(void);
uint32 dpls_phy6252_led_tick(void);

#endif
