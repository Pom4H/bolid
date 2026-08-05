#!/usr/bin/env python3
"""One-shot repository migration for real-time multi-voltage telemetry."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{relative}: expected one match, found {count}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_all_checked(relative: str, replacements: list[tuple[str, str]]) -> None:
    for old, new in replacements:
        replace_once(relative, old, new)


def replace_global(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count < 1:
        raise SystemExit(f"{relative}: no matches for {old!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


replace_all_checked(
    "Firmware/include/dpls_server.h",
    [
        ("#define DPLS_FW_VERSION_PATCH 1u", "#define DPLS_FW_VERSION_PATCH 2u"),
        (
            """enum {
    DPLS_CAP_ADC_PRESENT    = 1u << 0,
    DPLS_CAP_HW_READBACK    = 1u << 1, /* power-stage feedback (false until stage 6) */
    DPLS_CAP_ADC_CALIBRATED = 1u << 2,
};""",
            """enum {
    DPLS_CAP_ADC_PRESENT           = 1u << 0,
    DPLS_CAP_HW_READBACK           = 1u << 1, /* power-stage feedback (false until stage 6) */
    DPLS_CAP_ADC_CALIBRATED        = 1u << 2,
    DPLS_CAP_MULTI_VOLTAGE_REPORT  = 1u << 3,
};""",
        ),
        (
            """    DPLS_STATE_POWER_VALID        = 1u << 2,
    DPLS_STATE_AUTOISO_VALID      = 1u << 3,
    DPLS_STATE_ADC_CALIBRATED     = 1u << 4,
};""",
            """    DPLS_STATE_POWER_VALID        = 1u << 2,
    DPLS_STATE_AUTOISO_VALID      = 1u << 3,
    DPLS_STATE_ADC_CALIBRATED     = 1u << 4,
    DPLS_STATE_PORT_2_VALID       = 1u << 5,
    DPLS_STATE_PORT_T_VALID       = 1u << 6,
};
#define DPLS_STATE_PORT_1_VALID DPLS_STATE_LINE_VOLTAGE_VALID
#define DPLS_STATE_RESERVE_VOLTAGE_VALID DPLS_STATE_RESERVE_VALID""",
        ),
        (
            """    void (*hardware_safe_normal)(void *context);
    uint16_t (*voltage_mv)(void *context);
    dpls_power_t (*power_source)(void *context);""",
            """    void (*hardware_safe_normal)(void *context);
    /* Legacy DPLS voltage remains in STATE_REPORT bytes 2..3. Firmware 1.1.2+
     * also appends four explicitly labelled live voltages. Missing physical
     * channels return 0 and keep their validity bits clear. */
    uint16_t (*voltage_mv)(void *context);
    uint16_t (*port1_voltage_mv)(void *context);
    uint16_t (*port2_voltage_mv)(void *context);
    uint16_t (*port_t_voltage_mv)(void *context);
    uint16_t (*reserve_voltage_mv)(void *context);
    dpls_power_t (*power_source)(void *context);""",
        ),
    ],
)

