#ifndef DPLS_GATT_SERVICE_H
#define DPLS_GATT_SERVICE_H

#include "bcomdef.h"
#include "att.h"

/* RX callback returns an ATT status: SUCCESS (0) if the frame was accepted, or
 * ATT_ERR_INSUFFICIENT_RESOURCES if the queue is full so the client's Write
 * Request is NAK'd and Android retries instead of the frame being dropped. */
typedef uint8 (*dpls_gatt_rx_cb_t)(const uint8 *data, uint16 length);

bStatus_t dpls_gatt_add_service(dpls_gatt_rx_cb_t rx_callback);
/* SUCCESS: the ATT PDU left the stack. If dpls_gatt_needs_confirmation() is
 * true, wait for ATT_HANDLE_VALUE_CFM (or the TX confirm timeout) before the
 * next frame; notifications do not confirm. Transient errors (blePending /
 * bleMemAllocError / MSG_BUFFER_NOT_AVAIL / bleNotConnected) mean retry;
 * ATT_ERR_INVALID_VALUE_SIZE is the only drop. */
bStatus_t dpls_gatt_send_indication(uint16 conn_handle, const uint8 *data, uint16 length, uint8 task_id);
/* True only for indicate-only CCCD. Samsung writes 0x03 then never confirms. */
bool dpls_gatt_needs_confirmation(uint16 conn_handle);
bool dpls_gatt_subscribed(void);

/* IDENTIFY uses the confirmation of its semantic ACK as the physical phase-zero
 * boundary. The task suppresses LED rendering while this specific indication is
 * in flight, then consumes the marker on ATT_HANDLE_VALUE_CFM. */
bool dpls_gatt_identify_ack_pending(void);
bool dpls_gatt_take_identify_ack_confirmation(void);

#endif
