#ifndef DPLS_PHY6252_SUPERVISOR_H
#define DPLS_PHY6252_SUPERVISOR_H

/* The vendor runtime owns the normal watchdog cadence. DPLS code may only
 * touch watchdog timing through this module. Long flash/SNV operations get a
 * bounded wider window; all other code remains subject to the SDK watchdog. */
void dpls_phy6252_supervisor_checkpoint(void);
void dpls_phy6252_supervisor_blocking_io_begin(void);
void dpls_phy6252_supervisor_blocking_io_end(void);

#endif
