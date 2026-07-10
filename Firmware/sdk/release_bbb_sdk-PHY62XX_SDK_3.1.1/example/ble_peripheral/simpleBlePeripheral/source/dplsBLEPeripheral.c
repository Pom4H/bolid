#include "bcomdef.h"
#include "OSAL.h"
#include "gap.h"
#include "gapbondmgr.h"
#include "gapgattserver.h"
#include "gatt.h"
#include "gattservapp.h"
#include "peripheral.h"
#include "ll.h"
#include "ll_common.h"
#include "linkdb.h"
#include "dpls_phy6252_app.h"
#include "simpleBLEPeripheral.h"

#define DEFAULT_MIN_CONN_INTERVAL 24
#define DEFAULT_MAX_CONN_INTERVAL 80
#define DEFAULT_CONN_TIMEOUT 3000

static uint8 app_task_id;

static uint8 scan_response[] = {
    0x10, GAP_ADTYPE_LOCAL_NAME_COMPLETE,
    'T','e','s','t','-','D','P','L','S','-','P','B','0','3','F'
};

static uint8 advertising_data[] = {
    0x02, GAP_ADTYPE_FLAGS,
    GAP_ADTYPE_FLAGS_GENERAL | GAP_ADTYPE_FLAGS_BREDR_NOT_SUPPORTED,
    0x11, GAP_ADTYPE_128BIT_COMPLETE,
    0x01,0x00,0xf0,0xd5,0xb7,0x14,0x4c,0x9a,
    0x2f,0x4d,0x7a,0x5d,0x00,0x10,0x5f,0x7b,
    0x07, GAP_ADTYPE_MANUFACTURER_SPECIFIC,
    0x01,0x0b, 0x0f,0x03,0x00,0x00
};

static uint8 device_name[GAP_DEVICE_NAME_LEN] = "Test-DPLS-PB03F";

static void state_changed(gaprole_States_t state)
{
    switch (state) {
    case GAPROLE_STARTED: {
        uint8 enabled = TRUE;
        GAPRole_SetParameter(GAPROLE_ADVERT_ENABLED, sizeof(enabled), &enabled);
        osal_start_timerEx(app_task_id, SBP_DPLS_TICK_EVT, 200);
        break;
    }
    case GAPROLE_CONNECTED: {
        uint16 handle = INVALID_CONNHANDLE;
        GAPRole_GetParameter(GAPROLE_CONNHANDLE, &handle);
        dpls_phy6252_connected(handle);
        break;
    }
    case GAPROLE_WAITING:
    case GAPROLE_WAITING_AFTER_TIMEOUT: {
        uint8 enabled = TRUE;
        dpls_phy6252_disconnected();
        GAPRole_SetParameter(GAPROLE_ADVERT_ENABLED, sizeof(enabled), &enabled);
        break;
    }
    default:
        break;
    }
}

static void rssi_changed(int8 rssi) { (void)rssi; }

static gapRolesCBs_t role_callbacks = { state_changed, rssi_changed };
static gapBondCBs_t bond_callbacks = { NULL, NULL };

