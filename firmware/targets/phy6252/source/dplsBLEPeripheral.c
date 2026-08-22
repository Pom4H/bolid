#include "bcomdef.h"
#include "OSAL.h"
#include "gap.h"
#include "gapbondmgr.h"
#include "gapgattserver.h"
#include "gatt.h"
#include "gattservapp.h"
#include "linkdb.h"
#include "peripheral.h"
#include "ll.h"
#include "ll_common.h"
#include "dpls_ble_identity.h"
#include "dpls_phy6252_events.h"
#include "dpls_phy6252_power.h"
#include "dpls_phy6252_runtime.h"
#include "simpleBLEPeripheral.h"
#include "pwrmgr.h"
#include "fs.h"
#include "log.h"

/* BLE units are 1.25 ms for connection interval and 10 ms for supervision
 * timeout. ACTIVE keeps pairing/control responsive. IDLE allows up to ~600 ms
 * effective peripheral latency, still below the 1 s product control budget. */
#define DPLS_ACTIVE_MIN_CONN_INTERVAL 24u
#define DPLS_ACTIVE_MAX_CONN_INTERVAL 40u
#define DPLS_ACTIVE_SLAVE_LATENCY 0u
#define DPLS_IDLE_MIN_CONN_INTERVAL 96u
#define DPLS_IDLE_MAX_CONN_INTERVAL 120u
#define DPLS_IDLE_SLAVE_LATENCY 3u
#define DPLS_CONN_TIMEOUT 3000u
#define DPLS_ADV_INTERVAL 800u

static uint8 app_task_id;
static bool link_profile_known;
static dpls_link_profile_t applied_link_profile;

static uint8 scan_response[] = {
    0x0f, GAP_ADTYPE_LOCAL_NAME_COMPLETE,
    'T','e','s','t','-','D','P','L','S','-','0','0','0','0'
};

static uint8 advertising_data[] = {
    0x02, GAP_ADTYPE_FLAGS,
    GAP_ADTYPE_FLAGS_GENERAL | GAP_ADTYPE_FLAGS_BREDR_NOT_SUPPORTED,
    0x11, GAP_ADTYPE_128BIT_COMPLETE,
    0x01,0x00,0xf0,0xd5,0xb7,0x14,0x4c,0x9a,
    0x2f,0x4d,0x7a,0x5d,0x00,0x10,0x5f,0x7b
};

static uint8 device_name[GAP_DEVICE_NAME_LEN] = "Test-DPLS-0000";

static void apply_identity_to_adv(void)
{
    static const char HEX[] = "0123456789ABCDEF";
    uint16 tag = (uint16)(dpls_ble_identity_device_id() & 0xffffu);
    uint8 i;
    char suffix[4];

    suffix[0] = HEX[(tag >> 12) & 0xfu];
    suffix[1] = HEX[(tag >> 8) & 0xfu];
    suffix[2] = HEX[(tag >> 4) & 0xfu];
    suffix[3] = HEX[tag & 0xfu];
    for (i = 0u; i < 4u; ++i) {
        scan_response[12 + i] = (uint8)suffix[i];
        device_name[10 + i] = (uint8)suffix[i];
    }
    device_name[14] = '\0';
}

static void enable_advertising(void)
{
    uint8 enabled = TRUE;
    apply_identity_to_adv();
    GGS_SetParameter(GGS_DEVICE_NAME_ATT, GAP_DEVICE_NAME_LEN, device_name);
    GAPRole_SetParameter(GAPROLE_SCAN_RSP_DATA, sizeof(scan_response), scan_response);
    GAPRole_SetParameter(GAPROLE_ADVERT_ENABLED, sizeof(enabled), &enabled);
}

static void schedule_runtime_timer(void)
{
    uint32 next_ms = dpls_phy6252_runtime_next_wakeup_ms();
    /* There is exactly one application timer. LED edges, ADC cadence and every
     * semantic/transport deadline are merged by runtime. */
    (void)osal_stop_timerEx(app_task_id, SBP_DPLS_TIMER_EVT);
    if (next_ms != 0u)
        (void)osal_start_timerEx(app_task_id, SBP_DPLS_TIMER_EVT, next_ms);
}

