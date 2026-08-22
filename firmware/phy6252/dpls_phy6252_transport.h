#ifndef DPLS_PHY6252_TRANSPORT_H
#define DPLS_PHY6252_TRANSPORT_H

#include "bcomdef.h"
#include "types.h"
#include <stddef.h>

void dpls_phy6252_transport_init(uint8 task_id);
void dpls_phy6252_transport_connected(uint16 conn_handle);
void dpls_phy6252_transport_disconnected(void);
bool dpls_phy6252_transport_connected_now(void);

uint8 dpls_phy6252_transport_receive_frame(const uint8 *data, uint16 length);
bool dpls_phy6252_transport_peek_rx(const uint8 **data, uint16 *length);
void dpls_phy6252_transport_consume_rx(void);

bool dpls_phy6252_transport_encrypted(void *context);
bool dpls_phy6252_transport_indicate(void *context, const uint8_t *frame, size_t length);
void dpls_phy6252_transport_disconnect(void *context);

void dpls_phy6252_transport_process_tx(void);
void dpls_phy6252_transport_tx_confirmed(void);
/* RC9 has no periodic transport tick. Runtime asks for the nearest absolute
 * monotonic deadline and wakes only when it is due. */
void dpls_phy6252_transport_check_deadlines(uint32 now_ms);
uint32 dpls_phy6252_transport_next_deadline_ms(uint32 now_ms);
bool dpls_phy6252_transport_tx_idle(void);

/* Единственный допустимый путь удаления bonds — явный физический factory reset.
 * Вызывать только после teardown link. */
bool dpls_phy6252_transport_factory_forget_bonds(void);

#endif
