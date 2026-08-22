# Mobile Test-DPLS

Один Kotlin Multiplatform application для Android и iOS.

Текущая версия приложений — **1.5.0**. PHY6252 firmware — **1.5.0**. Wire protocol — v2.

## Где находится код

| Путь | Ответственность |
|---|---|
| `core/src/commonMain/` | `DplsClient`, Compose UI, protocol/crypto/parsers/domain/session orchestration |
| `core/src/commonTest/` | общие unit/contract tests |
| `core/src/androidMain/` | `AndroidBleTransport`, Android services/alerts/prefs |
| `core/src/iosMain/` | `IosBleTransport`, Apple services и Compose UIViewController |
| `runtime/` | `NodeId`, session lifecycle, endpoint и sequencing |
| `wire/` | низкоуровневый wire/CRC/crypto/radio-name helpers |
| `android/` | permissions и Activity/Application shell |
| `ios/` | Xcode shell, plist/assets/signing |
| `web/` | тот же Compose UI поверх lab transport |

Правило: если Android и iOS должны показать одинаковое поведение, оно находится в common Kotlin code.

## BLE identity и discovery

Приложение сканирует по фирменному Service UUID:

`7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001`

Radio name имеет вид `Test-DPLS-XXXX`, где `XXXX` — младшие 16 бит serial. Этот суффикс используется только для отображения и **не считается полным deviceId**.

Полный 32-битный `NodeId` становится известен только после `DEVICE_INFO_REPORT`.

Manufacturer Specific Data в текущем контракте нет. Firmware version/status не читаются из advertising.

## Credentials

Verifier долговременно привязывается к подтверждённому `NodeId` (`node:<serial>`). До первого `DEVICE_INFO_REPORT` после commissioning допустим временный cache `endpoint:<BLE endpoint>`, необходимый для reconnect после reboot платы.

Старые migration aliases `id:`, `addr:` и `legacy-addr:` не поддерживаются.

## Android

`AndroidBleTransport` отвечает за:

- service-UUID scan;
- `BluetoothGatt` lifecycle;
- MTU;
- CCCD;
- bonding по `INSUFFICIENT_AUTHENTICATION/ENCRYPTION`;
- transient retries и stale-bond recovery.

Pairing не определяется по advertising: TX CCCD на firmware требует encrypted write.

## iOS

`IosBleTransport` использует CoreBluetooth. iOS не предоставляет приложению BLE MAC; transport endpoint — `CBPeripheral.identifier`.

CoreBluetooth инициирует pairing, когда защищённая GATT операция требует encryption/authentication.

## Быстрый цикл разработки

```sh
bash tools/check_mobile.sh
```

Отдельно Android/JVM:

```sh
cd mobile
./gradlew :core:testDebugUnitTest
./gradlew :core:lintDebug :android:lintDebug :android:assembleDebug
```

macOS/iOS:

```sh
./gradlew :core:iosSimulatorArm64Test
./gradlew :core:linkDebugFrameworkIosSimulatorArm64
open ios/TestDPLS.xcodeproj
```

Для Gradle/Xcode integration требуется Java 17.

## GATT

| Элемент | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

UI считает hardware mode подтверждённым только после matching `COMMAND_RESULT` и `STATE_REPORT`, а не после успешного GATT write.

## Тестовая стратегия

Общие tests покрывают wire/CRC/crypto, binary contracts, session transitions, reconnect, stale responses, settings, journal и malformed/random frames. Platform tests проверяют интеграцию OS API, а реальное BLE/pairing остаётся hardware E2E.