replace_once(
    "Firmware/src/dpls_server.c",
    """static void send_state(dpls_server_t *s) {
    uint8_t p[17]; memset(p, 0, sizeof(p));
    p[0] = (uint8_t)s->mode; p[1] = (uint8_t)s->hal.power_source(s->hal.context);
    wr16(p + 2, s->hal.voltage_mv(s->hal.context));
    if (s->mode != DPLS_MODE_NORMAL && !elapsed(s->now_ms, s->mode_deadline_ms))
        wr16(p + 4, (uint16_t)((s->mode_deadline_ms - s->now_ms + 999u) / 1000u));
    p[6] = s->hal.reserve_low(s->hal.context) ? 1u : 0u;
    /* p[7] is a flag byte: bit0 = connected, bit1 = real-short auto-isolation. */
    p[7] = (uint8_t)((s->connected ? 0x01u : 0u) | (s->real_short ? 0x02u : 0u));
    wr32(p + 8, s->now_ms / 1000u); wr32(p + 12, s->state_revision);
    /* p[16]: measurement-validity mask. Legacy clients read only 16 bytes and
     * ignore this; new clients hide unmeasured fields when a bit is clear. */
    p[16] = s->hal.measurement_validity ? s->hal.measurement_validity(s->hal.context) : 0u;
    send_frame(s, DPLS_MSG_STATE_REPORT, p, sizeof(p), false);
}""",
    """static void send_state(dpls_server_t *s) {
    uint8_t p[25];
    uint16_t legacy_voltage;
    uint16_t port1_voltage;
    memset(p, 0, sizeof(p));
    legacy_voltage = s->hal.voltage_mv ? s->hal.voltage_mv(s->hal.context) : 0u;
    port1_voltage = s->hal.port1_voltage_mv ?
        s->hal.port1_voltage_mv(s->hal.context) : legacy_voltage;
    p[0] = (uint8_t)s->mode; p[1] = (uint8_t)s->hal.power_source(s->hal.context);
    wr16(p + 2, legacy_voltage);
    if (s->mode != DPLS_MODE_NORMAL && !elapsed(s->now_ms, s->mode_deadline_ms))
        wr16(p + 4, (uint16_t)((s->mode_deadline_ms - s->now_ms + 999u) / 1000u));
    p[6] = s->hal.reserve_low(s->hal.context) ? 1u : 0u;
    /* p[7] is a flag byte: bit0 = connected, bit1 = real-short auto-isolation. */
    p[7] = (uint8_t)((s->connected ? 0x01u : 0u) | (s->real_short ? 0x02u : 0u));
    wr32(p + 8, s->now_ms / 1000u); wr32(p + 12, s->state_revision);
    /* p[16]: per-channel measurement-validity mask. */
    p[16] = s->hal.measurement_validity ? s->hal.measurement_validity(s->hal.context) : 0u;
    /* Firmware 1.1.2+: +1, +2, +T and reserve in mV. The original 17-byte
     * prefix stays compatible with previous Android clients. */
    wr16(p + 17, port1_voltage);
    wr16(p + 19, s->hal.port2_voltage_mv ?
                       s->hal.port2_voltage_mv(s->hal.context) : 0u);
    wr16(p + 21, s->hal.port_t_voltage_mv ?
                       s->hal.port_t_voltage_mv(s->hal.context) : 0u);
    wr16(p + 23, s->hal.reserve_voltage_mv ?
                       s->hal.reserve_voltage_mv(s->hal.context) : 0u);
    send_frame(s, DPLS_MSG_STATE_REPORT, p, sizeof(p), false);
}""",
)

replace_all_checked(
    "Firmware/phy6252/dpls_phy6252_app.c",
    [
        (
            """static uint16_t voltage_mv(void *context)
{
    (void)context;
    return cached_line_mv;
}

static dpls_power_t power_source(void *context)""",
            """static uint16_t voltage_mv(void *context)
{
    (void)context;
    return cached_line_mv;
}

static uint16_t port1_voltage_mv(void *context)
{
    (void)context;
    return cached_line_mv;
}

static uint16_t reserve_voltage_mv(void *context)
{
    (void)context;
    return cached_vcap_mv;
}

static dpls_power_t power_source(void *context)""",
        ),
        (
            """    out->capabilities = 0u;
    out->capabilities |= DPLS_CAP_ADC_PRESENT;
    if (line_calib_from_nv) out->capabilities |= DPLS_CAP_ADC_CALIBRATED;""",
            """    out->capabilities = 0u;
    out->capabilities |= DPLS_CAP_ADC_PRESENT;
    out->capabilities |= DPLS_CAP_MULTI_VOLTAGE_REPORT;
    if (line_calib_from_nv) out->capabilities |= DPLS_CAP_ADC_CALIBRATED;""",
        ),
        (
            """    hal.hardware_safe_normal = safe_normal;
    hal.voltage_mv = voltage_mv;
    hal.power_source = power_source;""",
            """    hal.hardware_safe_normal = safe_normal;
    hal.voltage_mv = voltage_mv;
    hal.port1_voltage_mv = port1_voltage_mv;
    hal.reserve_voltage_mv = reserve_voltage_mv;
    hal.power_source = power_source;""",
        ),
    ],
)

