#include "dpls_phy6252_supervisor.h"

#include "watchdog.h"

/* SDK main() starts with WDG_2S. Keep that as the normal failure detector and
 * widen it only while the flash driver owns the CPU. The runtime log captured
 * warm resets immediately after a journal SNV write, so allowing random modules
 * to feed the watchdog would hide the actual scheduling bug instead of bounding
 * the one known blocking resource. */
static uint8_t blocking_depth;

void dpls_phy6252_supervisor_checkpoint(void)
{
    hal_watchdog_feed();
}

void dpls_phy6252_supervisor_blocking_io_begin(void)
{
    if (blocking_depth++ != 0u) return;
    (void)watchdog_config(WDG_8S);
    hal_watchdog_feed();
}

void dpls_phy6252_supervisor_blocking_io_end(void)
{
    if (blocking_depth == 0u) return;
    if (--blocking_depth != 0u) return;
    hal_watchdog_feed();
    (void)watchdog_config(WDG_2S);
}
