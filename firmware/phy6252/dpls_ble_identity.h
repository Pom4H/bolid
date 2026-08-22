#ifndef DPLS_BLE_IDENTITY_H
#define DPLS_BLE_IDENTITY_H

#include "bcomdef.h"

#define DPLS_FACTORY_IDENTITY_FLASH_ADDR 0x1103F000u
#define DPLS_FACTORY_IDENTITY_RECORD_SIZE 64u

/* Ранний boot path только читает неизменяемые данные. Никаких SNV/TRNG/HCI. */
void dpls_ble_identity_prepare(void);

/* Единственная controller-команда identity выполняется после GAPROLE_STARTED. */
void dpls_ble_identity_on_stack_started(void);

/* Серийный номер DID1, если запись валидна; иначе стабильный fallback из chip MAC. */
uint32_t dpls_ble_identity_device_id(void);

#endif
