#ifndef TEST_DPLS_SIMPLE_BLE_PERIPHERAL_H
#define TEST_DPLS_SIMPLE_BLE_PERIPHERAL_H

#include "types.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SBP_START_DEVICE_EVT 0x0001u
#define SBP_DPLS_TIMER_EVT   0x0080u

void SimpleBLEPeripheral_Init(uint8 task_id);
uint16 SimpleBLEPeripheral_ProcessEvent(uint8 task_id, uint16 events);

#ifdef __cplusplus
}
#endif

#endif