replace_global(
    "Firmware/tests/test_server.c",
    ".voltage_mv = voltage, .power_source = power, .reserve_low = low,",
    ".voltage_mv = voltage, .port1_voltage_mv = port1_voltage,\n        .port2_voltage_mv = port2_voltage, .port_t_voltage_mv = port_t_voltage,\n        .reserve_voltage_mv = reserve_voltage, .power_source = power, .reserve_low = low,",
)

replace_all_checked(
    "Firmware/tests/test_server.c",
    [
        (
            """static uint16_t voltage(void *c) { (void)c; return 24100; }
static dpls_power_t power(void *c)""",
            """static uint16_t voltage(void *c) { (void)c; return 24100; }
static uint16_t port1_voltage(void *c) { (void)c; return 24100; }
static uint16_t port2_voltage(void *c) { (void)c; return 23800; }
static uint16_t port_t_voltage(void *c) { (void)c; return 23700; }
static uint16_t reserve_voltage(void *c) { (void)c; return 4200; }
static dpls_power_t power(void *c)""",
        ),
        (
            """    /* STATE_REPORT is 17 bytes: live voltage at [2..3] and the validity mask at
     * [16], forwarded verbatim from the HAL so the app can hide unmeasured data. */
    fake.validity = DPLS_STATE_LINE_VOLTAGE_VALID | DPLS_STATE_POWER_VALID;""",
            """    /* STATE_REPORT keeps its legacy prefix and appends +1, +2, +T and reserve. */
    fake.validity = DPLS_STATE_PORT_1_VALID | DPLS_STATE_PORT_2_VALID |
                    DPLS_STATE_PORT_T_VALID | DPLS_STATE_RESERVE_VOLTAGE_VALID |
                    DPLS_STATE_POWER_VALID;""",
        ),
        (
            """    assert(response.payload_length == 17);
    assert(response.payload[2] == (uint8_t)(24100u & 0xffu) && response.payload[3] == (uint8_t)(24100u >> 8));
    assert(response.payload[16] == (DPLS_STATE_LINE_VOLTAGE_VALID | DPLS_STATE_POWER_VALID));""",
            """    assert(response.payload_length == 25);
    assert(response.payload[2] == (uint8_t)(24100u & 0xffu) && response.payload[3] == (uint8_t)(24100u >> 8));
    assert(response.payload[16] == fake.validity);
    assert(response.payload[17] == (uint8_t)(24100u & 0xffu) && response.payload[18] == (uint8_t)(24100u >> 8));
    assert(response.payload[19] == (uint8_t)(23800u & 0xffu) && response.payload[20] == (uint8_t)(23800u >> 8));
    assert(response.payload[21] == (uint8_t)(23700u & 0xffu) && response.payload[22] == (uint8_t)(23700u >> 8));
    assert(response.payload[23] == (uint8_t)(4200u & 0xffu) && response.payload[24] == (uint8_t)(4200u >> 8));""",
        ),
    ],
)

replace_all_checked(
    "TestDPLS/app/src/main/java/ru/bolid/testdpls/ble/DplsModels.kt",
    [
        (
            """    val autoIsoValid: Boolean = true,
    val adcCalibrated: Boolean = false,
)""",
            """    val autoIsoValid: Boolean = true,
    val adcCalibrated: Boolean = false,
    val port1VoltageMv: Int = voltageMv,
    val port2VoltageMv: Int = 0,
    val portTVoltageMv: Int = 0,
    val reserveVoltageMv: Int = 0,
    val port1VoltageValid: Boolean = lineVoltageValid,
    val port2VoltageValid: Boolean = false,
    val portTVoltageValid: Boolean = false,
    val reserveVoltageValid: Boolean = false,
)""",
        ),
        (
            """    val adcCalibrated: Boolean,
    val userName: String,
)""",
            """    val adcCalibrated: Boolean,
    val userName: String,
    val multiVoltageReport: Boolean = false,
)""",
        ),
    ],
)

