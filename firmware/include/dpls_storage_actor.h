#ifndef DPLS_STORAGE_ACTOR_H
#define DPLS_STORAGE_ACTOR_H

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    DPLS_STORAGE_RADIO = 0,
    DPLS_STORAGE_DRAINING,
    DPLS_STORAGE_FLASH,
    DPLS_STORAGE_FAULT,
} dpls_storage_phase_t;

typedef enum {
    DPLS_STORAGE_EVT_WRITE_REQUESTED = 0,
    DPLS_STORAGE_EVT_TX_IDLE,
    DPLS_STORAGE_EVT_LINK_DOWN,
    DPLS_STORAGE_EVT_COMMIT_OK,
    DPLS_STORAGE_EVT_COMMIT_RETRY,
    DPLS_STORAGE_EVT_COMMIT_FATAL,
    DPLS_STORAGE_EVT_RESET,
} dpls_storage_event_t;

typedef struct {
    dpls_storage_phase_t phase;
    bool pending;
    bool link_active;
} dpls_storage_actor_t;

typedef struct {
    bool request_disconnect;
    bool disable_advertising;
    bool commit;
    bool enable_advertising;
    bool fail_safe;
} dpls_storage_effects_t;

void dpls_storage_actor_init(dpls_storage_actor_t *actor, bool link_active);
dpls_storage_effects_t dpls_storage_actor_reduce(dpls_storage_actor_t *actor,
                                                  dpls_storage_event_t event);
bool dpls_storage_actor_flash_allowed(const dpls_storage_actor_t *actor);
bool dpls_storage_actor_advertising_allowed(const dpls_storage_actor_t *actor);

#endif
