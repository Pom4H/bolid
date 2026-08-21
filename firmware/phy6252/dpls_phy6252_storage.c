#include "dpls_phy6252_storage.h"

#include "dpls_phy6252_app.h"
#include "dpls_phy6252_snv_guard.h"

bool dpls_phy6252_flash_work_pending(void)
{
    return dpls_phy6252_snv_pending() || dpls_phy6252_storage_pending();
}

bool dpls_phy6252_flash_disconnect_requested(void)
{
    return dpls_phy6252_snv_disconnect_requested();
}

bool dpls_phy6252_flash_process_one(void)
{
    if (dpls_phy6252_link_active()) return false;

    if (dpls_phy6252_snv_pending()) {
        if (!dpls_phy6252_snv_flush_deferred()) return false;
    } else if (dpls_phy6252_storage_pending()) {
        dpls_phy6252_process_storage();
    }

    return !dpls_phy6252_flash_work_pending();
}