replace_all_checked(
    "TestDPLS/app/src/main/java/ru/bolid/testdpls/ble/BleClient.kt",
    [
        (
            """        val validity = if (payload.remaining() >= 1) payload.u8() else 0x00
        val bootEpoch = System.currentTimeMillis() / 1000 - uptimeSeconds""",
            """        val validity = if (payload.remaining() >= 1) payload.u8() else 0x00
        // Firmware 1.1.2+ appends +1, +2, +T and reserve voltages in mV while
        // retaining the original 17-byte prefix for older clients.
        val extendedVoltages = payload.remaining() >= 8
        val port1Voltage = if (extendedVoltages) payload.u16() else voltage
        val port2Voltage = if (extendedVoltages) payload.u16() else 0
        val portTVoltage = if (extendedVoltages) payload.u16() else 0
        val reserveVoltage = if (extendedVoltages) payload.u16() else 0
        val bootEpoch = System.currentTimeMillis() / 1000 - uptimeSeconds""",
        ),
        (
            """            autoIsoValid = (validity and 0x08) != 0,
            adcCalibrated = (validity and 0x10) != 0,
        )""",
            """            autoIsoValid = (validity and 0x08) != 0,
            adcCalibrated = (validity and 0x10) != 0,
            port1VoltageMv = port1Voltage,
            port2VoltageMv = port2Voltage,
            portTVoltageMv = portTVoltage,
            reserveVoltageMv = reserveVoltage,
            port1VoltageValid = (validity and 0x01) != 0,
            port2VoltageValid = extendedVoltages && (validity and 0x20) != 0,
            portTVoltageValid = extendedVoltages && (validity and 0x40) != 0,
            reserveVoltageValid = extendedVoltages && (validity and 0x02) != 0,
        )""",
        ),
        (
            """            adcCalibrated = (caps and 0x04) != 0,
            userName = name,""",
            """            adcCalibrated = (caps and 0x04) != 0,
            multiVoltageReport = (caps and 0x08) != 0,
            userName = name,""",
        ),
        (
            """        val state = _uiState.value
        val mode = state.state?.mode
        if (state.authenticated && !state.commandInProgress && mode != null && mode != DplsMode.NORMAL && gatt != null) {
            handler.postDelayed(stateRefresh, STATE_REFRESH_MS)
        }""",
            """        val state = _uiState.value
        if (state.authenticated && !state.commandInProgress && state.state != null && gatt != null) {
            handler.postDelayed(stateRefresh, STATE_REFRESH_MS)
        }""",
        ),
        (
            """            if (current.authenticated && current.state?.mode != DplsMode.NORMAL && gatt != null) {
                send(DplsProtocol.Type.STATE_GET, authenticatedPayload())
                handler.postDelayed(this, STATE_REFRESH_MS)
            }""",
            """            if (current.authenticated && !current.commandInProgress && current.state != null && gatt != null) {
                send(DplsProtocol.Type.STATE_GET, authenticatedPayload())
                handler.postDelayed(this, STATE_REFRESH_MS)
            }""",
        ),
    ],
)

