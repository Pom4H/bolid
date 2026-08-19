#include "dpls_phy6252_transport.h"

#include "dpls_gatt_service.h"
#include "dpls_phy6252_events.h"
#include "OSAL.h"
#include "att.h"
#include "gapbondmgr.h"
#include "linkdb.h"
#include "log.h"
#include "peripheral.h"
#include <string.h>

#define DPLS_RX_QUEUE_DEPTH 6u
#define DPLS_RX_SLOT_SIZE 96u
#define DPLS_TX_QUEUE_DEPTH 4u
#define DPLS_TX_SLOT_SIZE 168u
#define DPLS_TX_CONFIRM_TIMEOUT_MS 2000u
#define DPLS_TX_NOTIFY_PACE_MS 80u
#define DPLS_BOND_DESYNC_LIMIT 3u
#define DPLS_BOND_DESYNC_WINDOW_MS 120000u
#define DPLS_LINK_ENCRYPT_TIMEOUT_MS 15000u

typedef struct {
    uint8 data[DPLS_RX_SLOT_SIZE];
    uint16 length;
} dpls_rx_slot_t;

typedef struct {
    dpls_rx_slot_t slots[DPLS_RX_QUEUE_DEPTH];
    uint8 head;
    uint8 tail;
    uint8 count;
} dpls_rx_queue_t;

typedef struct {
    uint16 length;
    uint8 data[DPLS_TX_SLOT_SIZE];
} dpls_tx_slot_t;

typedef struct {
    dpls_tx_slot_t slots[DPLS_TX_QUEUE_DEPTH];
    uint8 head;
    uint8 tail;
    uint8 count;
    bool in_flight;
    uint32 in_flight_since_ms;
} dpls_tx_queue_t;

static uint8 task_id;
static uint16 connection_handle = INVALID_CONNHANDLE;
static uint32 connected_at_ms;
static bool connection_had_encryption;
static uint8 pre_auth_disconnect_count;
static uint32 pre_auth_disconnect_window_ms;
static dpls_rx_queue_t rx;
static dpls_tx_queue_t tx;

static void tx_complete_head(void)
{
    if (tx.count == 0u) {
        tx.in_flight = false;
        return;
    }
    tx.head = (uint8)((tx.head + 1u) % DPLS_TX_QUEUE_DEPTH);
    --tx.count;
    tx.in_flight = false;
}

static void tx_pump(void)
{
    bStatus_t rc;
    if (tx.in_flight || tx.count == 0u || connection_handle == INVALID_CONNHANDLE) {
        if (tx.count != 0u) {
            LOG("DPLS TX skip inflight=%u count=%u conn=%u\n",
                tx.in_flight ? 1u : 0u,
                tx.count,
                connection_handle == INVALID_CONNHANDLE ? 0u : 1u);
        }
        return;
    }

    rc = dpls_gatt_send_indication(connection_handle, tx.slots[tx.head].data,
                                   tx.slots[tx.head].length, task_id);
    if (rc == SUCCESS) {
        tx.in_flight = true;
        tx.in_flight_since_ms = (uint32)osal_GetSystemClock();
    } else if (rc == bleMemAllocError || rc == blePending || rc == MSG_BUFFER_NOT_AVAIL ||
               rc == bleNotConnected) {
        /* Keep head queued. A later TX event/tick retries after the stack drains. */
    } else {
        LOG("DPLS TX drop t=%02x rc=%u\n",
            tx.slots[tx.head].length > 1u ? tx.slots[tx.head].data[1] : 0u,
            rc);
        tx_complete_head();
        if (tx.count != 0u) osal_set_event(task_id, DPLS_PHY6252_TX_EVT);
    }
}

static void note_pre_auth_disconnect(bool authenticated)
{
    uint32 now = (uint32)osal_GetSystemClock();
    if (authenticated || !connection_had_encryption) return;
    if (pre_auth_disconnect_window_ms == 0u ||
        (uint32)(now - pre_auth_disconnect_window_ms) > DPLS_BOND_DESYNC_WINDOW_MS) {
        pre_auth_disconnect_count = 0u;
        pre_auth_disconnect_window_ms = now;
    }
    if (++pre_auth_disconnect_count < DPLS_BOND_DESYNC_LIMIT) return;
    pre_auth_disconnect_count = 0u;
    pre_auth_disconnect_window_ms = 0u;
    dpls_phy6252_transport_erase_bonds();
}

void dpls_phy6252_transport_init(uint8 new_task_id)
{
    task_id = new_task_id;
    connection_handle = INVALID_CONNHANDLE;
    connected_at_ms = 0u;
    connection_had_encryption = false;
    pre_auth_disconnect_count = 0u;
    pre_auth_disconnect_window_ms = 0u;
    memset(&rx, 0, sizeof(rx));
    memset(&tx, 0, sizeof(tx));
}

void dpls_phy6252_transport_connected(uint16 conn_handle)
{
    connection_handle = conn_handle;
    connected_at_ms = (uint32)osal_GetSystemClock();
    connection_had_encryption = false;
}

void dpls_phy6252_transport_disconnected(bool authenticated)
{
    note_pre_auth_disconnect(authenticated);
    connection_handle = INVALID_CONNHANDLE;
    connected_at_ms = 0u;
    connection_had_encryption = false;
    memset(&rx, 0, sizeof(rx));
    memset(&tx, 0, sizeof(tx));
}

