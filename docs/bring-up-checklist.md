# Hardware bring-up и приёмка Test-DPLS

Чеклист проверки реального железа: сначала PB-03F-Kit, затем силовая часть. Пункты с ⚠ требуют измерений на конкретном железе и не считаются подтверждёнными host-тестами.

Source of truth:

- pin mapping — `firmware/phy6252/dpls_board.h`;
- BLE startup/identity — `firmware/targets/phy6252/source/dplsBLEPeripheral.c`, `firmware/phy6252/dpls_ble_identity.c`;
- ADC/power/reserve/auto-isolation — `firmware/phy6252/dpls_phy6252_app.c`;
- calibration — `firmware/src/dpls_calib.c`;
- LED — `firmware/src/dpls_led.c`.

## 0. Подготовка

Host checks:

```sh
cmake -S firmware -B firmware/build
cmake --build firmware/build
ctest --test-dir firmware/build --output-on-failure
bash tools/lint_firmware.sh
bash tools/coverage_firmware.sh
```

Target builds:

```sh
tools/build_firmware.sh keil tmp/test-dpls.hex
tools/build_firmware.sh gcc tmp/test-dpls-gcc.hex
```

Прошивка новой или существующей платы одинаковая:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex
```

Никакого factory sidecar/provisioning шага нет. Полный erase при необходимости:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex --erase
```

После erase SNV MAC/IRK/CSRK/bonds/settings будут восстановлены обычными runtime путями.

UART/session capture:

```sh
python3 tools/serial_capture.py 20 --no-reset
python3 tools/session_capture/record_session.py --name bringup
python3 tools/session_capture/parse_session.py tmp/sessions/session-*-bringup.log
```

## 1. Boot и BLE startup

Главный acceptance этого RC:

- [ ] После application-only `wh` и полного power-cycle плата появляется в scan без дополнительных записей flash.
- [ ] Виден Service UUID `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001`.
- [ ] Radio name = `Test-DPLS-XXXX`; если identity не подготовилась, допустим диагностический `Test-DPLS-0000`, но плата не должна исчезать из эфира.
- [ ] BLE address не нулевой и не `FF:FF:FF:FF:FF:FF` в нормальном identity path.
- [ ] BLE address сохраняется после reboot и обычной перепрошивки.
- [ ] После chip erase создаётся новая SNV identity без отдельного provisioning.
- [ ] Boot journal/deferred flash не задерживает первый advertising.
- [ ] Через несколько секунд idle flash flush не ломает advertising и последующее подключение.
- [ ] В эфире нет Manufacturer Specific Data старого контракта.
- [ ] `DEVICE_INFO_REPORT` показывает firmware `1.4.2`, hardware revision и сохранённое пользовательское имя.

### Boot order

Проверяем именно такой порядок:

```text
power/reset
  ↓
RAM retention + SNV mount
  ↓
identity prepare
  ↓
DPLS init
  ↓
GAPRole_StartDevice
  ↓
GAPROLE_STARTED
  ↓
advertising
  ↓
idle/deferred flash
```

Flash до первого advertising — regression.

## 2. Силовые выходы

Проверять P31/P32/P33 (ISO) и P14/P16/P17 (KZ). Логика active-high.

| Режим | ISO_1 P31 | ISO_2 P32 | ISO_T P33 | KZ_1 P14 | KZ_2 P16 | KZ_T P17 |
|---|---:|---:|---:|---:|---:|---:|
| NORMAL | 0 | 0 | 0 | 0 | 0 | 0 |
| Обрыв +Т | 0 | 0 | 1 | 0 | 0 | 0 |
| Обрыв магистрали | 0 | 1 | 0 | 0 | 0 | 0 |
| КЗ +1 | 0 | 0 | 0 | 1 | 0 | 0 |
| КЗ +2 | 0 | 0 | 0 | 0 | 1 | 0 |
| КЗ +Т | 0 | 0 | 0 | 0 | 0 | 1 |

- [ ] На старте все шесть outputs = 0.
- [ ] Каждый режим включает ровно один сигнал.
- [ ] Break-before-make исключает перекрытие режимов.
- [ ] Переключение ≤1 с (ТЗ 4.3.6).
- [ ] КЗ/обрыв корректно определяются С2000-КДЛ, после `NORMAL` линия восстанавливается.
- [ ] Сквозное сопротивление `NORMAL` ≤40 мОм; ток искусственного КЗ ≤1 А (ТЗ 4.3.3/4.3.5).

## 3. Световая индикация

Зелёный канал — P11. Красный P7 и синий P18 не должны примешиваться к зелёным сценам.

