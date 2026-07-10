#ifndef DPLS_GATT_SERVICE_H
#define DPLS_GATT_SERVICE_H

#include "bcomdef.h"
#include "att.h"

typedef void (*dpls_gatt_rx_cb_t)(const uint8 *data, uint16 length);

bStatus_t dpls_gatt_add_service(dpls_gatt_rx_cb_t rx_callback);
bool dpls_gatt_send_indication(uint16 conn_handle, const uint8 *data, uint16 length, uint8 task_id);
bool dpls_gatt_send_notification(uint16 conn_handle, const uint8 *data, uint16 length, uint8 task_id);
bool dpls_gatt_subscribed(void);

#endif
