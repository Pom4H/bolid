# Серийная идентификация Test-DPLS

Этот документ описывает единственную актуальную схему идентификации Test-DPLS на PHY6252/PB-03F.

До выхода в серию обратная совместимость со старыми экспериментальными способами идентификации намеренно не поддерживается. Плата без корректного factory record считается непровиженной и не должна начинать BLE advertising.

## 1. Состав идентичности

Идентичность прибора разделена на независимые сущности:

| Сущность | Назначение | Меняется при эксплуатации |
|---|---|---|
| `serial_number` | основной ID прибора в протоколе, приложении и производственной БД | нет |
| BLE address | транспортный адрес Bluetooth LE | нет |
| IRK / CSRK | постоянные BLE identity/signing keys | нет |
| hardware revision | ревизия платы | нет |
| пользовательское имя | имя, задаваемое приложением | да |
| пароль/верификатор | прикладная аутентификация Test-DPLS | да |
| bonds | сопряжения телефонов | да |
| журнал | эксплуатационные события | да |

`device_id` в wire-протоколе остаётся 32-битным и равен `serial_number` из factory record.

BLE MAC больше не используется как бизнес-идентификатор прибора.

## 2. Что находится в эфире

Текущая прошивка рекламирует только:

- стандартные BLE flags;
- фирменный 128-bit Service UUID `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001`;
- local name `Test-DPLS-XXXX`, где `XXXX` — младшие 16 бит `serial_number` в HEX.

Manufacturer Specific Data не используется. В частности, прошивка не передаёт `0x0B01` и мобильное приложение не содержит parser старого manufacturer payload.

Полная информация о приборе читается после подключения через `DEVICE_INFO_REPORT`:

- полный `device_id` / `serial_number`;
- версия wire protocol;
- версия firmware;
- hardware revision;
- capabilities;
- пользовательское имя.

Именно эти данные должны использоваться экраном «Об устройстве». Для firmware 1.4.2 приложение после подключения получает `1.4.2` из `DEVICE_INFO_REPORT`; версия больше не зависит от BLE manufacturer data.

## 3. Карта flash

Persistent data физически исключены из linker-области приложения:

```text
0x11020000 ─┐
            │ application XIP
            │ 0x1C000 bytes
0x1103BFFF ─┘

0x1103C000 ─┐
            │ SNV filesystem, 3 × 4 KiB
0x1103EFFF ─┘

0x1103F000 ─┐
            │ FACTORY IDENTITY, 4 KiB
0x1103FFFF ─┘
```

`scatter_load.sct` и `phy6252.ld` заканчивают application XIP на `0x1103BFFF`. Рост прошивки поэтому должен привести к linker overflow, а не к перезаписи SNV или factory identity.

Рабочая firmware только читает factory record. API изменения или стирания factory identity в приложении нет.

Factory sector не является OTP/eFuse: полный chip erase стирает его физически. Поэтому `tools/flash_firmware.sh --erase` требует явного `DPLS_ALLOW_FACTORY_ERASE=1`.

## 4. Factory record v1

В начале `0x1103F000` хранится 64-байтная запись:

| Offset | Размер | Поле |
|---:|---:|---|
| `0` | 4 | magic `DID1` |
| `4` | 2 | версия = `1` |
| `6` | 2 | размер = `64` |
| `8` | 4 | `serial_number`, LE32 |
| `12` | 2 | hardware revision |
| `14` | 2 | flags |
| `16` | 6 | BLE static-random address или `FF..FF` |
| `22` | 1 | BLE address source/type |
| `23` | 1 | reserved |
| `24` | 16 | IRK |
| `40` | 16 | CSRK |
| `56` | 6 | reserved |
| `62` | 2 | CRC16-CCITT-FALSE по байтам `0..61` |

Flags v1:

```text
bit 0  в record записан BLE static-random address
bit 1  IRK присутствует
bit 2  CSRK присутствует
```

Валидная запись обязана содержать:

- serial в диапазоне `1..0xFFFFFFFE`;
- корректный CRC;
- только известные flags;
- IRK и CSRK;
- корректный тип BLE address.

