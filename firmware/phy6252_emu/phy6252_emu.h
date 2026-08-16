#ifndef PHY6252_EMU_H
#define PHY6252_EMU_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Host model of PHY6252 / PB-03F BLE ATT + OSAL turns + deferred SNV.
 *
 * Product firmware (DPLS or anything else) owns GATT payloads and GPIO.
 * This file owns the chip behaviours measured on SDK 3.1.2 + Samsung SM-A135F:
 *
 *  - OSAL: RX, TX, TICK, LED are separate turns. LED never pumps TX.
 *    GATT write only queues RX; process_rx then process_tx (not nested under
 *    the write callback).
 *  - ATT: one PDU in flight.
 *    CCCD notify bit (Samsung writes 0x03): GATT_Notification, no ATT CFM,
 *    advance on the 80 ms notify-pace tick.
 *    Indicate-only: wait ATT CFM or 2000 ms timeout.
 *  - Notify queue full: drop the new frame, keep the link.
 *  - Indicate queue full: empty the TX queue and report overflow (product
 *    typically drops the link).
 *  - SNV: dirty page in RAM; flush only while TX is idle (flash erase stalls
 *    the link layer — AUTH_RESULT then never leaves the radio).
 *  - Default CMSIS ARMCM0 stack is 1024 bytes. Keep HMAC/SHA off the OSAL
 *    stack or the TX queue in BSS is corrupted.
 *
 * Copy this directory into another PHY6252 project; it has no product types.
 */

#define PHY6252_EMU_RX_DEPTH 6u
#define PHY6252_EMU_RX_SLOT 96u
#define PHY6252_EMU_TX_DEPTH 4u
#define PHY6252_EMU_TX_SLOT 168u
#define PHY6252_EMU_NOTIFY_PACE_MS 80u
#define PHY6252_EMU_INDICATE_TIMEOUT_MS 2000u
#define PHY6252_EMU_SNV_PAGE 248u
#define PHY6252_EMU_APP_STACK_BYTES 1024u
#define PHY6252_EMU_CCCD_NOTIFY 0x0001u
#define PHY6252_EMU_CCCD_INDICATE 0x0002u
#define PHY6252_EMU_CCCD_SAMSUNG 0x0003u

typedef struct {
    uint8_t data[PHY6252_EMU_RX_SLOT];
    uint16_t length;
} phy6252_emu_rx_slot_t;

typedef struct {
    uint8_t data[PHY6252_EMU_TX_SLOT];
    uint16_t length;
} phy6252_emu_tx_slot_t;

typedef struct {
    phy6252_emu_tx_slot_t slots[PHY6252_EMU_TX_DEPTH];
    uint8_t head;
    uint8_t tail;
    uint8_t count;
    bool in_flight;
    uint32_t in_flight_since_ms;
} phy6252_emu_tx_t;

typedef struct {
    phy6252_emu_rx_slot_t slots[PHY6252_EMU_RX_DEPTH];
    uint8_t head;
    uint8_t tail;
    uint8_t count;
} phy6252_emu_rx_t;

typedef struct phy6252_emu phy6252_emu_t;

typedef struct {
    /* Delivered when process_tx actually puts a PDU on the air. */
    void (*on_att_pdu)(void *context, const uint8_t *data, uint16_t length, bool notify);
    /* One queued GATT write, from process_rx — product parses the payload. */
    void (*on_gatt_write)(void *context, const uint8_t *data, uint16_t length);
    /* Indicate-queue overflow after the TX queue has been emptied. */
    void (*on_indicate_overflow)(void *context);
    /* SNV page committed because TX is idle. */
    void (*on_snv_flush)(void *context, uint16_t id, const uint8_t *page, uint16_t length);
    void *context;
} phy6252_emu_hooks_t;

struct phy6252_emu {
    phy6252_emu_hooks_t hooks;
    phy6252_emu_rx_t rx;
    phy6252_emu_tx_t tx;
    bool connected;
    uint16_t cccd;
    uint32_t now_ms;
    bool snv_dirty;
    uint16_t snv_id;
    uint16_t snv_length;
    uint8_t snv_page[PHY6252_EMU_SNV_PAGE];
    unsigned att_sent;
};

void phy6252_emu_init(phy6252_emu_t *emu, const phy6252_emu_hooks_t *hooks);
void phy6252_emu_connect(phy6252_emu_t *emu);
void phy6252_emu_disconnect(phy6252_emu_t *emu);
void phy6252_emu_set_cccd(phy6252_emu_t *emu, uint16_t cfg);
bool phy6252_emu_cccd_notify(const phy6252_emu_t *emu);

/* GATT Write Request: queue only. Does not run the product or TX. */
bool phy6252_emu_gatt_write(phy6252_emu_t *emu, const uint8_t *data, uint16_t length);

/* Product TX path (indication/notification payload). */
bool phy6252_emu_enqueue_tx(phy6252_emu_t *emu, const uint8_t *data, uint16_t length);

void phy6252_emu_process_rx(phy6252_emu_t *emu);
void phy6252_emu_process_tx(phy6252_emu_t *emu);
void phy6252_emu_complete_tx_head(phy6252_emu_t *emu);
void phy6252_emu_att_cfm(phy6252_emu_t *emu);
void phy6252_emu_tick(phy6252_emu_t *emu, uint32_t now_ms);
/* OSAL after RX_EVT: drain RX, then one TX attempt. Does not pump the queue. */
void phy6252_emu_run_after_write(phy6252_emu_t *emu);

bool phy6252_emu_tx_idle(const phy6252_emu_t *emu);
void phy6252_emu_snv_mark(phy6252_emu_t *emu, uint16_t id,
                         const uint8_t *page, uint16_t length);

#endif
