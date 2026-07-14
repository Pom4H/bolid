# Переход Test-DPLS на PHY62XX SDK 3.1.2

## Решение

Прошивка собирается на полном PHY62XX SDK 3.1.2 для PHY6222/PHY6252. SDK
получается из `xuhongv/PHY6252_6222_SDK` и закреплён commit SHA в
`Firmware/sdk/phy6252-sdk.env`.

Vendor SDK не редактируется и не копируется в историю нашего репозитория:

- полный SDK загружается в `Firmware/sdk/PHY62XX_SDK_3.1.2`;
- product target находится отдельно в `Firmware/targets/phy6252`;
- исходники ядра и адаптера остаются в `Firmware/src` и `Firmware/phy6252`;
- `tools/fetch_phy6252_sdk.sh` проверяет точный SHA перед сборкой.

Это устраняет старую схему, где product-файлы и абсолютные пути
`/Users/rom/...` были встроены внутрь дерева SDK 3.1.1.

## Почему полный переход, а не backport ADC

В 3.1.2 изменены не только ADC-функции. Обновлены `rf.lib`, `ble_host.lib`,
power manager, flash и BLE examples. Отдельное копирование `adc.c` оставляло бы
гибрид из компонентов двух SDK и усложняло бы диагностику sleep/radio/ADC.

После успешного target build и аппаратного soak ветка с самописной заменой ADC
не нужна.

## Локальная сборка

1. Активировать инструменты из:

   `Firmware/targets/phy6252/vcpkg-configuration.json`

2. Активировать коммерческую лицензию Arm Compiler 6.
3. Запустить:

```sh
bash tools/build_firmware.sh
```

Скрипт сам загрузит закреплённый SDK и создаст:

`tmp/test-dpls-sdk-3.1.2.hex`

## GitHub Actions

Workflow: `.github/workflows/firmware-target.yml`.

Он использует официальные actions:

- `ARM-software/cmsis-actions/vcpkg@v1` для CMSIS Toolbox и AC6;
- `ARM-software/cmsis-actions/armlm@v1` для лицензии;
- `cbuild` для target image;
- `fromelf` для формирования flashable HEX.

В настройках репозитория требуется secret:

`ARM_LICENSE_CODE`

Без secret workflow намеренно падает. Бесплатная лицензия `KEMDK-COM0`, которую
`armlm@v1` может активировать без параметров, по документации Arm предназначена
для evaluation/non-commercial использования и поэтому не применяется в CI
коммерческого устройства.

## Что проверяет CI, а что остаётся на плате

CI подтверждает:

- совместимость исходников с заголовками и библиотеками SDK 3.1.2;
- линковку через AC6 и scatter-файл;
- фактический размер Flash/SRAM;
- создание AXF, MAP и HEX.

После зелёной target-сборки нужны аппаратные проверки:

1. boot и BLE advertising без ADC;
2. подключение, bonding и MTU/DLE;
3. включение ADC API 3.1.2 в interrupt mode;
4. P20 one-shot soak;
5. P20 + P23 soak при активном GATT traffic;
6. sleep/wakeup и ток потребления;
7. двухчасовой прогон без watchdog reset.

До завершения этих пунктов миграционный PR должен оставаться draft.
