#define DPLS_PHY6252_SNV_GUARD_IMPL 1
#include "dpls_phy6252_app.h"
#include "dpls_phy6252_snv_guard.h"

#include "OSAL.h"
#include "log.h"
#include "osal_snv.h"
#include "watchdog.h"

#include <string.h>

#define DPLS_SNV_DEFERRED_DEPTH 6u
#define DPLS_SNV_DEFERRED_MAX_LEN 128u

typedef struct {
    osalSnvId_t id;
    osalSnvLen_t len;
    uint8 data[DPLS_SNV_DEFERRED_MAX_LEN];
} dpls_deferred_snv_t;

static dpls_deferred_snv_t deferred[DPLS_SNV_DEFERRED_DEPTH];
static uint8 deferred_count;
static bool disconnect_requested;

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

static int pending_index(osalSnvId_t id)
{
    int i;
    for (i = (int)deferred_count - 1; i >= 0; --i) {
        if (deferred[i].id == id) return i;
    }
    return -1;
}

bool dpls_phy6252_snv_pending(void)
{
    return deferred_count != 0u;
}

bool dpls_phy6252_snv_disconnect_requested(void)
{
    return disconnect_requested;
}

bool dpls_phy6252_snv_flush_deferred(void)
{
    if (dpls_phy6252_link_active()) return false;

    while (deferred_count != 0u) {
        if (physical_write(deferred[0].id, deferred[0].len, deferred[0].data) != SUCCESS) {
            LOG("DPLS SNV deferred commit failed id=0x%02x\n", (unsigned)deferred[0].id);
            return false;
        }
        if (deferred_count > 1u) {
            memmove(&deferred[0], &deferred[1],
                    (size_t)(deferred_count - 1u) * sizeof(deferred[0]));
        }
        --deferred_count;
    }

    disconnect_requested = false;
    return true;
}

uint8 dpls_phy6252_snv_read_guarded(osalSnvId_t id, osalSnvLen_t len, void *data)
{
    int index;
    if (!data) return FAILURE;

    index = pending_index(id);
    if (index >= 0) {
        if (len > deferred[index].len) return FAILURE;
        memcpy(data, deferred[index].data, len);
        return SUCCESS;
    }

    if (!dpls_phy6252_link_active() && deferred_count != 0u &&
        !dpls_phy6252_snv_flush_deferred()) {
        return FAILURE;
    }
    return osal_snv_read(id, len, data);
}

uint8 dpls_phy6252_snv_write_guarded(osalSnvId_t id, osalSnvLen_t len, void *data)
{
    int index;
    dpls_deferred_snv_t *slot;

    if (!data || (uint16)len > DPLS_SNV_DEFERRED_MAX_LEN) return FAILURE;

    if (!dpls_phy6252_link_active()) {
        if (deferred_count != 0u && !dpls_phy6252_snv_flush_deferred()) return FAILURE;
        return osal_snv_write(id, len, data);
    }

    index = pending_index(id);
    if (index < 0) {
        if (deferred_count >= DPLS_SNV_DEFERRED_DEPTH) {
            disconnect_requested = true;
            LOG("DPLS SNV deferred queue full\n");
            return FAILURE;
        }
        index = (int)deferred_count++;
    }

    slot = &deferred[index];
    slot->id = id;
    slot->len = len;
    memcpy(slot->data, data, len);
    disconnect_requested = true;
    LOG("DPLS SNV deferred id=0x%02x len=%u\n", (unsigned)id, (unsigned)len);
    return SUCCESS;
}
