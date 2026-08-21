#include "dpls_phy6252_transport.h"

#include "dpls_gatt_service.h"
#include "dpls_phy6252_events.h"
#include "dpls_phy6252_supervisor.h"
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
#define DPLS_LINK_ENCRYPT_TIMEOUT_MS 60000u

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
    bool confirmation_required;
    uint32 in_flight_since_ms;
} dpls_tx_queue_t;

static uint8 task_id;
static uint16 connection_handle = INVALID_CONNHANDLE;
static uint32 connected_at_ms;
static bool connection_had_encryption;
static dpls_rx_queue_t rx;
static dpls_tx_queue_t tx;

static void tx_complete_head(void)
{
    if (tx.count == 0u) {
        tx.in_flight = false;
        tx.confirmation_required = false;
        return;
    }
    tx.head = (uint8)((tx.head + 1u) % DPLS_TX_QUEUE_DEPTH);
    --tx.count;
    tx.in_flight = false;
    tx.confirmation_required = false;
}

static void tx_pump(void)
{
    bStatus_t rc;
    if (tx.in_flight || tx.count == 0u || connection_handle == INVALID_CONNHANDLE) return;

    rc = dpls_gatt_send_indication(connection_handle, tx.slots[tx.head].data,
                                   tx.slots[tx.head].length, task_id);
    if (rc == SUCCESS) {
        tx.in_flight = true;
        tx.confirmation_required = dpls_gatt_needs_confirmation(connection_handle);
        tx.in_flight_since_ms = (uint32)osal_GetSystemClock();
        return;
    }
    if (rc == bleMemAllocError || rc == blePending || rc == MSG_BUFFER_NOT_AVAIL ||
        rc == bleNotConnected)
        return;

    LOG("DPLS TX drop t=%02x rc=%u\n",
        tx.slots[tx.head].length > 1u ? tx.slots[tx.head].data[1] : 0u, rc);
    tx_complete_head();
    if (tx.count != 0u) osal_set_event(task_id, DPLS_PHY6252_TX_EVT);
}

void dpls_phy6252_transport_init(uint8 new_task_id)
{
    task_id = new_task_id;
    connection_handle = INVALID_CONNHANDLE;
    connected_at_ms = 0u;
    connection_had_encryption = false;
    memset(&rx, 0, sizeof(rx));
    memset(&tx, 0, sizeof(tx));
}

void dpls_phy6252_transport_connected(uint16 conn_handle)
{
    connection_handle = conn_handle;
    connected_at_ms = (uint32)osal_GetSystemClock();
    connection_had_encryption = false;
}

void dpls_phy6252_transport_disconnected(void)
{
    connection_handle = INVALID_CONNHANDLE;
    connected_at_ms = 0u;
    connection_had_encryption = false;
    memset(&rx, 0, sizeof(rx));
    memset(&tx, 0, sizeof(tx));
}

bool dpls_phy6252_transport_connected_now(void)
{
    return connection_handle != INVALID_CONNHANDLE;
}

uint8 dpls_phy6252_transport_receive_frame(const uint8 *data, uint16 length)
{
    dpls_rx_slot_t *slot;
    if (!data || length == 0u || length > DPLS_RX_SLOT_SIZE)
        return ATT_ERR_INVALID_VALUE_SIZE;
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
    if (rx.count == 0u) return;
    rx.slots[rx.head].length = 0u;
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
    if (!frame || length == 0u || length > DPLS_TX_SLOT_SIZE ||
        tx.count >= DPLS_TX_QUEUE_DEPTH)
        return false;

    memcpy(tx.slots[tx.tail].data, frame, length);
    tx.slots[tx.tail].length = (uint16)length;
    tx.tail = (uint8)((tx.tail + 1u) % DPLS_TX_QUEUE_DEPTH);
    ++tx.count;
    osal_set_event(task_id, DPLS_PHY6252_TX_EVT);
    return true;
}

void dpls_phy6252_transport_disconnect(void *context)
{
    (void)context;
    if (connection_handle != INVALID_CONNHANDLE)
        (void)GAPRole_TerminateConnection();
}

void dpls_phy6252_transport_process_tx(void)
{
    tx_pump();
}

void dpls_phy6252_transport_tx_confirmed(void)
{
    if (!tx.in_flight || !tx.confirmation_required) return;
    tx_complete_head();
    tx_pump();
}

void dpls_phy6252_transport_tick_tx(uint32 now_ms)
{
    uint32 elapsed;

    if (connection_handle == INVALID_CONNHANDLE) return;
    if (!tx.in_flight) {
        tx_pump();
        return;
    }

    elapsed = (uint32)(now_ms - tx.in_flight_since_ms);
    if (tx.confirmation_required) {
        if (elapsed >= DPLS_TX_CONFIRM_TIMEOUT_MS) {
            LOG("DPLS TX confirmation timeout\n");
            (void)GAPRole_TerminateConnection();
        }
        return;
    }

    /* Notify не имеет ATT confirmation. Выдерживаем минимальный pacing, затем
     * освобождаем ровно один slot. */
    if (elapsed >= DPLS_TX_NOTIFY_PACE_MS) {
        tx_complete_head();
        tx_pump();
    }
}

void dpls_phy6252_transport_tick_security(uint32 now_ms)
{
    if (connection_handle == INVALID_CONNHANDLE) return;

    if (dpls_phy6252_transport_encrypted(NULL)) {
        connection_had_encryption = true;
        return;
    }

    /* Это только resource timeout plaintext link. Он никогда не делает выводов
     * о состоянии bond и никогда не удаляет ключи. */
    if (!connection_had_encryption &&
        (uint32)(now_ms - connected_at_ms) >= DPLS_LINK_ENCRYPT_TIMEOUT_MS) {
        LOG("DPLS KILL plaintext link\n");
        (void)GAPRole_TerminateConnection();
    }
}

bool dpls_phy6252_transport_tx_idle(void)
{
    return tx.count == 0u && !tx.in_flight;
}

bool dpls_phy6252_transport_factory_forget_bonds(void)
{
    if (connection_handle != INVALID_CONNHANDLE) return false;

    dpls_phy6252_supervisor_blocking_io_begin();
    GAPBondMgr_SetParameter(GAPBOND_ERASE_ALLBONDS, 0, NULL);
    dpls_phy6252_supervisor_blocking_io_end();
    return true;
}
