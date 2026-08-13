# Сборка PHY6252 в Keil / Arm Compiler 6

Keil-вариант целевой прошивки Test-DPLS вынесен в отдельный entry point:

```sh
DPLS_ADC=1 tools/build_firmware_keil.sh tmp/test-dpls-sdk-3.1.2.hex
DPLS_ADC=0 tools/build_firmware_keil.sh tmp/test-dpls-adcoff.hex
```

`tools/build_firmware.sh` остаётся совместимым wrapper'ом и вызывает тот же Keil build.

## Что требуется

Сборка использует существующий target `Firmware/targets/phy6252/`:

- CMSIS-Toolbox `cbuild`;
- Arm Compiler 6;
- `fromelf`;
- конфигурацию `Firmware/targets/phy6252/vcpkg-configuration.json`;
- закреплённый PHY62XX SDK 3.1.2, который получает `tools/fetch_phy6252_sdk.sh`.

В CI toolchain и бесплатная Arm Community license активируются автоматически через `ARM-software/cmsis-actions`.

## Результат

Скрипт строит AXF, раскладывает load regions через `fromelf` и собирает один прошивочный Intel HEX из:

- `ER_ROM_XIP`;
- `JUMP_TABLE`;
- `ER_IROM1`.

GitHub Actions workflow `.github/workflows/firmware-target.yml` собирает оба варианта — с ADC и без ADC — и сохраняет HEX, AXF, MAP и build logs в артефакт `test-dpls-phy6252-keil-sdk-3.1.2`.