- [ ] `NORMAL` от ДПЛС — LED выключен.
- [ ] КЗ +1/+2/+Т — 1/2/3 коротких вспышки.
- [ ] Обрыв магистрали — длинная + 2 коротких.
- [ ] Обрыв +Т — длинная + 3 коротких.
- [ ] Identify — 1 Гц, duty 50%, auto-stop ≤60 с.
- [ ] Резерв добавляет предусмотренную сцену индикации.
- [ ] Автоизоляция реального КЗ имеет более высокий safety-смысл, чем test-mode display.

## 4. ⚠ ADC +1 / +2 / +Т / reserve

Revision 2:

| Канал | GPIO |
|---|---|
| +1 | P20 |
| +2 | P15 |
| +Т | P24 |
| reserve | P23 |

- [ ] Каждый канал монотонен в рабочем диапазоне.
- [ ] 27–30 В не насыщают измерительный тракт.
- [ ] Выполнена двухточечная calibration.
- [ ] Calibration записана в SNV `0x83` и читается обратно с валидной CRC.
- [ ] SNV `0x82` используется только как fallback BLE MAC.
- [ ] В 5–27 В ошибка каждого канала ≤±0,1 В, UI resolution не хуже 0,1 В.
- [ ] `null`/invalid measurement не маскируется под достоверный 0 В.

## 5. ⚠ Резервный накопитель и питание

Текущие firmware thresholds должны подтверждаться на силовой части измерением, а не только кодом.

- [ ] Подтверждён коэффициент делителя VCAP.
- [ ] Проверено рабочее окно заряда/разряда резерва.
- [ ] Low-reserve hysteresis не дребезжит.
- [ ] При снятии линии `power_source=RESERVE`, при восстановлении — `DPLS`.
- [ ] Автономия от резерва ≥5 мин с BLE (ТЗ 4.4.2 / 5.1d).
- [ ] Средний ток ≤0,5 мА, пики ≤3 мА (ТЗ 4.3.2).
- [ ] Low reserve принудительно снимает опасный режим.

## 6. ⚠ Автоизоляция реального КЗ

- [ ] Реальное КЗ ниже по линии изолируется аппаратно в требуемое время.
- [ ] Firmware сообщает `real_short` и пишет соответствующее событие.
- [ ] При активной auto-isolation test-mode command отклоняется.
- [ ] После снятия КЗ состояние корректно восстанавливается.
- [ ] Firmware thresholds подтверждены на реальной силовой части и при необходимости скорректированы.

## 7. Factory reset

- [ ] P34↔GND ≥5 с очищает пользовательское имя/пароль/bonds и возвращает commissioning state.
- [ ] P24 не используется как reset: это ADC +Т.
- [ ] Удалённого factory reset по BLE нет.
- [ ] BLE identity после reset остаётся валидной/восстанавливается из SNV/vendor MAC.
- [ ] Calibration не должна случайно исчезать при обычной эксплуатации; её lifecycle проверяется отдельно от user settings.

## 8. BLE round-trip

- [ ] `STATE_REPORT` соответствует фактическим mode/voltage/power/reserve/real-short.
- [ ] MODE_SET идемпотентен.
- [ ] Safety return работает по timeout, disconnect и low reserve.
- [ ] После пяти неверных application passwords действует lock 300 с.
- [ ] Protected GATT operation без encryption отвергается.
- [ ] После auth `DEVICE_INFO_REPORT` является authoritative источником identity текущей сессии и данных «Об устройстве».

## 9. E2E на живом железе

```sh
python3 tools/e2e/phone_e2e_test.py
python3 tools/e2e/phone_e2e_test.py --journal-only
python3 tools/e2e/journal_reboot_check.py
```

- [ ] Полный phone+board E2E проходит.
- [ ] Journal переживает reboot и обычную перепрошивку.
- [ ] Ring journal корректно ротируется на проектной ёмкости.
- [ ] Reconnect после deferred flash flush проходит без ручного сброса bonds.

## 10. Финальная приёмка

- [ ] Host tests/lint/coverage проходят.
- [ ] Keil и GCC production HEX фактически не содержат data records в `0x1103C000..0x1103FFFF`.
- [ ] Keil и GCC/Firmverse проходят на одном application-only artifact contract.
- [ ] Реальная PB-03F стартует и рекламируется после обычного `wh` без factory sidecar.
- [ ] Сценарии ТЗ раздела 5 пройдены на стенде С2000-КДЛ.
- [ ] Финальные calibration coefficients и thresholds зафиксированы в эксплуатационной/испытательной документации.
