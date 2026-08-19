#include "dpls_sim_transport.h"

#include <string.h>

static void reset_rx(dpls_sim_transport_t *transport)
{
    memset(&transport->rx, 0, sizeof(transport->rx));
}

static void reset_tx(dpls_sim_transport_t *transport)
{
    memset(&transport->tx, 0, sizeof(transport->tx));
}

void dpls_sim_transport_init(dpls_sim_transport_t *transport,
                             const dpls_sim_transport_hooks_t *hooks)
{
    memset(transport, 0, sizeof(*transport));
    if (hooks != NULL) transport->hooks = *hooks;
}

void dpls_sim_transport_connect(dpls_sim_transport_t *transport)
{
    transport->connected = true;
    reset_rx(transport);
    reset_tx(transport);
}

void dpls_sim_transport_disconnect(dpls_sim_transport_t *transport)
{
    transport->connected = false;
    transport->cccd = 0u;
    reset_rx(transport);
    reset_tx(transport);
}

void dpls_sim_transport_set_cccd(dpls_sim_transport_t *transport, uint16_t cfg)
{
    transport->cccd = cfg;
}

bool dpls_sim_transport_cccd_notify(const dpls_sim_transport_t *transport)
{
    return (transport->cccd & DPLS_SIM_TRANSPORT_CCCD_NOTIFY) != 0u;
}

bool dpls_sim_transport_gatt_write(dpls_sim_transport_t *transport,
                                   const uint8_t *data, uint16_t length)
{
    dpls_sim_transport_rx_slot_t *slot;
    if (data == NULL || length == 0u || length > DPLS_SIM_TRANSPORT_RX_SLOT) return false;
    if (transport->rx.count >= DPLS_SIM_TRANSPORT_RX_DEPTH) return false;
    slot = &transport->rx.slots[transport->rx.tail];
    memcpy(slot->data, data, length);
    slot->length = length;
    transport->rx.tail = (uint8_t)((transport->rx.tail + 1u) % DPLS_SIM_TRANSPORT_RX_DEPTH);
    ++transport->rx.count;
    return true;
}

bool dpls_sim_transport_enqueue_tx(dpls_sim_transport_t *transport,
                                   const uint8_t *data, uint16_t length)
{
    dpls_sim_transport_tx_slot_t *slot;
    if (data == NULL || length == 0u || length > DPLS_SIM_TRANSPORT_TX_SLOT) return false;
    if (transport->tx.count >= DPLS_SIM_TRANSPORT_TX_DEPTH) {
        if (dpls_sim_transport_cccd_notify(transport)) return false;
        reset_tx(transport);
        if (transport->hooks.on_indicate_overflow != NULL) {
            transport->hooks.on_indicate_overflow(transport->hooks.context);
        }
        return false;
    }
    slot = &transport->tx.slots[transport->tx.tail];
    memcpy(slot->data, data, length);
    slot->length = length;
    transport->tx.tail = (uint8_t)((transport->tx.tail + 1u) % DPLS_SIM_TRANSPORT_TX_DEPTH);
    ++transport->tx.count;
    return true;
}

void dpls_sim_transport_process_rx(dpls_sim_transport_t *transport)
{
    dpls_sim_transport_rx_slot_t slot;
    if (transport->rx.count == 0u) return;
    slot = transport->rx.slots[transport->rx.head];
    transport->rx.head = (uint8_t)((transport->rx.head + 1u) % DPLS_SIM_TRANSPORT_RX_DEPTH);
    --transport->rx.count;
    if (transport->hooks.on_gatt_write != NULL) {
        transport->hooks.on_gatt_write(transport->hooks.context, slot.data, slot.length);
    }
}

void dpls_sim_transport_complete_tx_head(dpls_sim_transport_t *transport)
{
    if (transport->tx.count == 0u) {
        transport->tx.in_flight = false;
        return;
    }
    transport->tx.head = (uint8_t)((transport->tx.head + 1u) % DPLS_SIM_TRANSPORT_TX_DEPTH);
    --transport->tx.count;
    transport->tx.in_flight = false;
}

void dpls_sim_transport_process_tx(dpls_sim_transport_t *transport)
{
    const dpls_sim_transport_tx_slot_t *slot;
    bool notify;
    if (transport->tx.in_flight || transport->tx.count == 0u || !transport->connected) return;
    slot = &transport->tx.slots[transport->tx.head];
    notify = dpls_sim_transport_cccd_notify(transport);
    if (transport->hooks.on_att_pdu != NULL) {
        transport->hooks.on_att_pdu(transport->hooks.context, slot->data, slot->length, notify);
    }
    ++transport->att_sent;
    transport->tx.in_flight = true;
    transport->tx.in_flight_since_ms = transport->now_ms;
}

void dpls_sim_transport_att_cfm(dpls_sim_transport_t *transport)
{
    if (transport->tx.in_flight) dpls_sim_transport_complete_tx_head(transport);
}

void dpls_sim_transport_tick(dpls_sim_transport_t *transport, uint32_t now_ms)
{
    uint32_t pace_ms;
    transport->now_ms = now_ms;
    pace_ms = dpls_sim_transport_cccd_notify(transport)
        ? DPLS_SIM_TRANSPORT_NOTIFY_PACE_MS
        : DPLS_SIM_TRANSPORT_INDICATE_TIMEOUT_MS;
    if (transport->tx.in_flight &&
        (uint32_t)(now_ms - transport->tx.in_flight_since_ms) >= pace_ms) {
        dpls_sim_transport_complete_tx_head(transport);
    }
}

void dpls_sim_transport_run_after_write(dpls_sim_transport_t *transport)
{
    while (transport->rx.count != 0u) dpls_sim_transport_process_rx(transport);
    dpls_sim_transport_process_tx(transport);
}
