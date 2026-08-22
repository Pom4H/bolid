#ifndef DPLS_PHY6252_EVENTS_H
#define DPLS_PHY6252_EVENTS_H

/* OSAL event ownership for the Test-DPLS runtime. Keep these out of the
 * vendor SimpleBLEPeripheral namespace so each adapter can wake exactly the
 * subsystem that owns the work. */
#define DPLS_PHY6252_RX_EVT       0x0040u
#define DPLS_PHY6252_TX_EVT       0x0400u
#define DPLS_PHY6252_ADC_EVT      0x0800u
#define DPLS_PHY6252_STORAGE_EVT  0x1000u
#define DPLS_PHY6252_DEBUG_UART_BOOT_EVT  0x0002u
#define DPLS_PHY6252_DEBUG_UART_WAKE_EVT  0x0004u
#define DPLS_PHY6252_DEBUG_UART_SLEEP_EVT 0x0008u

#endif