void dpls_phy6252_transport_pairing_state(uint8 state, uint8 status)
{
    if (state == GAPBOND_PAIRING_STATE_COMPLETE && status != SUCCESS)
        dpls_phy6252_transport_erase_bonds();
}

bool dpls_phy6252_transport_connected_now(void)
{
    return connection_handle != INVALID_CONNHANDLE;
}

uint16 dpls_phy6252_transport_connection_handle(void)
{
    return connection_handle;
}

uint8 dpls_phy6252_transport_receive_frame(const uint8 *data, uint16 length)
{
    dpls_rx_slot_t *slot;
    if (length > DPLS_RX_SLOT_SIZE) return ATT_ERR_INVALID_VALUE_SIZE;
    if (rx.count >= DPLS_RX_QUEUE_DEPTH) return ATT_ERR_INSUFFICIENT_RESOURCES;
    slot = &rx.slots[rx.tail];
    memcpy(slot->data, data, length);
    slot->length = length;
    rx.tail = (uint8)((rx.tail + 1u) % DPLS_RX_QUEUE_DEPTH);
    ++rx.count;
    osal_set_event(task_id, DPLS_PHY6252_RX_EVT);
    return SUCCESS;
}

bool dpls_phy6252_transport_peek_rx(const uint8 **data, uint16 *length)
{
    dpls_rx_slot_t *slot;
    if (!data || !length || rx.count == 0u) return false;
    slot = &rx.slots[rx.head];
    *data = slot->data;
    *length = slot->length;
    return true;
}

void dpls_phy6252_transport_consume_rx(void)
{
    dpls_rx_slot_t *slot;
    if (rx.count == 0u) return;
    slot = &rx.slots[rx.head];
    slot->length = 0u;
    rx.head = (uint8)((rx.head + 1u) % DPLS_RX_QUEUE_DEPTH);
    --rx.count;
    if (rx.count != 0u) osal_set_event(task_id, DPLS_PHY6252_RX_EVT);
}

bool dpls_phy6252_transport_encrypted(void *context)
{
    (void)context;
    return connection_handle != INVALID_CONNHANDLE && linkDB_Encrypted(connection_handle);
}

bool dpls_phy6252_transport_indicate(void *context, const uint8_t *frame, size_t length)
{
    (void)context;
    if (length > DPLS_TX_SLOT_SIZE || tx.count >= DPLS_TX_QUEUE_DEPTH) {
        LOG("DPLS QTX full t=%02x count=%u\n", length > 1u ? frame[1] : 0u, tx.count);
        return false;
    }
    memcpy(tx.slots[tx.tail].data, frame, length);
    tx.slots[tx.tail].length = (uint16)length;
    tx.tail = (uint8)((tx.tail + 1u) % DPLS_TX_QUEUE_DEPTH);
    ++tx.count;
    LOG("DPLS QTX n=%u t=%02x count=%u\n",
        (unsigned)length, length > 1u ? frame[1] : 0u, tx.count);
    osal_set_event(task_id, DPLS_PHY6252_TX_EVT);
    return true;
}

void dpls_phy6252_transport_disconnect(void *context)
{
    (void)context;
    (void)GAPRole_TerminateConnection();
}

void dpls_phy6252_transport_process_tx(void)
{
    tx_pump();
}

void dpls_phy6252_transport_tx_confirmed(void)
{
    LOG("DPLS CFM inflight=%u count=%u\n", tx.in_flight ? 1u : 0u, tx.count);
    if (tx.in_flight) tx_complete_head();
    tx_pump();
}

void dpls_phy6252_transport_tick_tx(uint32 now_ms)
{
    uint32 pace_ms;
    if (connection_handle == INVALID_CONNHANDLE) return;
    pace_ms = dpls_gatt_needs_confirmation(connection_handle)
                  ? DPLS_TX_CONFIRM_TIMEOUT_MS
                  : DPLS_TX_NOTIFY_PACE_MS;
    if (tx.in_flight && (uint32)(now_ms - tx.in_flight_since_ms) >= pace_ms) {
        dpls_phy6252_transport_tx_confirmed();
        return;
    }
    tx_pump();
}

void dpls_phy6252_transport_tick_security(bool authenticated, uint32 now_ms)
{
    if (connection_handle == INVALID_CONNHANDLE) return;
    if (dpls_phy6252_transport_encrypted(NULL)) connection_had_encryption = true;
    if (authenticated) {
        pre_auth_disconnect_count = 0u;
        pre_auth_disconnect_window_ms = 0u;
    }
    if (!dpls_phy6252_transport_encrypted(NULL) && !connection_had_encryption &&
        (uint32)(now_ms - connected_at_ms) >= DPLS_LINK_ENCRYPT_TIMEOUT_MS) {
        LOG("DPLS KILL encrypt\n");
        dpls_phy6252_transport_erase_bonds();
        (void)GAPRole_TerminateConnection();
    }
}

void dpls_phy6252_transport_erase_bonds(void)
{
    GAPBondMgr_SetParameter(GAPBOND_ERASE_ALLBONDS, 0, NULL);
}
