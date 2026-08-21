#define DPLS_PHY6252_SNV_GUARD_IMPL 1
#include "dpls_phy6252_app.h"
#include "dpls_phy6252_snv_guard.h"

#include "dpls_storage_actor.h"
#include "OSAL.h"
#include "log.h"
#include "osal_snv.h"
#include "watchdog.h"

#include <string.h>

/* Active-link persistence is intentionally one transaction. The storage actor
 * owns radio -> drain -> flash -> radio ordering; this adapter owns only bytes. */
#define DPLS_SNV_DEFERRED_MAX_LEN 96u

typedef struct {
    bool pending;
    osalSnvId_t id;
    osalSnvLen_t len;
    uint8 data[DPLS_SNV_DEFERRED_MAX_LEN];
} dpls_deferred_snv_t;

static dpls_deferred_snv_t deferred;
static dpls_storage_actor_t storage_actor;

static uint8 physical_write(osalSnvId_t id, osalSnvLen_t len, void *data)
{
    uint8 rc;
    (void)watchdog_config(WDG_8S);
    hal_watchdog_feed();
    rc = osal_snv_write(id, len, data);
    hal_watchdog_feed();
    (void)watchdog_config(WDG_2S);
    return rc;
}

static void sync_link_state(void)
{
    storage_actor.link_active = dpls_phy6252_link_active();
}

bool dpls_phy6252_snv_pending(void)
{
    return deferred.pending;
}

bool dpls_phy6252_snv_disconnect_requested(void)
{
    return deferred.pending && storage_actor.phase == DPLS_STORAGE_DRAINING;
}

bool dpls_phy6252_snv_flush_deferred(void)
{
    dpls_storage_effects_t fx;
    if (dpls_phy6252_link_active()) return false;
    sync_link_state();

    if (!deferred.pending) {
        storage_actor.pending = false;
        storage_actor.phase = DPLS_STORAGE_RADIO;
        return true;
    }

    fx = dpls_storage_actor_reduce(&storage_actor, DPLS_STORAGE_EVT_LINK_DOWN);
    if (!fx.commit || !dpls_storage_actor_flash_allowed(&storage_actor)) return false;

    if (physical_write(deferred.id, deferred.len, deferred.data) != SUCCESS) {
        LOG("DPLS SNV deferred commit failed id=0x%02x\n", (unsigned)deferred.id);
        (void)dpls_storage_actor_reduce(&storage_actor, DPLS_STORAGE_EVT_COMMIT_RETRY);
        return false;
    }

    memset(deferred.data, 0, deferred.len);
    deferred.pending = false;
    deferred.len = 0u;
    (void)dpls_storage_actor_reduce(&storage_actor, DPLS_STORAGE_EVT_COMMIT_OK);
    return true;
}

uint8 dpls_phy6252_snv_read_guarded(osalSnvId_t id, osalSnvLen_t len, void *data)
{
    if (!data) return FAILURE;

    if (deferred.pending && deferred.id == id) {
        if (len > deferred.len) return FAILURE;
        memcpy(data, deferred.data, len);
        return SUCCESS;
    }

    if (!dpls_phy6252_link_active() && deferred.pending &&
        !dpls_phy6252_snv_flush_deferred()) {
        return FAILURE;
    }
    return osal_snv_read(id, len, data);
}

uint8 dpls_phy6252_snv_write_guarded(osalSnvId_t id, osalSnvLen_t len, void *data)
{
    dpls_storage_effects_t fx;
    if (!data || (uint16)len > DPLS_SNV_DEFERRED_MAX_LEN) return FAILURE;

    if (!dpls_phy6252_link_active()) {
        if (deferred.pending && !dpls_phy6252_snv_flush_deferred()) return FAILURE;
        return osal_snv_write(id, len, data);
    }

    if (deferred.pending && deferred.id != id) {
        LOG("DPLS storage actor rejected concurrent write id=0x%02x pending=0x%02x\n",
            (unsigned)id, (unsigned)deferred.id);
        return FAILURE;
    }

    deferred.pending = true;
    deferred.id = id;
    deferred.len = len;
    memcpy(deferred.data, data, len);

    sync_link_state();
    fx = dpls_storage_actor_reduce(&storage_actor, DPLS_STORAGE_EVT_WRITE_REQUESTED);
    if (!fx.request_disconnect || storage_actor.phase != DPLS_STORAGE_DRAINING) {
        memset(deferred.data, 0, deferred.len);
        deferred.pending = false;
        deferred.len = 0u;
        return FAILURE;
    }

    LOG("DPLS storage staged id=0x%02x len=%u\n", (unsigned)id, (unsigned)len);
    return SUCCESS;
}
