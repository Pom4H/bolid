# Прошивка Test-DPLS для PHY6252

Ядро сервера (`src/`, `include/`), адаптер GATT/HAL (`phy6252/`), target
(`targets/phy6252/`). Vendor SDK 3.1.2 подтягивается в `sdk/PHY62XX_SDK_3.1.2/`
(не в git) по SHA из `sdk/phy6252-sdk.env`.

Версия прошивки: **1.2.1**.

## Сборка

```sh
cmake -S . -B build && cmake --build build
ctest --test-dir build --output-on-failure
../tools/lint_firmware.sh
../tools/coverage_firmware.sh   # ctest + ≥80% строк Firmware/src

# из корня репозитория
tools/build_firmware.sh keil tmp/test-dpls.hex   # релизный образ, Keil/AC6
tools/build_firmware.sh gcc  tmp/test-dpls.hex   # GNU Arm, та же раскладка
```

Keil: CMSIS-solution `targets/phy6252/test-dpls.csolution.yml` +
`scatter_load.sct`. GCC: `targets/phy6252/Makefile` + `phy6252.ld`. Список
исходников и дефайны совпадают. Релизный HEX — Keil. Vendor SDK собирается
с `-w`, наш код — `-Werror`. `tools/build_firmware.sh` падает на warning в логе.

`fromelf` пишет entry-point в каждый регион; скрипт оставляет его только в
ER_IROM1, иначе флешер pvvx обрывает разбор.

## GATT

- Service `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001`
- RX `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001`
- TX `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001`

Кадр: `version/type/flags/sequence/length/payload/CRC16-CCITT`. Сессия —
challenge-response, lock после 5 ошибок на 300 с (SNV `0x84`). TX — indication
по одному кадру, таймаут подтверждения 2 с.

## Распиновка (`phy6252/dpls_board.h`)

3,3 В, активный «1», нули = «Норма».

| Пин | Сигнал |
|---|---|
| P31 / P32 / P33 | ISO_1 / ISO_2 / ISO_T |
| P14 / P16 / P17 | KZ_1 / KZ_2 / KZ_T |
| P20 / P15 / P24 / P23 | +1 / +2 / +Т / резерв |
| P7 / P11 / P18 | RGB R / G / B (сцены — зелёный) |
| P34 | заводской сброс пароля |

Режимы: «Обрыв +Т» → ISO_T, «Обрыв магистрали» → ISO_2, «КЗ+1/+2/+Т» →
KZ_1/2/T. Break-before-make, не больше одного активного выхода.

**SNV:** бонды `0x20..0x5F`, настройки `0x80`, маркер `0x81`, MAC `0x82`,
калибровка `0x83`, auth-lock `0x84`, журнал `0x90..0xA3`. Три сектора FS @
`0x1103C000`. `--erase` стирает всё.

Float живёт в `hal_adc_value_cal` — поэтому adc/fp и прикладной код в XIP, не
в тесном `ER_IROM1`.

## Ловушки SDK `main.c`

Компенсированы в `source/dplsBLEPeripheral.c`:

- во сне удерживать SRAM0+SRAM1+SRAM2, иначе wakeup = reset-loop;
- смонтировать FS (`hal_fs_init(0x1103C000, 3)`), иначе SNV мёртв;
- watchdog кормится из IRQ — зависание задачи само не сбрасывает чип.

Сон запрещён только пока энергизован тестовый режим (`MOD_USR1`). В «Норме»
ядро спит, LED-таймер не крутится.

## Проверено / открыто

На PB-03F-Kit: boot, BLE, коммишининг, вход, режимы, журнал, четыре канала ADC
(на голом ките +2/+Т/резерв = 0: делителей нет). На силовой части ещё нужны
калибровка ±0,1 В, ток, автоизоляция и кнопка сброса на P34.

Приёмка: [docs/bring-up-checklist.md](../docs/bring-up-checklist.md).
Чип: [docs/phy6252-programmer-reference.md](../docs/phy6252-programmer-reference.md).