replace_all_checked(
    "TestDPLS/app/src/main/java/ru/bolid/testdpls/ui/DplsScreen.kt",
    [
        (
            """            InfoRow("Напряжение ДПЛС", if (device?.lineVoltageValid == true) "%.2f В".format(device.voltageMv / 1000.0) else "Нет данных")
            InfoRow("Питание", if (device?.powerValid == true) device.powerSource.title else "Нет данных")
            InfoRow("Резерв", if (device?.reserveValid == true) if (device.reserveLow) "Низкий заряд" else "Норма" else "Нет данных")""",
            """            Text("Напряжения · обновление каждую секунду", color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoRow("Клемма +1", formatVoltage(device?.port1VoltageMv, device?.port1VoltageValid == true))
            InfoRow("Клемма +2", formatVoltage(device?.port2VoltageMv, device?.port2VoltageValid == true))
            InfoRow("Клемма +Т", formatVoltage(device?.portTVoltageMv, device?.portTVoltageValid == true))
            InfoRow("Резерв", formatVoltage(device?.reserveVoltageMv, device?.reserveVoltageValid == true))
            InfoRow("Питание", if (device?.powerValid == true) device.powerSource.title else "Нет данных")
            InfoRow("Состояние резерва", if (device?.reserveValid == true) if (device.reserveLow) "Низкий заряд" else "Норма" else "Нет данных")""",
        ),
        (
            """            InfoRow("Калибровка ADC", if (info.adcCalibrated) "Выполнена" else "Не выполнена")
            InfoRow("Обратная связь выходов", if (info.hardwareReadback) "Есть" else "Нет")""",
            """            InfoRow("Калибровка ADC", if (info.adcCalibrated) "Выполнена" else "Не выполнена")
            InfoRow("Телеметрия +1/+2/+Т/резерв", if (info.multiVoltageReport) "Поддерживается" else "Нет")
            InfoRow("Обратная связь выходов", if (info.hardwareReadback) "Есть" else "Нет")""",
        ),
        (
            """private fun formatDuration(seconds: Int): String =
    "%d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)""",
            """private fun formatVoltage(millivolts: Int?, valid: Boolean): String =
    if (valid && millivolts != null) "%.2f В".format(millivolts / 1000.0) else "Нет данных"

private fun formatDuration(seconds: Int): String =
    "%d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)""",
        ),
    ],
)

replace_all_checked(
    "TestDPLS/app/build.gradle.kts",
    [
        ("versionCode = 3", "versionCode = 4"),
        ('versionName = "1.1.0"', 'versionName = "1.1.1"'),
    ],
)

requirements = """# Требования к отображению напряжений в реальном времени

## Основание в ТЗ

Исходное ТЗ требует измерять напряжение ДПЛС на клеммах устройства, передавать
его через BLE и показывать в тестовом мобильном приложении. Для измерительного
канала заданы входное сопротивление не менее 1 МОм, диапазон не менее 0-30 В,
погрешность не хуже ±0,1 В в диапазоне 5-27 В и разрешение не хуже 0,1 В.

Исходное ТЗ не требует раздельных измерений на клеммах +1, +2 и +Т. Настоящий
документ вводит дополнительное требование проекта.

## Функциональные требования

1. На основном экране после аутентификации одновременно отображаются:
   - напряжение на клемме +1;
   - напряжение на клемме +2;
   - напряжение на клемме +Т;
   - напряжение резервного накопителя.
2. Значения обновляются автоматически не реже одного раза в секунду во всех
   режимах, включая «Норма». Кнопка ручного обновления не является основным
   способом получения телеметрии.
3. Единица представления - вольты, два знака после запятой. Передача по BLE -
   целое число милливольт без float.
4. Для каждого канала передаётся отдельный признак достоверности. Значение 0 В
   без признака достоверности не считается измерением.
5. Приложение не имеет права копировать одно измерение в строки +1, +2 и +Т.
   Канал без физического измерения показывается как «Нет данных».
6. При отсутствии нового STATE_REPORT более трёх периодов обновления значения
   считаются устаревшими; приложение показывает состояние восстановления связи.
7. Опрос приостанавливается на время выгрузки журнала и выполнения команды,
   после чего возобновляется автоматически.
8. Расширенный STATE_REPORT сохраняет совместимый 17-байтовый префикс. Новые
   поля добавляются в конец: +1, +2, +Т, резерв - по uint16 LE в милливольтах.

## Требования к аппаратной части

Для независимого отображения четырёх напряжений должны существовать четыре
независимых измерительных тракта либо внешний многоканальный ADC/аналоговый
мультиплексор с гарантированным временем полного цикла не более 1 секунды.

На текущей PB-03F-конфигурации прошивка получает только:

- общий канал ДПЛС на P20, публикуемый как +1/совместимый канал;
- напряжение резервного накопителя на P23.

Отдельных физических каналов +2 и +Т в текущей распиновке нет. До ревизии
аппаратуры их validity-флаги остаются сброшенными, а приложение показывает
«Нет данных». Подстановка общего напряжения ДПЛС запрещена.

## Приёмка

1. В режиме «Норма» и каждом из пяти тестовых режимов приложение получает новый
   отчёт не реже одного раза в секунду.
2. Изменение каждого подключённого измерительного входа появляется на экране не
   позднее двух секунд с учётом окна усреднения ADC.
3. Отключённый или ещё не измеренный канал отображается как «Нет данных».
4. Старый 17-байтовый STATE_REPORT корректно читается: +1 берётся из legacy-поля,
   +2, +Т и цифровое значение резерва считаются недоступными.
5. Показания ДПЛС проверяются поверенным вольтметром по требованиям исходного ТЗ.
"""
requirements_path = ROOT / "docs/live-voltage-requirements.md"
if requirements_path.exists():
    raise SystemExit(f"{requirements_path}: already exists")
