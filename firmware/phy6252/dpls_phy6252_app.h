#ifndef DPLS_PHY6252_APP_H
#define DPLS_PHY6252_APP_H

#include "bcomdef.h"

/* dpls_phy6252_app.c includes this header before the vendor osal_snv.h. Rename
 * only that translation unit's SNV calls to our radio-safe adapter. The guard
 * implementation defines DPLS_PHY6252_SNV_GUARD_IMPL so it can call the real
 * SDK functions without recursion. dplsBLEPeripheral.c does not call SNV. */
#ifndef DPLS_PHY6252_SNV_GUARD_IMPL
#define osal_snv_read dpls_phy6252_snv_read_guarded
#define osal_snv_write dpls_phy6252_snv_write_guarded
#endif

#define DPLS_PHY6252_RX_EVT 0x0040
#define DPLS_PHY6252_TX_EVT 0x0400
#define DPLS_PHY6252_ADC_EVT 0x0800
#define DPLS_PHY6252_STORAGE_EVT 0x1000

void dpls_phy6252_init(uint8 task_id);
void dpls_phy6252_connected(uint16 conn_handle);
void dpls_phy6252_disconnected(void);
bool dpls_phy6252_link_active(void);
bool dpls_phy6252_storage_pending(void);
void dpls_phy6252_process_rx(void);
/* Convert the raw ADC samples captured by the (minimal) ISR into calibrated
 * millivolts. Runs in the OSAL task — this is where the soft-float scaling and
 * window averaging live, off the interrupt path. */
void dpls_phy6252_process_adc(void);
void dpls_phy6252_process_tx(void);
/* Flush at most one deferred journal block. Physical SNV writes are additionally
 * guarded by dpls_phy6252_snv_guard and cannot run while a BLE handle is active. */
void dpls_phy6252_process_storage(void);
void dpls_phy6252_tx_confirmed(void);
void dpls_phy6252_tick(void);
uint32 dpls_phy6252_led_tick(void);

#endif