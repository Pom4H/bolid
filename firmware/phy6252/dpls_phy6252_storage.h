#ifndef DPLS_PHY6252_STORAGE_H
#define DPLS_PHY6252_STORAGE_H

#include <stdbool.h>

/* The target shell sees exactly one storage actor. It never knows whether work
 * came from settings/auth or journal persistence. */
bool dpls_phy6252_flash_work_pending(void);
bool dpls_phy6252_flash_disconnect_requested(void);
/* Process at most one blocking flash unit while link is down. Returns true when
 * no flash work remains and advertising may be re-enabled. */
bool dpls_phy6252_flash_process_one(void);

#endif
