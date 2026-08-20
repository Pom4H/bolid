#ifndef DPLS_PHY6252_SNV_GUARD_H
#define DPLS_PHY6252_SNV_GUARD_H

#include <stdbool.h>

/* Application-owned SNV writes are staged while BLE is connected. The target
 * drains this queue before advertising is allowed again. */
bool dpls_phy6252_snv_pending(void);
bool dpls_phy6252_snv_flush_deferred(void);
bool dpls_phy6252_snv_disconnect_requested(void);

#endif