При любой ошибке record отклоняется целиком.

## 5. BLE address

Алгоритм выбора адреса только один:

```text
валидный factory record?
        │
        ├─ нет → identity не готова → advertising запрещён
        │
        └─ да
             │
             ├─ record содержит static-random address
             │      → ADDRTYPE_STATIC
             │
             └─ static address не задан
                    → использовать заводской public MAC PHY6252
                    → если MAC невалиден, identity не готова
```

Runtime-генерации MAC нет. SNV не является источником BLE identity.

Для обычной PB-03F предпочтительно использовать заводской public MAC чипа и не дублировать его в record.

Если конкретная партия не имеет пригодного заводского MAC, provisioning генерирует BLE static-random address. Его два старших бита должны быть `11`.

Factory record хранит адрес в человекочитаемом порядке, например `C2:34:56:78:9A:BC`. Перед передачей в PHY6252 SDK адрес явно переводится в `B_ADDR` byte order.

## 6. Старт PHY6252 и фикс из PR #32

PR #32 выявил реальную гонку старта PHY6252: `HCI_EXT_SetBDADDRCmd()` может временно отклонить установку public address до полного запуска BLE controller.

Текущая схема учитывает это:

1. factory record читается до `GAPRole_StartDevice()`;
2. после `GAPROLE_STARTED` выполняется применение BLE address;
3. адрес проверяется через GAP/HCI state;
4. только после успешного применения identity разрешается advertising;
5. если controller временно не готов, idle tick повторяет применение identity.

Поэтому устройство не должно появляться в эфире с дефолтным/нулевым/`FF:FF:...` адресом или как `Test-DPLS-0000`.

## 7. IRK и CSRK

IRK и CSRK создаются один раз на производственной станции криптографическим RNG и записываются в factory record.

Firmware загружает только factory keys. Генерации identity keys при первом boot и fallback на SNV нет.

Factory reset может очистить runtime/SNV копии, но после reboot те же IRK/CSRK снова загружаются из factory sector.

## 8. Pairing

Pairing не определяется по advertising marker.

CCCD Test-DPLS требует `GATT_PERMIT_ENCRYPT_WRITE`. Поэтому штатный поток выглядит так:

1. телефон подключается к Service UUID;
2. обнаруживает RX/TX;
3. пытается записать CCCD;
4. PHY6252 возвращает authentication/encryption error, если link ещё не защищён;
5. Android запускает bonding и повторяет CCCD;
6. iOS/CoreBluetooth инициирует системный security flow при доступе к защищённому атрибуту.

Так security boundary задана самим GATT, а не форматом рекламы.

## 9. Генерация factory record

Для штатного PHY62x2 programmer создаётся 64-байтный BIN:

```sh
python3 tools/make_factory_identity.py \
  --serial 12874 \
  --hw-revision 2 \
  --binary-output tmp/factory-00012874.bin \
  --metadata tmp/factory-00012874.json
```

По умолчанию:

- используется заводской public MAC PHY6252;
- IRK и CSRK генерируются один раз;
- BIN содержит ровно 64 байта;
- BIN содержит секретные ключи и создаётся с правами `0600`, где ОС это поддерживает;
- metadata JSON не содержит IRK/CSRK;
- metadata содержит SHA-256 factory record.

Если нужен static-random address:

```sh
python3 tools/make_factory_identity.py \
  --serial 12874 \
  --generate-static-address \
  --binary-output tmp/factory-00012874.bin \
  --metadata tmp/factory-00012874.json
```

Или можно передать заранее выделенный адрес:

```sh
python3 tools/make_factory_identity.py \
  --serial 12874 \
  --static-address C2:34:56:78:9A:BC \
  --binary-output tmp/factory-00012874.bin
```

## 10. Почему factory data не шьётся через `wh`

`rdwr_phy62x2.py wh` предназначен для application HEX. Он строит application segment table и пишет служебный header.

Поэтому standalone factory HEX нельзя передавать в `wh`: даже HEX только с `0x1103F000` способен изменить application header.

Штатный provisioning использует raw binary write:

