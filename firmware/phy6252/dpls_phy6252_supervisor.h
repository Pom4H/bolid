#ifndef DPLS_PHY6252_SUPERVISOR_H
#define DPLS_PHY6252_SUPERVISOR_H

/* PHY62XX SDK 3.1.2 enables WDG_2S in main(), but its OSAL idle-task watchdog
 * feed is commented out. DPLS therefore owns an explicit cooperative heartbeat
 * through this module. Long flash/SNV operations get a bounded wider window;
 * all other code remains subject to the normal 2 second watchdog. */
void dpls_phy6252_supervisor_checkpoint(void);
void dpls_phy6252_supervisor_blocking_io_begin(void);
void dpls_phy6252_supervisor_blocking_io_end(void);

#endif
