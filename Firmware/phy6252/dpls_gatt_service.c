/* PHY62XX SDK 3.1.2 GATT adapter for the portable DPLS server core.
 * All RX writes require an encrypted link. */
#include "dpls_gatt_service.h"
#include "OSAL.h"
#include "linkdb.h"
#include "gatt.h"
#include "gatt_uuid.h"
#include "gattservapp.h"
#include <string.h>

#define DPLS_ATTR_COUNT 6
#define DPLS_RX_VALUE_INDEX 2
#define DPLS_TX_VALUE_INDEX 4

static const uint8 dpls_service_uuid[ATT_UUID_SIZE] = {0x01,0x00,0xf0,0xd5,0xb7,0x14,0x4c,0x9a,0x2f,0x4d,0x7a,0x5d,0x00,0x10,0x5f,0x7b};
static const uint8 dpls_rx_uuid[ATT_UUID_SIZE]      = {0x01,0x00,0xf0,0xd5,0xb7,0x14,0x4c,0x9a,0x2f,0x4d,0x7a,0x5d,0x01,0x10,0x5f,0x7b};
static const uint8 dpls_tx_uuid[ATT_UUID_SIZE]      = {0x01,0x00,0xf0,0xd5,0xb7,0x14,0x4c,0x9a,0x2f,0x4d,0x7a,0x5d,0x02,0x10,0x5f,0x7b};
static gattAttrType_t service_type = {ATT_UUID_SIZE, (uint8 *)dpls_service_uuid};
static uint8 rx_properties = GATT_PROP_WRITE;
static uint8 tx_properties = GATT_PROP_INDICATE | GATT_PROP_NOTIFY;
static uint8 rx_value;
static uint8 tx_value;
/* The PHY62XX helpers always iterate GATT_MAX_NUM_CONN entries (which also
 * includes the loopback slot). A scalar here corrupts adjacent static data
 * after reconnects and eventually makes ATT writes fail with 0x0e. */
static gattCharCfg_t tx_cccd[GATT_MAX_NUM_CONN];
static dpls_gatt_rx_cb_t app_rx;

static uint8 read_cb(uint16 conn, gattAttribute_t *attr, uint8 *value, uint16 *len, uint16 offset, uint8 max_len);
static bStatus_t write_cb(uint16 conn, gattAttribute_t *attr, uint8 *value, uint16 len, uint16 offset);
static void connection_cb(uint16 conn, uint8 change);

static gattAttribute_t attrs[DPLS_ATTR_COUNT] = {
    {{ATT_BT_UUID_SIZE, primaryServiceUUID}, GATT_PERMIT_READ, 0, (uint8 *)&service_type},
    {{ATT_BT_UUID_SIZE, characterUUID}, GATT_PERMIT_READ, 0, &rx_properties},
    {{ATT_UUID_SIZE, (uint8 *)dpls_rx_uuid}, GATT_PERMIT_WRITE | GATT_PERMIT_ENCRYPT_WRITE, 0, &rx_value},
    {{ATT_BT_UUID_SIZE, characterUUID}, GATT_PERMIT_READ, 0, &tx_properties},
    {{ATT_UUID_SIZE, (uint8 *)dpls_tx_uuid}, 0, 0, &tx_value},
    {{ATT_BT_UUID_SIZE, clientCharCfgUUID}, GATT_PERMIT_READ | GATT_PERMIT_WRITE, 0, (uint8 *)&tx_cccd}
};

CONST gattServiceCBs_t callbacks = {read_cb, write_cb, NULL};

bStatus_t dpls_gatt_add_service(dpls_gatt_rx_cb_t rx_callback) {
    app_rx = rx_callback;
    GATTServApp_InitCharCfg(INVALID_CONNHANDLE, tx_cccd);
    linkDB_Register(connection_cb);
    return GATTServApp_RegisterService(attrs, GATT_NUM_ATTRS(attrs), &callbacks);
}

static uint8 read_cb(uint16 conn, gattAttribute_t *attr, uint8 *value, uint16 *len, uint16 offset, uint8 max_len) {
    (void)conn; (void)offset; (void)max_len;
    if (osal_memcmp(attr->type.uuid, clientCharCfgUUID, ATT_BT_UUID_SIZE)) {
        *len = 2; osal_memcpy(value, attr->pValue, 2); return SUCCESS;
    }
    return ATT_ERR_ATTR_NOT_FOUND;
}

static bStatus_t write_cb(uint16 conn, gattAttribute_t *attr, uint8 *value, uint16 len, uint16 offset) {
    if (osal_memcmp(attr->type.uuid, clientCharCfgUUID, ATT_BT_UUID_SIZE))
        return GATTServApp_ProcessCCCWriteReq(conn, attr, value, len, offset, GATT_CLIENT_CFG_NOTIFY | GATT_CLIENT_CFG_INDICATE);
    if (attr->handle == attrs[DPLS_RX_VALUE_INDEX].handle && osal_memcmp(attr->type.uuid, dpls_rx_uuid, ATT_UUID_SIZE)) {
        if (offset != 0) return ATT_ERR_ATTR_NOT_LONG;
        /* Propagate the application's backpressure: a full RX queue returns
         * ATT_ERR_INSUFFICIENT_RESOURCES so the client retries the write rather
         * than the frame being silently dropped. */
        return app_rx ? app_rx(value, len) : SUCCESS;
    }
    return ATT_ERR_ATTR_NOT_FOUND;
}

static void connection_cb(uint16 conn, uint8 change) {
    if (conn != LOOPBACK_CONNHANDLE && (change == LINKDB_STATUS_UPDATE_REMOVED ||
        (change == LINKDB_STATUS_UPDATE_STATEFLAGS && !linkDB_Up(conn)))) {
        GATTServApp_InitCharCfg(conn, tx_cccd);
    }
}

bool dpls_gatt_subscribed(void) {
    uint8 i;
    for (i = 0; i < GATT_MAX_NUM_CONN; ++i)
        if (tx_cccd[i].connHandle != INVALID_CONNHANDLE && tx_cccd[i].value != GATT_CFG_NO_OPERATION) return true;
    return false;
}

bStatus_t dpls_gatt_send_indication(uint16 conn, const uint8 *data, uint16 length, uint8 task_id) {
    attHandleValueInd_t ind;
    /* Gate on the CCCD and the NEGOTIATED MTU (not the compile-time maximum), so
     * a frame that would not fit the current link is rejected here instead of
     * being truncated on the air. */
    if (!(GATTServApp_ReadCharCfg(conn, tx_cccd) & GATT_CLIENT_CFG_INDICATE)) return bleNotConnected;
    if (length + 3u > ATT_GetCurrentMTUSize(conn)) return ATT_ERR_INVALID_VALUE_SIZE;
    ind.handle = attrs[DPLS_TX_VALUE_INDEX].handle; ind.len = length; osal_memcpy(ind.value, data, length);
    return GATT_Indication(conn, &ind, FALSE, task_id);
}

bool dpls_gatt_send_notification(uint16 conn, const uint8 *data, uint16 length, uint8 task_id) {
    attHandleValueNoti_t noti;
    (void)task_id;
    if (!(GATTServApp_ReadCharCfg(conn, tx_cccd) & GATT_CLIENT_CFG_NOTIFY) ||
        length + 3u > ATT_GetCurrentMTUSize(conn)) return false;
    noti.handle = attrs[DPLS_TX_VALUE_INDEX].handle; noti.len = length; osal_memcpy(noti.value, data, length);
    return GATT_Notification(conn, &noti, FALSE) == SUCCESS;
}
