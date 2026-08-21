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
#include "dpls_phy6252_runtime.h"
#include "simpleBLEPeripheral.h"
#include "pwrmgr.h"
#include "fs.h"

#define DEFAULT_MIN_CONN_INTERVAL 24
#define DEFAULT_MAX_CONN_INTERVAL 80
#define DEFAULT_CONN_TIMEOUT 3000
#define DPLS_TICK_MS 1000u
#define DPLS_TICK_IDLE_MS 5000u
#define DPLS_ADV_INTERVAL 800u

static uint8 app_task_id;

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

static void schedule_led_if_needed(void)
{
    uint32 next_ms = dpls_phy6252_runtime_led_tick();
    if (next_ms != 0u)
        osal_start_timerEx(app_task_id, SBP_DPLS_LED_EVT, next_ms);
}

static void state_changed(gaprole_States_t state)
{
    switch (state) {
    case GAPROLE_STARTED:
        /* Controller/HCI жив только здесь. Identity никогда не gate-ит radio. */
        dpls_ble_identity_on_stack_started();
        enable_advertising();
        osal_start_timerEx(app_task_id, SBP_DPLS_TICK_EVT, DPLS_TICK_IDLE_MS);
        schedule_led_if_needed();
        break;

    case GAPROLE_CONNECTED: {
        uint16 handle = INVALID_CONNHANDLE;
        GAPRole_GetParameter(GAPROLE_CONNHANDLE, &handle);
        (void)hal_pwrmgr_lock(MOD_USR0);
        dpls_phy6252_runtime_connected(handle);
        osal_start_timerEx(app_task_id, SBP_DPLS_TICK_EVT, DPLS_TICK_MS);
        break;
    }

    case GAPROLE_WAITING:
    case GAPROLE_WAITING_AFTER_TIMEOUT:
        dpls_phy6252_runtime_disconnected();
        (void)hal_pwrmgr_unlock(MOD_USR0);
        schedule_led_if_needed();
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
    /* Vendor GAPBondMgr требует callback registration; application state от
     * pairing callback не зависит и ключи отсюда никогда не стираются. */
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
    uint16 min_interval = DEFAULT_MIN_CONN_INTERVAL;
    uint16 max_interval = DEFAULT_MAX_CONN_INTERVAL;
    uint16 latency = 0;
    uint16 timeout = DEFAULT_CONN_TIMEOUT;
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

    hal_pwrmgr_RAM_retention(RET_SRAM0 | RET_SRAM1 | RET_SRAM2);
    hal_pwrmgr_RAM_retention_set();
    (void)hal_pwrmgr_LowCurrentLdo_enable();

    /* Три независимых sleep owner: link / силовые выходы / ADC-series. */
    (void)hal_pwrmgr_register(MOD_USR0, NULL, NULL);
    (void)hal_pwrmgr_register(MOD_USR1, NULL, NULL);
    (void)hal_pwrmgr_register(MOD_USR2, NULL, NULL);

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
                ((gattMsgEvent_t *)message)->method == ATT_HANDLE_VALUE_CFM)
                dpls_phy6252_runtime_tx_confirmed();
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
        schedule_led_if_needed();
        return events ^ DPLS_PHY6252_RX_EVT;
    }

    if (events & SBP_DPLS_TICK_EVT) {
        dpls_phy6252_runtime_tick();
        schedule_led_if_needed();
        osal_start_timerEx(app_task_id, SBP_DPLS_TICK_EVT,
                           dpls_phy6252_runtime_link_active() ? DPLS_TICK_MS : DPLS_TICK_IDLE_MS);
        return events ^ SBP_DPLS_TICK_EVT;
    }

    if (events & SBP_DPLS_LED_EVT) {
        schedule_led_if_needed();
        return events ^ SBP_DPLS_LED_EVT;
    }

    if (events & DPLS_PHY6252_TX_EVT) {
        dpls_phy6252_runtime_process_tx();
        return events ^ DPLS_PHY6252_TX_EVT;
    }

    if (events & DPLS_PHY6252_ADC_EVT) {
        dpls_phy6252_runtime_process_adc();
        return events ^ DPLS_PHY6252_ADC_EVT;
    }

    if (events & DPLS_PHY6252_STORAGE_EVT) {
        dpls_phy6252_runtime_process_storage();
        if (!dpls_phy6252_runtime_link_active() && !dpls_phy6252_runtime_flash_pending())
            enable_advertising();
        return events ^ DPLS_PHY6252_STORAGE_EVT;
    }

    return 0u;
}
