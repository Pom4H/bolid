#ifndef DPLS_PHY6252_MEASUREMENTS_H
#define DPLS_PHY6252_MEASUREMENTS_H

#include "dpls_server.h"
#include "types.h"

void dpls_phy6252_measurements_init(uint8 task_id);
/* Sampling cadence is scheduled by runtime; this function only starts a due
 * conversion series. */
void dpls_phy6252_measurements_tick(bool connected, dpls_mode_t mode);
/* ADC IRQ completion immediately reconciles derived safety facts. */
void dpls_phy6252_measurements_process(dpls_mode_t mode);

uint16_t dpls_phy6252_measurements_voltage_mv(void *context);
uint16_t dpls_phy6252_measurements_port1_mv(void *context);
uint16_t dpls_phy6252_measurements_port2_mv(void *context);
uint16_t dpls_phy6252_measurements_port_t_mv(void *context);
uint16_t dpls_phy6252_measurements_reserve_mv(void *context);
dpls_power_t dpls_phy6252_measurements_power_source(void *context);
bool dpls_phy6252_measurements_reserve_low(void *context);
bool dpls_phy6252_measurements_real_short(void *context);
uint8_t dpls_phy6252_measurements_validity(void *context);
bool dpls_phy6252_measurements_line_calibrated(void);

#endif