```text
rdwr_phy62x2.py -r we 0x3F000 factory.bin
```

Для этого есть отдельный скрипт:

```sh
tools/flash_factory_identity.sh tmp/factory-00012874.bin
```

Скрипт принимает только файл размером ровно 64 байта и пишет его в сектор `0x3F000`, не затрагивая application header и SNV.

## 11. Производственный поток

Для каждой платы:

1. производственная БД выдаёт следующий уникальный `serial_number`;
2. станция формирует factory BIN и metadata JSON;
3. прошивается application firmware;
4. отдельной командой записывается factory BIN;
5. плата перезапускается;
6. проверяется BLE name `Test-DPLS-XXXX`;
7. проверяется фактический BLE address;
8. выполняется подключение по Service UUID;
9. выполняется pairing;
10. читается `DEVICE_INFO_REPORT`;
11. проверяется полный `device_id == serial_number`;
12. проверяется firmware version `1.4.2`;
13. проверяется hardware revision;
14. результат записывается в production DB.

Для текущего стенда:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex
tools/flash_factory_identity.sh tmp/factory-00012874.bin
```

Не использовать полный erase между этими шагами.

## 12. Производственная БД

Минимальные поля на прибор:

```text
serial_number
hardware_revision
chip_factory_mac
ble_address_source       # chip_public / static_random
ble_address              # фактически прочитанный адрес
factory_record_sha256
firmware_version_at_test
production_timestamp
test_station
test_result
```

IRK/CSRK нельзя писать в обычные логи, CSV оператора или GitHub artifacts.

Если резервное хранение секретов понадобится, оно должно быть отдельным защищённым хранилищем с контролем доступа.

## 13. Factory reset и обновление firmware

Обычный factory reset может очищать:

- пользовательский пароль/верификатор;
- пользовательское имя;
- bonds;
- runtime lock state;
- прочие пользовательские настройки.

Он не должен менять:

- `serial_number`;
- BLE address;
- factory IRK/CSRK;
- hardware revision.

Обычная перепрошивка application image также не затрагивает `0x1103F000`.

После намеренного chip erase factory identity должна быть заново восстановлена из производственных данных и полностью перепроверена до эксплуатации прибора.

## 14. Автоматические проверки

`tools/test_factory_identity.py` фиксирует архитектурные инварианты:

- обязательный factory record;
- отсутствие SNV-MAC fallback;
- отсутствие `device_id` из MAC;
- отсутствие runtime-генерации identity keys;
- CRC и обязательные IRK/CSRK;
- корректный static-random address;
- явный перевод display address → PHY6252 `B_ADDR`;
- post-`GAPROLE_STARTED` retry из PR #32;
- запрет advertising до готовности identity;
- отсутствие Manufacturer Specific Data / `0x0B01`;
- отсутствие manufacturer parser в Android/iOS/common mobile;
- encrypted CCCD;
- границы application/SNV/factory в linker;
- безопасный raw provisioning через `we 0x3F000`.

Тест входит в `tools/check_all.sh`.

## 15. Критерии готовности к серии

Перед выпуском серии на реальном стенде проверить:

- минимум 10 плат получают разные serial;
- плата без factory record не появляется в BLE scan;
- повреждённый CRC блокирует advertising;
- `device_id` совпадает с production serial;
- `device_id` не меняется после reboot;
- BLE address не меняется после reboot;
- обычная перепрошивка не меняет serial/address/IRK;
- factory reset не меняет serial/address/IRK;
- удаление SNV не меняет factory identity;
- firmware `1.4.2` корректно отображается в «Об устройстве» после `DEVICE_INFO_REPORT`;
- пользовательское имя, записанное через `NAME_SET`, читается с самой платы на другом телефоне после `DEVICE_INFO_REPORT`;
- Android и iOS успешно проходят pairing без Manufacturer Specific Data;
- factory provisioning не меняет application header и SNV;
- две платы с одинаковым serial не могут пройти production acceptance.

После выполнения этих проверок источником истины являются production DB + factory record. BLE MAC остаётся только транспортным адресом.
