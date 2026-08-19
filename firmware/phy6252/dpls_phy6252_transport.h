#ifndef DPLS_PHY6252_TRANSPORT_H
#define DPLS_PHY6252_TRANSPORT_H

#include "bcomdef.h"
#include "types.h"
#include <stddef.h>

void dpls_phy6252_transport_init(uint8 task_id);
void dpls_phy6252_transport_connected(uint16 conn_handle);
void dpls_phy6252_transport_disconnected(bool authenticated);
void dpls_phy6252_transport_pairing_state(uint8 state, uint8 status);
bool dpls_phy6252_transport_connected_now(void);
uint16 dpls_phy6252_transport_connection_handle(void);

/* GATT RX callback: enqueue only, never execute protocol work in the ATT write turn.
 * Runtime borrows the queue head for one OSAL turn, then consumes it explicitly;
 * there is no second 96-byte copy buffer. */
uint8 dpls_phy6252_transport_receive_frame(const uint8 *data, uint16 length);
bool dpls_phy6252_transport_peek_rx(const uint8 **data, uint16 *length);
void dpls_phy6252_transport_consume_rx(void);

/* dpls_link_hal_t implementation. */
bool dpls_phy6252_transport_encrypted(void *context);
bool dpls_phy6252_transport_indicate(void *context, const uint8_t *frame, size_t length);
void dpls_phy6252_transport_disconnect(void *context);

void dpls_phy6252_transport_process_tx(void);
void dpls_phy6252_transport_tx_confirmed(void);
void dpls_phy6252_transport_tick_tx(uint32 now_ms);
void dpls_phy6252_transport_tick_security(bool authenticated, uint32 now_ms);

void dpls_phy6252_transport_erase_bonds(void);

#endif