static void apply_link_profile_if_needed(void)
{
    dpls_link_profile_t desired;
    uint16 min_interval;
    uint16 max_interval;
    uint16 latency;
    bStatus_t rc;

    if (!dpls_phy6252_runtime_link_active()) return;
    desired = dpls_phy6252_runtime_link_profile();
    if (link_profile_known && desired == applied_link_profile) return;

    if (desired == DPLS_LINK_PROFILE_IDLE) {
        min_interval = DPLS_IDLE_MIN_CONN_INTERVAL;
        max_interval = DPLS_IDLE_MAX_CONN_INTERVAL;
        latency = DPLS_IDLE_SLAVE_LATENCY;
    } else {
        min_interval = DPLS_ACTIVE_MIN_CONN_INTERVAL;
        max_interval = DPLS_ACTIVE_MAX_CONN_INTERVAL;
        latency = DPLS_ACTIVE_SLAVE_LATENCY;
    }

    rc = GAPRole_SendUpdateParam(min_interval, max_interval, latency,
                                 DPLS_CONN_TIMEOUT, GAPROLE_NO_ACTION);
    if (rc == SUCCESS || rc == bleAlreadyInRequestedMode) {
        applied_link_profile = desired;
        link_profile_known = true;
        LOG("DPLS BLE profile=%u min=%u max=%u lat=%u\n",
            (unsigned)desired, min_interval, max_interval, latency);
    } else {
        /* Transient host pressure is retried on the next real runtime event; no
         * dedicated polling timer exists for connection-parameter updates. */
        LOG("DPLS BLE profile retry=%u rc=%u\n", (unsigned)desired, rc);
    }
}

static void state_changed(gaprole_States_t state)
{
    switch (state) {
    case GAPROLE_STARTED:
        dpls_ble_identity_on_stack_started();
        enable_advertising();
        schedule_runtime_timer();
        break;

    case GAPROLE_CONNECTED: {
        uint16 handle = INVALID_CONNHANDLE;
        GAPRole_GetParameter(GAPROLE_CONNHANDLE, &handle);
        link_profile_known = false;
        dpls_phy6252_power_link_connected();
        dpls_phy6252_runtime_connected(handle);
        apply_link_profile_if_needed();
        schedule_runtime_timer();
        break;
    }

    case GAPROLE_WAITING:
    case GAPROLE_WAITING_AFTER_TIMEOUT:
        dpls_phy6252_runtime_disconnected();
        dpls_phy6252_power_link_disconnected();
        link_profile_known = false;
        schedule_runtime_timer();
        if (!dpls_phy6252_runtime_flash_pending()) enable_advertising();
        break;

    default:
        break;
    }
}

static void rssi_changed(int8 rssi)
{
    (void)rssi;
}

static void bond_pair_state_cb(uint16 conn_handle, uint8 state, uint8 status)
{
    (void)conn_handle;
    (void)state;
    (void)status;
}

static gapRolesCBs_t role_callbacks = { state_changed, rssi_changed };
static gapBondCBs_t bond_callbacks = { NULL, bond_pair_state_cb };

