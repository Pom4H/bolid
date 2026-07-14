#ifndef DPLS_ADC_SOAK_H
#define DPLS_ADC_SOAK_H

#include "bcomdef.h"

/* Separate OSAL task used only by the diagnostic ADC branch. Its event bits are
 * private to this task and therefore do not collide with application events. */
void DplsAdcSoak_Init(uint8 task_id);
uint16 DplsAdcSoak_ProcessEvent(uint8 task_id, uint16 events);

#endif
