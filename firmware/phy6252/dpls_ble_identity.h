#ifndef DPLS_BLE_IDENTITY_H
#define DPLS_BLE_IDENTITY_H

#include "bcomdef.h"

/* Подготовка identity выполняется до GAPRole_StartDevice(). */
void dpls_ble_identity_prepare(void);

/* После GAP_DeviceInit синхронизируем identity/RPA со стеком. */
void dpls_ble_identity_on_stack_started(void);

/* Сбрасывает bonding keys, MAC сохраняется. После вызова нужен reboot. */
void dpls_ble_identity_reset_bonding_keys(void);

/* Стабильный 32-bit device id выводится из identity MAC. */
uint32_t dpls_ble_identity_device_id(void);

#endif
