#define DPLS_PHY6252_SNV_GUARD_IMPL 1
#include "dpls_phy6252_app.h"
#include "dpls_phy6252_snv_guard.h"

#include "OSAL.h"
#include "log.h"
#include "osal_snv.h"
#include "watchdog.h"

#include <string.h>

/* Active-link persistence in Test-DPLS is deliberately tiny: one durable
 * settings record (94 bytes) or one auth-lock record (8 bytes). Keep exactly
 * one staged write so the radio-safety fix does not consume scarce PHY6252
 * MAIN SRAM. Normal product flow disconnects immediately after the protocol
 * reply drains; a second different write before that is rejected fail-closed. */
#define DPLS_SNV_DEFERRED_MAX_LEN 96u

typedef struct {
    bool pending;
    osalSnvId_t id;
    osalSnvLen_t len;
    uint8 data[DPLS_SNV_DEFERRED_MAX_LEN];
} dpls_deferred_snv_t;

static dpls_deferred_snv_t deferred;

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

bool dpls_phy6252_snv_pending(void)
{
    return deferred.pending;
}

bool dpls_phy6252_snv_disconnect_requested(void)
{
    return deferred.pending && dpls_phy6252_link_active();
}

bool dpls_phy6252_snv_flush_deferred(void)
{
    if (dpls_phy6252_link_active()) return false;
    if (!deferred.pending) return true;

    if (physical_write(deferred.id, deferred.len, deferred.data) != SUCCESS) {
        LOG("DPLS SNV deferred commit failed id=0x%02x\n", (unsigned)deferred.id);
        return false;
    }

    memset(deferred.data, 0, deferred.len);
    deferred.pending = false;
    deferred.len = 0u;
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
    if (!data || (uint16)len > DPLS_SNV_DEFERRED_MAX_LEN) return FAILURE;

    if (!dpls_phy6252_link_active()) {
        if (deferred.pending && !dpls_phy6252_snv_flush_deferred()) return FAILURE;
        return physical_write(id, len, data);
    }

    if (deferred.pending && deferred.id != id) {
        /* Never allocate a second RAM copy. The first commit already requests
         * a disconnect; the caller can retry the new operation after reconnect. */
        LOG("DPLS SNV second active-link write rejected id=0x%02x pending=0x%02x\n",
            (unsigned)id, (unsigned)deferred.id);
        return FAILURE;
    }

    deferred.pending = true;
    deferred.id = id;
    deferred.len = len;
    memcpy(deferred.data, data, len);
    LOG("DPLS SNV deferred id=0x%02x len=%u\n", (unsigned)id, (unsigned)len);
    return SUCCESS;
}
