#ifndef DPLS_PHY6252_ADC_H
#define DPLS_PHY6252_ADC_H

#include <stdbool.h>
#include <stdint.h>

/* Four independent voltage inputs on hardware revision 2. The module owns the
 * complete PHY6252 ADC state machine; callers only schedule it and consume
 * fresh millivolt values/validity. */
bool dpls_phy6252_adc_init(uint8_t task_id, uint16_t process_event);
void dpls_phy6252_adc_tick(uint32_t now_ms);
void dpls_phy6252_adc_process(uint32_t now_ms);

/* Disconnected/advertising operation samples only +1 and reserve, the two
 * channels used by autonomous safety logic. A connected operator session
 * enables +2/+T as well. Safety channels remain at the same 1 Hz cadence in
 * both modes, so the power saving does not trade away protection latency. */
void dpls_phy6252_adc_set_full_scan(bool enabled);

uint16_t dpls_phy6252_adc_port1_mv(void);
uint16_t dpls_phy6252_adc_port2_mv(void);
uint16_t dpls_phy6252_adc_port_t_mv(void);
uint16_t dpls_phy6252_adc_reserve_mv(void);

/* Returns DPLS_STATE_PORT_1/2/T_VALID and DPLS_STATE_RESERVE_VOLTAGE_VALID.
 * A bit is cleared again when a channel has not produced a fresh conversion for
 * DPLS_ADC_STALE_MS, so the UI never presents a frozen value as live data. */
uint8_t dpls_phy6252_adc_validity(uint32_t now_ms);

/* True only when a per-channel calibration record covers all four inputs.
 * Legacy two-channel calibration is migrated conservatively and does not claim
 * that +2/+T are calibrated. */
bool dpls_phy6252_adc_fully_calibrated(void);

#endif