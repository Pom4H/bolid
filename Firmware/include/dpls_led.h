#ifndef DPLS_LED_H
#define DPLS_LED_H

#include <stdbool.h>
#include <stdint.h>

/* Статусный светодиод служит одному сценарию — «показать на объекте»: пока
 * идентификация активна, светодиод мигает 1 Гц со скважностью 50 %, всё
 * остальное время погашен. Серии вспышек по типам событий сняты: индикация
 * режимов живёт на отдельных лампах, а состояние прибора читается в приложении.
 *
 * Драйвер владеет только формой мигания, поэтому адаптеру достаточно сообщать
 * флаг идентификации и вызывать dpls_led_tick() из таймера. */

#define DPLS_LED_IDENTIFY_HALF_MS 500u
/* Как часто перевзводить таймер, когда мигать нечем. */
#define DPLS_LED_IDLE_MS 1000u

typedef void (*dpls_led_output_fn)(void *context, bool on);

typedef struct {
    dpls_led_output_fn output;
    void *context;
    bool identify;
    bool level_set;
    bool level;
    uint32_t cycle_start_ms;
} dpls_led_t;

void dpls_led_init(dpls_led_t *led, dpls_led_output_fn output, void *context, uint32_t now_ms);

/* Включает или выключает идентификацию. Повторный вызов с тем же значением
 * ничего не делает: мигание не перезапускается с начала периода. */
void dpls_led_set_identify(dpls_led_t *led, bool identify, uint32_t now_ms);

/* Двигает мигание, дёргает выход только на фронтах и возвращает, через сколько
 * миллисекунд вызывающему стоит прийти снова. */
uint32_t dpls_led_tick(dpls_led_t *led, uint32_t now_ms);

#endif
