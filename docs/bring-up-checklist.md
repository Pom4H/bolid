# Hardware bring-up и приёмка Test-DPLS

Чеклист проверки реального железа: сначала PB-03F-Kit, затем силовая часть. Пункты с ⚠ требуют измерений на конкретном железе и не считаются подтверждёнными host-тестами.

Source of truth:

- pin mapping — `firmware/phy6252/dpls_board.h`;
- BLE startup/identity — `firmware/targets/phy6252/source/dplsBLEPeripheral.c`, `firmware/phy6252/dpls_ble_identity.c`;
- ADC/power/reserve — `firmware/phy6252/dpls_phy6252_measurements.c`, `firmware/phy6252/dpls_phy6252_power.c`;
- runtime orchestration — `firmware/phy6252/dpls_phy6252_runtime.c`;
- calibration — `firmware/src/dpls_calib.c`;
- LED — `firmware/src/dpls_led.c`.

## 0. Подготовка

Host checks:

```sh
bash tools/run_host_invariant_gate.sh
bash tools/lint_firmware.sh
bash tools/coverage_firmware.sh
```

Production target:

```sh
tools/build_firmware.sh tmp/test-dpls.hex
tools/flash_firmware.sh tmp/test-dpls.hex
```

Target image всегда собирается Arm Compiler 6.24.0 из CMSIS project. Второго target build path нет.

Полный erase при необходимости:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex --erase
```

UART/session capture:

```sh
python3 tools/serial_capture.py 20 --no-reset
python3 tools/session_capture/record_session.py --name bringup
python3 tools/session_capture/parse_session.py tmp/sessions/session-*-bringup.log
```

## 1. Boot и BLE startup

- [ ] После application-only `wh` и полного power-cycle плата появляется в scan без дополнительных записей flash.
- [ ] Виден Service UUID `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001`.
- [ ] Radio name = `Test-DPLS-XXXX`.
- [ ] BLE address не нулевой и не `FF:FF:FF:FF:FF:FF`.
- [ ] BLE address сохраняется после reboot и обычной перепрошивки.
- [ ] После очистки SNV identity восстанавливается обычным runtime path.
- [ ] Boot journal/deferred flash не задерживает первый advertising.
- [ ] Через несколько секунд idle flash flush не ломает advertising и последующее подключение.
- [ ] `DEVICE_INFO_REPORT` показывает firmware `1.5.0`, hardware revision и сохранённое пользовательское имя.

Проверяем порядок:

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
- [ ] Переключение ≤1 с.
- [ ] Сквозное сопротивление `NORMAL` ≤40 мОм; ток искусственного КЗ ≤1 А.

## 3. Световая индикация

- [ ] `NORMAL` от ДПЛС — LED выключен.
- [ ] КЗ +1/+2/+Т — 1/2/3 коротких вспышки.
- [ ] Обрыв магистрали — длинная + 2 коротких.
- [ ] Обрыв +Т — длинная + 3 коротких.
- [ ] Identify — 1 Гц, duty 50%, auto-stop ≤60 с.
- [ ] Auto-isolation имеет приоритет над test-mode display.

## 4. ⚠ ADC +1 / +2 / +Т / reserve

| Канал | GPIO |
|---|---|
| +1 | P20 |
| +2 | P15 |
| +Т | P24 |
| reserve | P23 |

- [ ] Каждый канал монотонен в рабочем диапазоне.
- [ ] 27–30 В не насыщают измерительный тракт.
- [ ] Выполнена двухточечная calibration.
- [ ] Calibration записана в SNV и читается обратно с валидной CRC.
- [ ] В 5–27 В ошибка каждого канала ≤±0,1 В.
- [ ] invalid measurement не маскируется под достоверный 0 В.

## 5. ⚠ Резервный накопитель и питание

- [ ] Подтверждён коэффициент делителя VCAP.
- [ ] Проверено рабочее окно заряда/разряда резерва.
- [ ] Low-reserve hysteresis не дребезжит.
- [ ] При снятии линии `power_source=RESERVE`, при восстановлении — `DPLS`.
- [ ] Автономия от резерва ≥5 мин с BLE.
- [ ] Средний ток ≤0,5 мА, пики ≤3 мА.
- [ ] Low reserve принудительно снимает опасный режим.

## 6. ⚠ Автоизоляция реального КЗ

- [ ] Реальное КЗ ниже по линии изолируется аппаратно в требуемое время.
- [ ] Firmware сообщает `real_short` и пишет событие.
- [ ] При active auto-isolation test-mode command отклоняется.
- [ ] После снятия КЗ состояние корректно восстанавливается.

## 7. Factory reset

- [ ] P34↔GND ≥5 с очищает пользовательское имя/пароль/bonds и возвращает commissioning state.
- [ ] P24 не используется как reset: это ADC +Т.
- [ ] Удалённого factory reset по BLE нет.
- [ ] BLE identity после reset остаётся валидной или восстанавливается штатным path.

## 8. BLE round-trip

- [ ] `STATE_REPORT` соответствует фактическим mode/voltage/power/reserve/real-short.
- [ ] MODE_SET идемпотентен.
- [ ] Safety return работает по timeout, disconnect и low reserve.
- [ ] После пяти неверных application passwords действует lock 300 с.
- [ ] Protected GATT operation без encryption отвергается.
- [ ] После auth `DEVICE_INFO_REPORT` является authoritative источником identity текущей сессии.

## 9. E2E на живом железе

```sh
python3 tools/e2e/phone_e2e_test.py
python3 tools/e2e/phone_e2e_test.py --journal-only
python3 tools/e2e/journal_reboot_check.py
```

- [ ] Полный phone+board E2E проходит.
- [ ] Journal переживает reboot и обычную перепрошивку.
- [ ] Ring journal корректно ротируется.
- [ ] Reconnect после deferred flash flush проходит без ручного сброса bonds.

## 10. Финальная приёмка

- [ ] Host tests/lint/coverage проходят.
- [ ] Production HEX не содержит data records в `0x1103C000..0x1103FFFF`.
- [ ] Тот же production HEX проходит strict Firmverse и прошивается на PB-03F.
- [ ] Реальная PB-03F стартует и рекламируется после обычного `wh`.
- [ ] Сценарии ТЗ раздела 5 пройдены на стенде С2000-КДЛ.
- [ ] Финальные calibration coefficients и thresholds зафиксированы в эксплуатационной/испытательной документации.
