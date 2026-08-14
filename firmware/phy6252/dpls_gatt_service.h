#ifndef DPLS_GATT_SERVICE_H
#define DPLS_GATT_SERVICE_H

#include "bcomdef.h"
#include "att.h"

/* RX callback returns an ATT status: SUCCESS (0) if the frame was accepted, or
 * ATT_ERR_INSUFFICIENT_RESOURCES if the queue is full so the client's Write
 * Request is NAK'd and Android retries instead of the frame being dropped. */
typedef uint8 (*dpls_gatt_rx_cb_t)(const uint8 *data, uint16 length);

bStatus_t dpls_gatt_add_service(dpls_gatt_rx_cb_t rx_callback);
/* Returns the GATT_Indication result: SUCCESS means it was accepted and an ATT
 * confirmation will follow; a transient error (MSG_BUFFER_NOT_AVAIL /
 * bleMemAllocError) means retry; anything else is permanent (not subscribed /
 * not connected / too big for the negotiated MTU). */
bStatus_t dpls_gatt_send_indication(uint16 conn_handle, const uint8 *data, uint16 length, uint8 task_id);
bool dpls_gatt_send_notification(uint16 conn_handle, const uint8 *data, uint16 length, uint8 task_id);
bool dpls_gatt_subscribed(void);

#endif
