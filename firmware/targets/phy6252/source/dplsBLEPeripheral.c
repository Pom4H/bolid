#include "bcomdef.h"
#include "OSAL.h"
#include "gap.h"
#include "gapgattserver.h"
#include "gattservapp.h"
#include "peripheral.h"
#include "ll.h"
#include "simpleBLEPeripheral.h"
#include "pwrmgr.h"

/*
 * Диагностическая прошивка для отделения проблем приложения от проблем
 * PHY6252/загрузки. Здесь намеренно нет DPLS runtime, SNV, factory identity,
 * ADC, bonding и пользовательского GATT-сервиса.
 *
 * Если плата с этим файлом появляется как BOLID-BOOT-PROBE, то reset,
 * application image, OSAL, GAP и радио исправны. Тогда неисправность выше —
 * в DPLS boot path или persistent flash.
 */

#define DPLS_PROBE_ADV_INTERVAL 160u /* 100 мс, единица GAP = 0.625 мс. */

static uint8 app_task_id;

static uint8 scan_response[] = {
    0x11, GAP_ADTYPE_LOCAL_NAME_COMPLETE,
    'B','O','L','I','D','-','B','O','O','T','-','P','R','O','B','E'
};

static uint8 advertising_data[] = {
    0x02, GAP_ADTYPE_FLAGS,
    GAP_ADTYPE_FLAGS_GENERAL | GAP_ADTYPE_FLAGS_BREDR_NOT_SUPPORTED,
    0x11, GAP_ADTYPE_128BIT_COMPLETE,
    0x01,0x00,0xf0,0xd5,0xb7,0x14,0x4c,0x9a,
    0x2f,0x4d,0x7a,0x5d,0x00,0x10,0x5f,0x7b
};

static void state_changed(gaprole_States_t state)
{
    (void)state;
}

static void rssi_changed(int8 rssi)
{
    (void)rssi;
}

static gapRolesCBs_t role_callbacks = { state_changed, rssi_changed };

void SimpleBLEPeripheral_Init(uint8 task_id)
{
    uint8 advertising_enabled = TRUE;
    uint8 channels = GAP_ADVCHAN_37 | GAP_ADVCHAN_38 | GAP_ADVCHAN_39;
    uint8 advertising_type = LL_ADV_CONNECTABLE_UNDIRECTED_EVT;
    uint16 advertising_off_time = 0u;
    uint16 advertising_interval = DPLS_PROBE_ADV_INTERVAL;

    app_task_id = task_id;

    /* Исключаем известный класс hardware-only ошибок сна из эксперимента. */
    hal_pwrmgr_RAM_retention(RET_SRAM0 | RET_SRAM1 | RET_SRAM2);
    hal_pwrmgr_RAM_retention_set();
    (void)hal_pwrmgr_LowCurrentLdo_enable();
    (void)hal_pwrmgr_register(MOD_USR1, NULL, NULL);
    hal_pwrmgr_lock(MOD_USR1);

    GAPRole_SetParameter(GAPROLE_ADV_EVENT_TYPE,
                         sizeof(advertising_type), &advertising_type);
    GAPRole_SetParameter(GAPROLE_ADV_CHANNEL_MAP,
                         sizeof(channels), &channels);
    GAPRole_SetParameter(GAPROLE_ADVERT_OFF_TIME,
                         sizeof(advertising_off_time), &advertising_off_time);
    GAPRole_SetParameter(GAPROLE_SCAN_RSP_DATA,
                         sizeof(scan_response), scan_response);
    GAPRole_SetParameter(GAPROLE_ADVERT_DATA,
                         sizeof(advertising_data), advertising_data);
    GAPRole_SetParameter(GAPROLE_ADVERT_ENABLED,
                         sizeof(advertising_enabled), &advertising_enabled);

    GAP_SetParamValue(TGAP_GEN_DISC_ADV_INT_MIN, advertising_interval);
    GAP_SetParamValue(TGAP_GEN_DISC_ADV_INT_MAX, advertising_interval);

    GGS_AddService(GATT_ALL_SERVICES);
    GATTServApp_AddService(GATT_ALL_SERVICES);

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
        return events ^ SBP_START_DEVICE_EVT;
    }

    return 0u;
}
