#ifndef DPLS_PHY6252_STORAGE_H
#define DPLS_PHY6252_STORAGE_H

#include "dpls_calib.h"
#include "dpls_server.h"
#include "types.h"

void dpls_phy6252_storage_init(void);
bool dpls_phy6252_storage_work_pending(void);

/* Только settings/auth требуют controlled disconnect. Journal ждёт естественный
 * disconnect и всё время активной BLE-сессии живёт в RAM. */
bool dpls_phy6252_storage_critical_pending(void);

/* Не более одной физической SNV-записи за OSAL turn. Radio state не хранится
 * здесь второй раз: runtime передаёт актуальный offline-факт явно. */
bool dpls_phy6252_storage_process_one(bool radio_offline);

dpls_settings_state_t dpls_phy6252_storage_settings_state(void *context);
void dpls_phy6252_storage_settings_salt(void *context, uint8_t out[DPLS_AUTH_SALT_SIZE]);
bool dpls_phy6252_storage_write_settings(void *context, const char *name,
                                         const uint8_t salt[16], const uint8_t verifier[32]);
void dpls_phy6252_storage_settings_name(void *context, char out[DPLS_NAME_MAX + 1u]);
bool dpls_phy6252_storage_set_name(void *context, const char *name);
bool dpls_phy6252_storage_set_password(void *context, const uint8_t salt[16],
                                       const uint8_t verifier[32]);
bool dpls_phy6252_storage_copy_verifier(uint8_t out[DPLS_AUTH_PROOF_SIZE]);

bool dpls_phy6252_storage_auth_lock_read(void *context);
bool dpls_phy6252_storage_auth_lock_write(void *context, bool locked);

bool dpls_phy6252_storage_events_init(void *context, uint16_t *count, uint32_t *next_sequence);
bool dpls_phy6252_storage_event_append(void *context, const dpls_event_t *event);
bool dpls_phy6252_storage_event_read(void *context, uint32_t sequence, dpls_event_t *event);

void dpls_phy6252_storage_load_calibration(dpls_calib_t *line, dpls_calib_t *vcap,
                                           bool *line_from_nv);

bool dpls_phy6252_storage_clear_settings(void);

/* Diagnostic image only: make the application boot descriptor invalid so the
 * next software reset is captured by the PHY6252 ROM UART programmer. */
bool dpls_phy6252_storage_prepare_rom_boot(void);

#endif
