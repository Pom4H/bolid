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
#include "dpls_ble_identity.h"
#include "dpls_phy6252_app.h"
#include "dpls_board.h"
#include "simpleBLEPeripheral.h"
#include "pwrmgr.h"
#include "fs.h"
#include "mcu.h"
#include "mcu_phy_bumbee.h"

#define DEFAULT_MIN_CONN_INTERVAL 24
#define DEFAULT_MAX_CONN_INTERVAL 80
#define DEFAULT_CONN_TIMEOUT 3000

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
    0x2f,0x4d,0x7a,0x5d,0x00,0x10,0x5f,0x7b,
    0x07, GAP_ADTYPE_MANUFACTURER_SPECIFIC,
    0x01,0x0b,
    0x00,0x00,0x00,0x00
};

static uint8 device_name[GAP_DEVICE_NAME_LEN] = "Test-DPLS-0000";

static void apply_identity_to_adv(void)
{
    static const char HEX[] = "0123456789ABCDEF";
    uint32 id = dpls_ble_identity_device_id();
    uint16 tag = (uint16)(id & 0xffffu);
    char suffix[4];
    uint8 i;

    suffix[0] = HEX[(tag >> 12) & 0xfu];
    suffix[1] = HEX[(tag >> 8) & 0xfu];
    suffix[2] = HEX[(tag >> 4) & 0xfu];
    suffix[3] = HEX[tag & 0xfu];
    for (i = 0; i < 4u; ++i) {
        scan_response[12 + i] = (uint8)suffix[i];
        device_name[10 + i] = (uint8)suffix[i];
    }
    device_name[14] = '\0';
    advertising_data[25] = (uint8)(id);
    advertising_data[26] = (uint8)(id >> 8);
    advertising_data[27] = (uint8)(id >> 16);
    advertising_data[28] = (uint8)(id >> 24);
}

