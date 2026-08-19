#ifndef DPLS_SIM_TRANSPORT_H
#define DPLS_SIM_TRANSPORT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Private transport model for the Test-DPLS host simulator.
 * It only models ATT queue/pacing used by lab and Soft-BLE tests. Production
 * PHY6252 HEX execution belongs to Firmverse and is intentionally out of scope. */
#define DPLS_SIM_TRANSPORT_RX_DEPTH 6u
#define DPLS_SIM_TRANSPORT_RX_SLOT 96u
#define DPLS_SIM_TRANSPORT_TX_DEPTH 4u
#define DPLS_SIM_TRANSPORT_TX_SLOT 168u
#define DPLS_SIM_TRANSPORT_NOTIFY_PACE_MS 80u
#define DPLS_SIM_TRANSPORT_INDICATE_TIMEOUT_MS 2000u
#define DPLS_SIM_TRANSPORT_CCCD_NOTIFY 0x0001u
#define DPLS_SIM_TRANSPORT_CCCD_INDICATE 0x0002u
#define DPLS_SIM_TRANSPORT_CCCD_SAMSUNG 0x0003u

typedef struct {
    uint8_t data[DPLS_SIM_TRANSPORT_RX_SLOT];
    uint16_t length;
} dpls_sim_transport_rx_slot_t;

typedef struct {
    uint8_t data[DPLS_SIM_TRANSPORT_TX_SLOT];
    uint16_t length;
} dpls_sim_transport_tx_slot_t;

typedef struct {
    dpls_sim_transport_tx_slot_t slots[DPLS_SIM_TRANSPORT_TX_DEPTH];
    uint8_t head;
    uint8_t tail;
    uint8_t count;
    bool in_flight;
    uint32_t in_flight_since_ms;
} dpls_sim_transport_tx_t;

typedef struct {
    dpls_sim_transport_rx_slot_t slots[DPLS_SIM_TRANSPORT_RX_DEPTH];
    uint8_t head;
    uint8_t tail;
    uint8_t count;
} dpls_sim_transport_rx_t;

typedef struct {
    void (*on_att_pdu)(void *context, const uint8_t *data, uint16_t length, bool notify);
    void (*on_gatt_write)(void *context, const uint8_t *data, uint16_t length);
    void (*on_indicate_overflow)(void *context);
    void *context;
} dpls_sim_transport_hooks_t;

typedef struct {
    dpls_sim_transport_hooks_t hooks;
    dpls_sim_transport_rx_t rx;
    dpls_sim_transport_tx_t tx;
    bool connected;
    uint16_t cccd;
    uint32_t now_ms;
    unsigned att_sent;
} dpls_sim_transport_t;

void dpls_sim_transport_init(dpls_sim_transport_t *transport,
                             const dpls_sim_transport_hooks_t *hooks);
void dpls_sim_transport_connect(dpls_sim_transport_t *transport);
void dpls_sim_transport_disconnect(dpls_sim_transport_t *transport);
void dpls_sim_transport_set_cccd(dpls_sim_transport_t *transport, uint16_t cfg);
bool dpls_sim_transport_cccd_notify(const dpls_sim_transport_t *transport);
bool dpls_sim_transport_gatt_write(dpls_sim_transport_t *transport,
                                   const uint8_t *data, uint16_t length);
bool dpls_sim_transport_enqueue_tx(dpls_sim_transport_t *transport,
                                   const uint8_t *data, uint16_t length);
void dpls_sim_transport_process_rx(dpls_sim_transport_t *transport);
void dpls_sim_transport_process_tx(dpls_sim_transport_t *transport);
void dpls_sim_transport_complete_tx_head(dpls_sim_transport_t *transport);
void dpls_sim_transport_att_cfm(dpls_sim_transport_t *transport);
void dpls_sim_transport_tick(dpls_sim_transport_t *transport, uint32_t now_ms);
void dpls_sim_transport_run_after_write(dpls_sim_transport_t *transport);

/* Existing product-simulator code used these names when the transport lived in
 * a standalone PHY6252 emulator directory. Keep source churn local while the
 * implementation and ownership are now entirely under firmware/sim. */
typedef dpls_sim_transport_t phy6252_emu_t;
typedef dpls_sim_transport_hooks_t phy6252_emu_hooks_t;
#define PHY6252_EMU_RX_DEPTH DPLS_SIM_TRANSPORT_RX_DEPTH
#define PHY6252_EMU_RX_SLOT DPLS_SIM_TRANSPORT_RX_SLOT
#define PHY6252_EMU_TX_DEPTH DPLS_SIM_TRANSPORT_TX_DEPTH
#define PHY6252_EMU_TX_SLOT DPLS_SIM_TRANSPORT_TX_SLOT
#define PHY6252_EMU_NOTIFY_PACE_MS DPLS_SIM_TRANSPORT_NOTIFY_PACE_MS
#define PHY6252_EMU_INDICATE_TIMEOUT_MS DPLS_SIM_TRANSPORT_INDICATE_TIMEOUT_MS
#define PHY6252_EMU_CCCD_NOTIFY DPLS_SIM_TRANSPORT_CCCD_NOTIFY
#define PHY6252_EMU_CCCD_INDICATE DPLS_SIM_TRANSPORT_CCCD_INDICATE
#define PHY6252_EMU_CCCD_SAMSUNG DPLS_SIM_TRANSPORT_CCCD_SAMSUNG
#define phy6252_emu_init dpls_sim_transport_init
#define phy6252_emu_connect dpls_sim_transport_connect
#define phy6252_emu_disconnect dpls_sim_transport_disconnect
#define phy6252_emu_set_cccd dpls_sim_transport_set_cccd
#define phy6252_emu_cccd_notify dpls_sim_transport_cccd_notify
#define phy6252_emu_gatt_write dpls_sim_transport_gatt_write
#define phy6252_emu_enqueue_tx dpls_sim_transport_enqueue_tx
#define phy6252_emu_process_rx dpls_sim_transport_process_rx
#define phy6252_emu_process_tx dpls_sim_transport_process_tx
#define phy6252_emu_complete_tx_head dpls_sim_transport_complete_tx_head
#define phy6252_emu_att_cfm dpls_sim_transport_att_cfm
#define phy6252_emu_tick dpls_sim_transport_tick
#define phy6252_emu_run_after_write dpls_sim_transport_run_after_write

#endif
