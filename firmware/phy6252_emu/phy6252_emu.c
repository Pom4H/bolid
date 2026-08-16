#include "phy6252_emu.h"

#include <string.h>

static void reset_rx(phy6252_emu_t *emu)
{
    memset(&emu->rx, 0, sizeof(emu->rx));
}

static void reset_tx(phy6252_emu_t *emu)
{
    memset(&emu->tx, 0, sizeof(emu->tx));
}

void phy6252_emu_init(phy6252_emu_t *emu, const phy6252_emu_hooks_t *hooks)
{
    memset(emu, 0, sizeof(*emu));
    if (hooks != NULL) {
        emu->hooks = *hooks;
    }
}

void phy6252_emu_connect(phy6252_emu_t *emu)
{
    emu->connected = true;
    reset_rx(emu);
    reset_tx(emu);
}

void phy6252_emu_disconnect(phy6252_emu_t *emu)
{
    emu->connected = false;
    emu->cccd = 0;
    reset_rx(emu);
    reset_tx(emu);
}

void phy6252_emu_set_cccd(phy6252_emu_t *emu, uint16_t cfg)
{
    emu->cccd = cfg;
}

bool phy6252_emu_cccd_notify(const phy6252_emu_t *emu)
{
    return (emu->cccd & PHY6252_EMU_CCCD_NOTIFY) != 0u;
}

bool phy6252_emu_gatt_write(phy6252_emu_t *emu, const uint8_t *data, uint16_t length)
{
    phy6252_emu_rx_slot_t *slot;

    if (data == NULL || length == 0u || length > PHY6252_EMU_RX_SLOT) {
        return false;
    }
    if (emu->rx.count >= PHY6252_EMU_RX_DEPTH) {
        return false;
    }
    slot = &emu->rx.slots[emu->rx.tail];
    memcpy(slot->data, data, length);
    slot->length = length;
    emu->rx.tail = (uint8_t)((emu->rx.tail + 1u) % PHY6252_EMU_RX_DEPTH);
    ++emu->rx.count;
    return true;
}

bool phy6252_emu_enqueue_tx(phy6252_emu_t *emu, const uint8_t *data, uint16_t length)
{
    phy6252_emu_tx_slot_t *slot;

    if (data == NULL || length == 0u || length > PHY6252_EMU_TX_SLOT) {
        return false;
    }
    if (emu->tx.count >= PHY6252_EMU_TX_DEPTH) {
        if (phy6252_emu_cccd_notify(emu)) {
            return false;
        }
        reset_tx(emu);
        if (emu->hooks.on_indicate_overflow != NULL) {
            emu->hooks.on_indicate_overflow(emu->hooks.context);
        }
        return false;
    }
    slot = &emu->tx.slots[emu->tx.tail];
    memcpy(slot->data, data, length);
    slot->length = length;
    emu->tx.tail = (uint8_t)((emu->tx.tail + 1u) % PHY6252_EMU_TX_DEPTH);
    ++emu->tx.count;
    return true;
}

void phy6252_emu_process_rx(phy6252_emu_t *emu)
{
    phy6252_emu_rx_slot_t slot;

    if (emu->rx.count == 0u) {
        return;
    }
    slot = emu->rx.slots[emu->rx.head];
    emu->rx.head = (uint8_t)((emu->rx.head + 1u) % PHY6252_EMU_RX_DEPTH);
    --emu->rx.count;
    if (emu->hooks.on_gatt_write != NULL) {
        emu->hooks.on_gatt_write(emu->hooks.context, slot.data, slot.length);
    }
}

void phy6252_emu_complete_tx_head(phy6252_emu_t *emu)
{
    if (emu->tx.count == 0u) {
        emu->tx.in_flight = false;
        return;
    }
    emu->tx.head = (uint8_t)((emu->tx.head + 1u) % PHY6252_EMU_TX_DEPTH);
    --emu->tx.count;
    emu->tx.in_flight = false;
}

void phy6252_emu_process_tx(phy6252_emu_t *emu)
{
    const phy6252_emu_tx_slot_t *slot;
    bool notify;

    if (emu->tx.in_flight || emu->tx.count == 0u || !emu->connected) {
        return;
    }
    slot = &emu->tx.slots[emu->tx.head];
    notify = phy6252_emu_cccd_notify(emu);
    if (emu->hooks.on_att_pdu != NULL) {
        emu->hooks.on_att_pdu(emu->hooks.context, slot->data, slot->length, notify);
    }
    ++emu->att_sent;
    emu->tx.in_flight = true;
    emu->tx.in_flight_since_ms = emu->now_ms;
}

void phy6252_emu_att_cfm(phy6252_emu_t *emu)
{
    if (emu->tx.in_flight) {
        phy6252_emu_complete_tx_head(emu);
    }
}

void phy6252_emu_tick(phy6252_emu_t *emu, uint32_t now_ms)
{
    uint32_t pace_ms;

    emu->now_ms = now_ms;
    /* Timer only. TX is a separate OSAL turn — call process_tx after this. */
    pace_ms = phy6252_emu_cccd_notify(emu)
        ? PHY6252_EMU_NOTIFY_PACE_MS
        : PHY6252_EMU_INDICATE_TIMEOUT_MS;
    if (emu->tx.in_flight &&
        (uint32_t)(now_ms - emu->tx.in_flight_since_ms) >= pace_ms) {
        phy6252_emu_complete_tx_head(emu);
    }
    if (emu->snv_dirty && phy6252_emu_tx_idle(emu)) {
        emu->snv_dirty = false;
        if (emu->hooks.on_snv_flush != NULL) {
            emu->hooks.on_snv_flush(emu->hooks.context, emu->snv_id,
                                    emu->snv_page, emu->snv_length);
        }
    }
}

void phy6252_emu_run_after_write(phy6252_emu_t *emu)
{
    while (emu->rx.count != 0u) {
        phy6252_emu_process_rx(emu);
    }
    phy6252_emu_process_tx(emu);
}

bool phy6252_emu_tx_idle(const phy6252_emu_t *emu)
{
    return !emu->tx.in_flight && emu->tx.count == 0u;
}

void phy6252_emu_snv_mark(phy6252_emu_t *emu, uint16_t id,
                         const uint8_t *page, uint16_t length)
{
    if (page == NULL || length == 0u || length > PHY6252_EMU_SNV_PAGE) {
        return;
    }
    emu->snv_id = id;
    emu->snv_length = length;
    memcpy(emu->snv_page, page, length);
    emu->snv_dirty = true;
}
