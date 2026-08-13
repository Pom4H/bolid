#include <stdint.h>

uint32_t SystemCoreClock = 25000000u;

void SystemCoreClockUpdate(void) {
    SystemCoreClock = 25000000u;
}

void SystemInit(void) {
    SystemCoreClock = 25000000u;
}