requirements_path.write_text(requirements, encoding="utf-8")

replace_all_checked(
    ".github/workflows/ci.yml",
    [
        (
            """          test -f docs/oss-register.md
          test -f tools/generate_behavior_sim.py""",
            """          test -f docs/oss-register.md
          test -f docs/live-voltage-requirements.md
          test -f tools/generate_behavior_sim.py""",
        ),
        (
            """          grep -q '\"firmware\":\"1.1.1\"' /tmp/test-dpls-behavior-sim.html
          grep -q 'DPLS_PIN_LINE_ADC' /tmp/test-dpls-behavior-sim.html""",
            """          grep -q '\"firmware\":\"1.1.2\"' /tmp/test-dpls-behavior-sim.html
          grep -q 'DPLS_PIN_LINE_ADC' /tmp/test-dpls-behavior-sim.html
          grep -q 'state.authenticated && !state.commandInProgress && state.state != null' TestDPLS/app/src/main/java/ru/bolid/testdpls/ble/BleClient.kt
          grep -q 'port2VoltageValid' TestDPLS/app/src/main/java/ru/bolid/testdpls/ble/BleClient.kt""",
        ),
    ],
)

replace_global(".github/workflows/prerelease.yml", "1.1.1-rc.1", "1.1.2-rc.1")
replace_global(".github/workflows/prerelease.yml", "1.1.0-rc.1", "1.1.1-rc.1")
replace_all_checked(
    ".github/workflows/prerelease.yml",
    [
        (
            """          cp docs/oss-register.md dist/docs/
          cp TestDPLS/BUILD.md dist/docs/android-build.md""",
            """          cp docs/oss-register.md dist/docs/
          cp docs/live-voltage-requirements.md dist/docs/
          cp TestDPLS/BUILD.md dist/docs/android-build.md""",
        ),
        (
            """          - единственный образ прошивки PHY6252 с ADC;
          - последовательный опрос P20/P23 вместо одновременного запуска каналов;""",
            """          - единственный образ прошивки PHY6252 с ADC;
          - автоматическое обновление напряжений каждую секунду во всех режимах;
          - расширенная телеметрия +1, +2, +Т и резерва с отдельными validity-флагами;
          - последовательный опрос P20/P23 вместо одновременного запуска каналов;""",
        ),
    ],
)

print("live-voltage migration applied")