void SimpleBLEPeripheral_Init(uint8 task_id)
{
    uint8 advertising_enabled = FALSE;
    uint8 update_enabled = FALSE;
    uint8 channels = GAP_ADVCHAN_37 | GAP_ADVCHAN_38 | GAP_ADVCHAN_39;
    uint8 advertising_type = LL_ADV_CONNECTABLE_UNDIRECTED_EVT;
    uint16 advertising_off_time = 0;
    uint16 min_interval = DPLS_ACTIVE_MIN_CONN_INTERVAL;
    uint16 max_interval = DPLS_ACTIVE_MAX_CONN_INTERVAL;
    uint16 latency = DPLS_ACTIVE_SLAVE_LATENCY;
    uint16 timeout = DPLS_CONN_TIMEOUT;
    uint16 advertising_interval = DPLS_ADV_INTERVAL;
    uint32 passkey = 0;
    uint8 pairing_mode = GAPBOND_PAIRING_MODE_WAIT_FOR_REQ;
    uint8 mitm = FALSE;
    uint8 io_capability = GAPBOND_IO_CAP_NO_INPUT_NO_OUTPUT;
    uint8 bonding = TRUE;
    uint8 bond_fail = GAPBOND_FAIL_TERMINATE_LINK;
    uint8 key_distribution = GAPBOND_KEYDIST_SENCKEY |
                             GAPBOND_KEYDIST_SIDKEY |
                             GAPBOND_KEYDIST_MENCKEY |
                             GAPBOND_KEYDIST_MIDKEY;

    app_task_id = task_id;
    link_profile_known = false;

    hal_pwrmgr_RAM_retention(RET_SRAM0 | RET_SRAM1 | RET_SRAM2);
    hal_pwrmgr_RAM_retention_set();
    (void)hal_pwrmgr_LowCurrentLdo_enable();
    dpls_phy6252_power_init();

    if (!hal_fs_initialized())
        (void)hal_fs_init(0x1103C000u, 3);

    dpls_ble_identity_prepare();
    apply_identity_to_adv();

    (void)LL_EXT_SetSCA(500);
    GAP_SetParamValue(TGAP_CONN_PAUSE_PERIPHERAL, 2);
    GAPRole_SetParameter(GAPROLE_ADV_EVENT_TYPE, sizeof(advertising_type), &advertising_type);
    GAPRole_SetParameter(GAPROLE_ADV_CHANNEL_MAP, sizeof(channels), &channels);
    GAPRole_SetParameter(GAPROLE_ADVERT_ENABLED, sizeof(advertising_enabled), &advertising_enabled);
    GAPRole_SetParameter(GAPROLE_ADVERT_OFF_TIME, sizeof(advertising_off_time), &advertising_off_time);
    GAPRole_SetParameter(GAPROLE_SCAN_RSP_DATA, sizeof(scan_response), scan_response);
    GAPRole_SetParameter(GAPROLE_ADVERT_DATA, sizeof(advertising_data), advertising_data);
    /* Runtime owns profile changes; disable the vendor's independent automatic
     * timer so connection policy still has one semantic owner. */
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
    GAPBondMgr_SetParameter(GAPBOND_KEY_DIST_LIST, sizeof(key_distribution), &key_distribution);
    GAPBondMgr_SetParameter(GAPBOND_BOND_FAIL_ACTION, sizeof(bond_fail), &bond_fail);

    GGS_SetParameter(GGS_DEVICE_NAME_ATT, GAP_DEVICE_NAME_LEN, device_name);
    GGS_AddService(GATT_ALL_SERVICES);
    GATTServApp_AddService(GATT_ALL_SERVICES);
    dpls_phy6252_runtime_init(app_task_id);
    ATT_SetMTUSizeMax(247);
    llInitFeatureSetDLE(TRUE);
    osal_set_event(app_task_id, SBP_START_DEVICE_EVT);
}

uint16 SimpleBLEPeripheral_ProcessEvent(uint8 task_id, uint16 events)
{
    (void)task_id;

    if (events & SYS_EVENT_MSG) {
        uint8 *message = osal_msg_receive(app_task_id);
        if (message) {
            osal_event_hdr_t *hdr = (osal_event_hdr_t *)message;
            if (hdr->event == GATT_MSG_EVENT &&
                ((gattMsgEvent_t *)message)->method == ATT_HANDLE_VALUE_CFM) {
                dpls_phy6252_runtime_tx_confirmed();
                schedule_runtime_timer();
            }
            osal_msg_deallocate(message);
        }
        return events ^ SYS_EVENT_MSG;
    }

    if (events & SBP_START_DEVICE_EVT) {
        GAPRole_StartDevice(&role_callbacks);
        GAPBondMgr_Register(&bond_callbacks);
        return events ^ SBP_START_DEVICE_EVT;
    }

    if (events & DPLS_PHY6252_RX_EVT) {
        dpls_phy6252_runtime_process_rx();
        apply_link_profile_if_needed();
        schedule_runtime_timer();
        return events ^ DPLS_PHY6252_RX_EVT;
    }

    if (events & SBP_DPLS_TIMER_EVT) {
        dpls_phy6252_runtime_process_timer();
        apply_link_profile_if_needed();
        schedule_runtime_timer();
        return events ^ SBP_DPLS_TIMER_EVT;
    }

    if (events & DPLS_PHY6252_TX_EVT) {
        dpls_phy6252_runtime_process_tx();
        schedule_runtime_timer();
        return events ^ DPLS_PHY6252_TX_EVT;
    }

    if (events & DPLS_PHY6252_ADC_EVT) {
        dpls_phy6252_runtime_process_adc();
        apply_link_profile_if_needed();
        schedule_runtime_timer();
        return events ^ DPLS_PHY6252_ADC_EVT;
    }

    if (events & DPLS_PHY6252_STORAGE_EVT) {
        dpls_phy6252_runtime_process_storage();
        schedule_runtime_timer();
        if (!dpls_phy6252_runtime_link_active() && !dpls_phy6252_runtime_flash_pending())
            enable_advertising();
        return events ^ DPLS_PHY6252_STORAGE_EVT;
    }

    return 0u;
}