static void state_changed(gaprole_States_t state)
{
    switch (state) {
    case GAPROLE_STARTED: {
        uint8 enabled = TRUE;
        dpls_ble_identity_on_stack_started();
        GAPRole_SetParameter(GAPROLE_ADVERT_ENABLED, sizeof(enabled), &enabled);
        osal_start_timerEx(app_task_id, SBP_DPLS_TICK_EVT, 200);
        osal_start_timerEx(app_task_id, SBP_DPLS_LED_EVT, 50);
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

static void bond_pair_state_cb(uint16 conn_handle, uint8 state, uint8 status)
{
    (void)conn_handle;
    if (state == GAPBOND_PAIRING_STATE_COMPLETE && status != SUCCESS) {
        GAPBondMgr_SetParameter(GAPBOND_ERASE_ALLBONDS, 0, NULL);
    }
}

/* P16/P17 are the module's XTAL_32K_IN/OUT pads, but this board carries no
 * 32.768 kHz crystal — the two pins are the KZ_2 and KZ_T shunt outputs. The
 * SDK selects CLK_32K_RCOSC (main.c) and then, outside the XOSC_PIN_ALLOW
 * guard we do not define, still runs "turn on 32kxtal" (clock.c), leaving the
 * crystal oscillator biased on two digital outputs for the whole run. Clearing
 * PMCTL0[28] puts the field back to the 0x05 the RCOSC branch itself
 * programmed; nothing else in the build reads a 32 kHz crystal. */
static void disable_32k_xtal(void)
{
    subWriteReg(&(AP_AON->PMCTL0), 28, 28, 0x00);
}

/* GPIO_OUTPUT only flips the DDR bit in this SDK; it does not pre-load the data
 * latch. If a retained/warm-boot latch contains 1, enabling an active-high mode
 * output before the later safe-normal write exposes a short pulse on the pin.
 * Prime every active-high control/LED latch low first. hal_gpio_write() updates
 * the latch before it enables output direction, so no unsafe level is exposed. */
static void prime_safe_gpio_outputs(void)
{
    hal_gpio_write(DPLS_PIN_ISO_1, 0);
    hal_gpio_write(DPLS_PIN_ISO_2, 0);
    hal_gpio_write(DPLS_PIN_ISO_T, 0);
    hal_gpio_write(DPLS_PIN_KZ_1, 0);
    hal_gpio_write(DPLS_PIN_KZ_2, 0);
    hal_gpio_write(DPLS_PIN_KZ_T, 0);
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);
}

static gapRolesCBs_t role_callbacks = { state_changed, rssi_changed };
static gapBondCBs_t bond_callbacks = { NULL, bond_pair_state_cb };

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
    uint8 bond_fail = GAPBOND_FAIL_TERMINATE_ERASE_BONDS;
    uint8 key_distribution = GAPBOND_KEYDIST_SENCKEY |
                             GAPBOND_KEYDIST_SIDKEY |
                             GAPBOND_KEYDIST_MENCKEY |
                             GAPBOND_KEYDIST_MIDKEY;

    app_task_id = task_id;
    /* hal_init() has already run by now, so undo its 32k-crystal bias here and
     * re-assert it on every wake — the AON domain survives sleep, but the ROM
     * wake path reprograms the 32 kHz source, so do not assume it stays off. */
    disable_32k_xtal();
    prime_safe_gpio_outputs();
    (void)hal_pwrmgr_register(MOD_USR2, NULL, disable_32k_xtal);
    /* dpls_phy6252_connected() already locks MOD_USR1 for the lifetime of an
     * active BLE connection. It used to be deliberately left unregistered,
     * making that lock a no-op and re-introducing the known ADC/radio/sleep race
     * (OSAL freeze / watchdog / GATT loss). Registering it here keeps advertising
     * low-power but prevents sleep only while the operator is connected. It also
     * prevents the ROM wake path from briefly re-biasing P16/P17 as 32 kHz XTAL
     * pins in the middle of an active control session. */
    (void)hal_pwrmgr_register(MOD_USR1, NULL, NULL);
    /* The pristine SDK 3.1.2 main.c retains only SRAM0 (0x1fff0000-0x1fff7fff)
     * across sleep, but our scatter places the ER_IROM1 tail past 0x1fff8000
     * (SRAM1) and ER_IROM2 at 0x1fffc000 (SRAM2). With those banks unpowered
     * every wakeup lands on dead code and turns into a warm reboot loop.
     * Re-assert the full-retention mask the proven 3.1.1 build used. */
    hal_pwrmgr_RAM_retention(RET_SRAM0 | RET_SRAM1 | RET_SRAM2);
    hal_pwrmgr_RAM_retention_set();
    /* osal_snv is fs-backed (USE_FS=1) and needs the fs region mounted before
     * the first read/write. The proven 3.1.1 main.c mounted it in hal_init();
     * the pristine 3.1.2 main.c does not, which leaves every SNV operation
     * failing (no BLE MAC, no settings, no journal persistence). Same region
     * as before: 3 sectors at 0x1103C000. */
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
        if (message) {
            osal_event_hdr_t *hdr = (osal_event_hdr_t *)message;
            if (hdr->event == GATT_MSG_EVENT &&
                ((gattMsgEvent_t *)message)->method == ATT_HANDLE_VALUE_CFM) {
                dpls_phy6252_tx_confirmed();
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
    if (events & SBP_DPLS_TICK_EVT) {
        dpls_phy6252_tick();
        osal_start_timerEx(app_task_id, SBP_DPLS_TICK_EVT, 200);
        return events ^ SBP_DPLS_TICK_EVT;
    }
    if (events & SBP_DPLS_LED_EVT) {
        uint32 next_ms = dpls_phy6252_led_tick();
        osal_start_timerEx(app_task_id, SBP_DPLS_LED_EVT, next_ms);
        return events ^ SBP_DPLS_LED_EVT;
    }
    if (events & DPLS_PHY6252_RX_EVT) {
        dpls_phy6252_process_rx();
        return events ^ DPLS_PHY6252_RX_EVT;
    }
    if (events & DPLS_PHY6252_TX_EVT) {
        dpls_phy6252_process_tx();
        return events ^ DPLS_PHY6252_TX_EVT;
    }
    if (events & DPLS_PHY6252_ADC_EVT) {
        dpls_phy6252_process_adc();
        return events ^ DPLS_PHY6252_ADC_EVT;
    }
    return 0;
}
