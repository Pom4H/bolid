# Test-DPLS lab

Host стенд: N процессов `dpls_simulator`, нативный BLE (central/peripheral) и тот же Compose `DplsApp`, что на телефоне, в wasm (`mobile/web`). Отдельной React-копии протокола нет.

```sh
bash tools/dpls_lab.sh
# http://127.0.0.1:8787
```

Скрипт собирает симулятор, `dpls-ble` (если исходник новее бинарника), wasm-телефон и поднимает Bun на порту 8787.

## Что на экране

- **Слева** — GPIO, напряжения, очереди ATT, лабораторные неисправности выбранного сима.
- **Справа** — iframe `/phone/`: Kotlin Compose wasm. Скан/логин/режимы идут в тот же `DplsClient`, транспорт — WebSocket на хаб (`LabBleTransport`).
- **Ростер** — симы и реальные платы с ноутбука. Подпись `сим|плата · fw · эфир · link`.
- **+ сим** — ещё один `dpls_simulator` с выбранной прошивкой из каталога (текущая **1.4.0** и выпущенные 1.3.0…1.1.0). `--fw` меняет advertized identity, не checkout старого дерева.
- **BLE сервер** — ноутбук рекламирует выбранный сим. На macOS radio в один момент либо peripheral, либо central.
- **Найти плату** — native central scan; в списке телефона появляются физические Test-DPLS.

Заводской пароль сима: `TestDpls01`.

## BLE на Mac

`tools/dpls-lab/native/DplsBle.swift` → `native/dpls-ble` (бинарник в `.gitignore`).

| Роль | Зачем |
|---|---|
| `peripheral` | телефон видит сим в эфире |
| `central` | lab видит настоящую PHY6252 |

Manufacturer data в ADV с Mac CoreBluetooth обычно срезается. Имя в том же PDU, что 128-bit service UUID, ужимается до 8 символов `DPLSXXXX` (`DplsAdvertisement.compactAirName`); Android показывает `Test-DPLS-XXXX`. Сопряжение `createBond()` нужно плате PHY6252; для lab-сервера без 0x0B01 в scan record телефон идёт в GATT без bond. CCCD: плата Samsung — `0x03`, Mac peripheral может отвергнуть `0x03` (GATT 245) — тот же `AndroidBleTransport` пишет `0x01`.

## Без телефона

Продуктовый путь `DplsClient` ↔ `dpls_simulator` без радио:

```sh
bash tools/soft_ble_e2e.sh
```

Lab на 8787 нужен, чтобы руками крутить стенд и wasm-телефон в браузере. Физический Samsung/плата — только для эфира и pairing.
