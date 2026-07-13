# BLE-сервер Test-DPLS для PHY6252

Каталог содержит переносимое ядро безопасного сервера и адаптер GATT для PHY62XX SDK 3.1.1.

## Реализовано

- бинарные кадры `version/type/flags/sequence/length/payload/CRC16-CCITT`;
- challenge-response аутентификация через HAL, блокировка после 5 ошибок на 300 с;
- токен сессии и проверка каждой защищённой команды;
- идемпотентный `MODE_SET` (cache 8 command ID);
- подтверждение команды только после успешного аппаратного переключения;
- независимый возврат в `NORMAL` через 5 минут, при потере/тайм-ауте сессии и низком резерве;
- идентификация с аппаратным пределом 60 с;
- персистентный кольцевой журнал на 200 записей во Flash и оконная выгрузка;
- RX только через зашифрованную GATT characteristic, TX через indication/notification.

UUID совпадают с Android-клиентом:

- Service `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001`
- RX `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001`
- TX `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001`

## Проверка ядра на хосте

```sh
cmake -S . -B build
cmake --build build
ctest --test-dir build --output-on-failure
```

## Интеграция с PHY6252

1. Взять `example/ble_peripheral/simpleBlePeripheral` из `PHY62XX_SDK_3.1.1.zip`.
2. Добавить `src/dpls_protocol.c`, `src/dpls_server.c`, `phy6252/dpls_gatt_service.c` и каталоги заголовков в Keil-проект.
3. До запуска BLE вызвать безопасную инициализацию GPIO и `dpls_server_init()`; аппаратная реализация `hardware_safe_normal` обязана выставить состояние «Норма».
4. Зарегистрировать `dpls_gatt_add_service()` и передавать RX callback в `dpls_server_receive()`.
5. Из GAP callbacks вызывать `dpls_server_connected()`/`dpls_server_disconnected()`; из периодического OSAL event (100–250 мс) — `dpls_server_tick()`.
6. Реализовать HAL: ключи/реле, ADC, питание, LED, случайные числа, flash settings и HMAC-SHA256/PBKDF2 verifier. Адаптер PHY6252 уже хранит журнал в SDK FS/SNV.
7. В advertising добавить 128-bit Service UUID и manufacturer data: ID устройства little-endian с company ID `0x0B01`; имя `Test-DPLS-XXXX`.

Для этой ревизии handshake использует кадры до 57 байт, поэтому в SDK следует поднять `MTU_SIZE` с заводских 23 до 247 и оставить Android-запрос MTU 247. Транспортная фрагментация управляющих кадров для режима ATT MTU 23 остаётся следующим совместимостным шагом; журнал уже передаётся короткими оконными chunk-сообщениями.

Журнал использует 20 SNV-блоков `0x90..0xA3` по 10 записей. Каждая запись имеет собственный CRC. При старте прошивка восстанавливает только `count/next_sequence`, не загружая события в ОЗУ. Во время выгрузки в RAM кэшируется только один Flash-блок (10 записей, 120 байт), а каждый `LOG_ACK` отправляет одну запись в `LOG_CHUNK`; снимок границ журнала фиксируется в `LOG_START`. Полный erase Flash стирает журнал, обычное обновление области приложения — сохраняет.

`dpls_gatt_service.c` рассчитан на API SDK 3.1.1. Конечный `.hex` собирается ARM Compiler 6 через CMSIS-Toolbox (`csolution` в `example/ble_peripheral/simpleBlePeripheral/ac6/`, toolchain ставится `vcpkg activate`); запуск сборки и склейка прошивочного hex — `tools/build_firmware.sh` из корня репозитория.

Пины аппаратных ключей, коэффициент ADC, вход резерва и кнопка физического сброса намеренно находятся в HAL: в предоставленных материалах нет схемы `АЦДР.469445.816 Э3`, поэтому безопасно угадать эти назначения невозможно.
