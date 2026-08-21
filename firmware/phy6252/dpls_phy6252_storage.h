#ifndef DPLS_PHY6252_STORAGE_H
#define DPLS_PHY6252_STORAGE_H

#include <stdbool.h>

/* Target видит только единый flash facade и не знает, откуда пришла работа. */
bool dpls_phy6252_flash_work_pending(void);
bool dpls_phy6252_flash_disconnect_requested(void);
/* Выполняет не больше одной blocking flash операции без активного BLE link.
 * Возвращает true, когда очередь пуста и advertising можно включить снова. */
bool dpls_phy6252_flash_process_one(void);

#endif