void SimpleBLEPeripheral_Init(uint8 task_id)
{
    uint8 advertising_enabled = FALSE;
    uint8 update_enabled = TRUE;
    uint8 channels = GAP_ADVCHAN_37 | GAP_ADVCHAN_38 | GAP_ADVCHAN_39;
    uint8 advertising_type = LL_ADV_CONNECTABLE_UNDIRECTED_EVT;
    uint16 advertising_off_time = 0;
    uint16 min_interval = DEFAULT_MIN_CONN_INTERVAL;
    uint16 max_interval = DEFAULT_MAX_CONN_INTERVAL;
    uint16 latency = 0;
    uint16 timeout = DEFAULT_CONN_TIMEOUT;
    uint16 advertising_interval = 320;
    uint32 passkey = 0;
    uint8 pairing_mode = GAPBOND_PAIRING_MODE_WAIT_FOR_REQ;
    uint8 mitm = FALSE;
    uint8 io_capability = GAPBOND_IO_CAP_NO_INPUT_NO_OUTPUT;
    uint8 bonding = TRUE;

    app_task_id = task_id;
    (void)LL_EXT_SetSCA(500);
    GAP_SetParamValue(TGAP_CONN_PAUSE_PERIPHERAL, 2);
    GAPRole_SetParameter(GAPROLE_ADV_EVENT_TYPE, sizeof(advertising_type), &advertising_type);
    GAPRole_SetParameter(GAPROLE_ADV_CHANNEL_MAP, sizeof(channels), &channels);
    GAPRole_SetParameter(GAPROLE_ADVERT_ENABLED, sizeof(advertising_enabled), &advertising_enabled);
    GAPRole_SetParameter(GAPROLE_ADVERT_OFF_TIME, sizeof(advertising_off_time), &advertising_off_time);
    GAPRole_SetParameter(GAPROLE_SCAN_RSP_DATA, sizeof(scan_response), scan_response);
    GAPRole_SetParameter(GAPROLE_ADVERT_DATA, sizeof(advertising_data), advertising_data);
    GAPRole_SetParameter(GAPROLE_PARAM_UPDATE_ENABLE, sizeof(update_enabled), &update_enabled);
    GAPRole_SetParameter(GAPROLE_MIN_CONN_INTERVAL, sizeof(min_interval), &min_interval);
    GAPRole_SetParameter(GAPROLE_MAX_CONN_INTERVAL, sizeof(max_interval), &max_interval);
    GAPRole_SetParameter(GAPROLE_SLAVE_LATENCY, sizeof(latency), &latency);
    GAPRole_SetParameter(GAPROLE_TIMEOUT_MULTIPLIER, sizeof(timeout), &timeout);
    GAP_SetParamValue(TGAP_GEN_DISC_ADV_INT_MIN, advertising_interval);
    GAP_SetParamValue(TGAP_GEN_DISC_ADV_INT_MAX, advertising_interval);

    GAPBondMgr_SetParameter(GAPBOND_DEFAULT_PASSCODE, sizeof(passkey), &passkey);
    GAPBondMgr_SetParameter(GAPBOND_PAIRING_MODE, sizeof(pairing_mode), &pairing_mode);
    GAPBondMgr_SetParameter(GAPBOND_MITM_PROTECTION, sizeof(mitm), &mitm);
    GAPBondMgr_SetParameter(GAPBOND_IO_CAPABILITIES, sizeof(io_capability), &io_capability);
    GAPBondMgr_SetParameter(GAPBOND_BONDING_ENABLED, sizeof(bonding), &bonding);

    GGS_SetParameter(GGS_DEVICE_NAME_ATT, GAP_DEVICE_NAME_LEN, device_name);
    GGS_AddService(GATT_ALL_SERVICES);
    GATTServApp_AddService(GATT_ALL_SERVICES);
    dpls_phy6252_init(app_task_id);
    ATT_SetMTUSizeMax(247);
    llInitFeatureSetDLE(TRUE);
    osal_set_event(app_task_id, SBP_START_DEVICE_EVT);
}

uint16 SimpleBLEPeripheral_ProcessEvent(uint8 task_id, uint16 events)
{
    (void)task_id;
    if (events & SYS_EVENT_MSG) {
        uint8 *message = osal_msg_receive(app_task_id);
        if (message) osal_msg_deallocate(message);
        return events ^ SYS_EVENT_MSG;
    }
    if (events & SBP_START_DEVICE_EVT) {
        GAPRole_StartDevice(&role_callbacks);
        GAPBondMgr_Register(&bond_callbacks);
        return events ^ SBP_START_DEVICE_EVT;
    }
    if (events & SBP_DPLS_TICK_EVT) {
        dpls_phy6252_tick();
        osal_start_timerEx(app_task_id, SBP_DPLS_TICK_EVT, 200);
        return events ^ SBP_DPLS_TICK_EVT;
    }
    if (events & DPLS_PHY6252_RX_EVT) {
        dpls_phy6252_process_rx();
        return events ^ DPLS_PHY6252_RX_EVT;
    }
    return 0;
}
