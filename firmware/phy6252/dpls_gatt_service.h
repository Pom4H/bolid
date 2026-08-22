#ifndef DPLS_GATT_SERVICE_H
#define DPLS_GATT_SERVICE_H

#include "bcomdef.h"
#include "att.h"

/* RX callback returns an ATT status: SUCCESS (0) if the frame was accepted, or
 * ATT_ERR_INSUFFICIENT_RESOURCES if the queue is full so the client's Write
 * Request is NAK'd and Android retries instead of the frame being dropped. */
typedef uint8 (*dpls_gatt_rx_cb_t)(const uint8 *data, uint16 length);

bStatus_t dpls_gatt_add_service(dpls_gatt_rx_cb_t rx_callback);
/* SUCCESS means the ATT host accepted the PDU. Indications remain in flight
 * until ATT_HANDLE_VALUE_CFM. The Samsung-compatible notification path is
 * complete immediately on SUCCESS; there is no fixed pacing timeout in RC9. */
bStatus_t dpls_gatt_send_indication(uint16 conn_handle, const uint8 *data, uint16 length, uint8 task_id);
bool dpls_gatt_needs_confirmation(uint16 conn_handle);
bool dpls_gatt_subscribed(void);

/* IDENTIFY uses the confirmation of its semantic ACK as the physical phase-zero
 * boundary. The task suppresses LED rendering while this specific indication is
 * in flight, then consumes the marker on ATT_HANDLE_VALUE_CFM. */
bool dpls_gatt_identify_ack_pending(void);
bool dpls_gatt_take_identify_ack_confirmation(void);

#endif
