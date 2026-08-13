#ifndef TEST_DPLS_SIMPLE_BLE_PERIPHERAL_H
#define TEST_DPLS_SIMPLE_BLE_PERIPHERAL_H

#include "types.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SBP_START_DEVICE_EVT 0x0001u
#define SBP_DPLS_TICK_EVT    0x0080u
#define SBP_DPLS_LED_EVT     0x0200u
#define SBP_DPLS_CONN_EVT    0x1000u

void SimpleBLEPeripheral_Init(uint8 task_id);
uint16 SimpleBLEPeripheral_ProcessEvent(uint8 task_id, uint16 events);

#ifdef __cplusplus
}
#endif

#endif
