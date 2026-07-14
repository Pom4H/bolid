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

Миграционная сборка использует штатный `components/driver/adc/adc.c` SDK 3.1.2.
Самописный backport ADC больше не нужен.

## Изменения API 3.1.2

В target-сборке учтены обнаруженные несовместимости:

- `GATT_MAX_NUM_CONN=MAX_NUM_LL_CONN+1`;
- `CFG_HCLK_DYNAMIC_CHANGE=0`;
- GATT read callback: `uint16 *length` вместо `uint8 *length`;
- GATT write callback: `uint16 length` вместо `uint8 length`;
- ADC запускается через `hal_adc_start(INTERRUPT_MODE)`;
- CMSIS RTE startup генерируется с `cbuild --update-rte`.

Пока ветка миграции не слита, две ADC-правки применяются к общей версии
`dpls_phy6252_app.c` проверяемым скриптом
`tools/prepare_phy6252_sdk312_app.py`. Скрипт требует ровно одно совпадение для
каждого изменения и восстанавливает исходник после сборки. После принятия
миграции эти изменения следует внести непосредственно в основной адаптер и
удалить migration shim.

## Результат target build

GitHub Actions успешно собрал прошивку Arm Compiler 6.24 с полным SDK 3.1.2 и
включённым ADC P20 + P23.

Размер образа:

- Code: 74 382 байта;
- RO-data: 2 446 байт;
- RW-data: 1 856 байт;
- ZI-data: 10 504 байта;
- `ER_IROM1`: 29 424 из 30 664 байт, свободно 1 240 байт;
- `ER_IROM2`: 3 808 из 16 384 байт;
- `ER_ROM_XIP`: 53 908 из 131 072 байт.

То есть target помещается, но retained-регион `ER_IROM1` остаётся тесным — около
4 % запаса. Новые крупные статические буферы туда добавлять нельзя без анализа
MAP-файла и перераскладки секций.

## Локальная сборка

1. Активировать инструменты из:

   `Firmware/targets/phy6252/vcpkg-configuration.json`

2. Активировать лицензию Arm Compiler 6 (Keil MDK Community, бесплатная).
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

Сборка использует бесплатную лицензию Arm Compiler 6 (Keil MDK Community,
`KEMDK-COM0`) на всех событиях, включая push в `main`. Secret не требуется.

## Что проверяет CI, а что остаётся на плате

CI уже подтверждает:

- совместимость исходников с заголовками и библиотеками SDK 3.1.2;
- компиляцию ADC-кода с `INTERRUPT_MODE`;
- линковку через AC6 и scatter-файл;
- фактический размер Flash/SRAM;
- создание AXF, MAP и flashable HEX;
- прохождение host tests и Android build/tests.

Остались аппаратные проверки:

1. boot и BLE advertising с новым SDK;
2. подключение, bonding и MTU/DLE;
3. P20 + P23 one-shot ADC при advertising;
4. ADC при подключении и активном GATT traffic;
5. отсутствие watchdog reset не менее двух часов;
6. sleep/wakeup и ток потребления;
7. проверка диапазона и калибровка обоих делителей.

До завершения этих пунктов миграционный PR остаётся draft.
