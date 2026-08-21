#include "dpls_storage_actor.h"

#include <string.h>

void dpls_storage_actor_init(dpls_storage_actor_t *actor, bool link_active)
{
    memset(actor, 0, sizeof(*actor));
    actor->link_active = link_active;
    actor->phase = link_active ? DPLS_STORAGE_RADIO : DPLS_STORAGE_RADIO;
}

dpls_storage_effects_t dpls_storage_actor_reduce(dpls_storage_actor_t *actor,
                                                  dpls_storage_event_t event)
{
    dpls_storage_effects_t fx;
    memset(&fx, 0, sizeof(fx));

    if (event == DPLS_STORAGE_EVT_RESET) {
        dpls_storage_actor_init(actor, false);
        fx.enable_advertising = true;
        return fx;
    }

    if (actor->phase == DPLS_STORAGE_FAULT) {
        fx.disable_advertising = true;
        fx.fail_safe = true;
        return fx;
    }

    switch (event) {
    case DPLS_STORAGE_EVT_WRITE_REQUESTED:
        actor->pending = true;
        if (actor->link_active) {
            actor->phase = DPLS_STORAGE_DRAINING;
            fx.request_disconnect = true;
        } else {
            actor->phase = DPLS_STORAGE_FLASH;
            fx.disable_advertising = true;
            fx.commit = true;
        }
        break;

    case DPLS_STORAGE_EVT_TX_IDLE:
        if (actor->pending && actor->link_active && actor->phase == DPLS_STORAGE_DRAINING) {
            fx.request_disconnect = true;
        }
        break;

    case DPLS_STORAGE_EVT_LINK_DOWN:
        actor->link_active = false;
        if (actor->pending) {
            actor->phase = DPLS_STORAGE_FLASH;
            fx.disable_advertising = true;
            fx.commit = true;
        } else {
            actor->phase = DPLS_STORAGE_RADIO;
            fx.enable_advertising = true;
        }
        break;

    case DPLS_STORAGE_EVT_COMMIT_OK:
        actor->pending = false;
        actor->phase = DPLS_STORAGE_RADIO;
        if (!actor->link_active) fx.enable_advertising = true;
        break;

    case DPLS_STORAGE_EVT_COMMIT_RETRY:
        if (actor->pending && !actor->link_active) {
            actor->phase = DPLS_STORAGE_FLASH;
            fx.disable_advertising = true;
            fx.commit = true;
        }
        break;

    case DPLS_STORAGE_EVT_COMMIT_FATAL:
        actor->phase = DPLS_STORAGE_FAULT;
        fx.disable_advertising = true;
        fx.fail_safe = true;
        break;

    default:
        break;
    }
    return fx;
}

bool dpls_storage_actor_flash_allowed(const dpls_storage_actor_t *actor)
{
    return actor->phase == DPLS_STORAGE_FLASH && actor->pending && !actor->link_active;
}

bool dpls_storage_actor_advertising_allowed(const dpls_storage_actor_t *actor)
{
    return actor->phase == DPLS_STORAGE_RADIO && !actor->pending && !actor->link_active;
}
