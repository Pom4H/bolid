# iOS host Test-DPLS

Эта директория намеренно маленькая. Product controller, UI, protocol/session logic и CoreBluetooth adapter находятся в Kotlin-коде `../core/`.

## Что остаётся в Xcode shell

| Путь | Ответственность |
|---|---|
| `TestDPLS/TestDPLSApp.swift` | минимальный Swift bootstrap для Compose `UIViewController` |
| `TestDPLS/Info.plist` | Bluetooth permissions, background mode, metadata |
| `TestDPLS/Resources/` | icon/assets |
| `TestDPLSTests/` | native smoke экспортированного KMP entry point |
| `TestDPLS.xcodeproj/` | signing, build settings, Gradle framework integration |

Отдельного Swift BLE client, protocol codec, crypto/domain model, application controller или SwiftUI-копии экранов нет.

```text
core/commonMain/DplsClient.kt      общий controller
core/commonMain/DplsApp.kt         общий Android+iOS UI
core/iosMain/IosBleTransport.kt    CoreBluetooth adapter
core/iosMain/IosPlatform.kt        Apple services
core/iosMain/IosApp.kt             Compose UIViewController
TestDPLSApp.swift                   минимальный Xcode bootstrap
```

## Xcode integration

Build phase `Build DplsCore` выполняется до Swift compilation:

```sh
cd "$SRCROOT/.."
./gradlew :core:embedAndSignAppleFrameworkForXcode
```

Swift импортирует `DplsCore` и вызывает `IosAppKt.MainViewController()`.

Требования: macOS, Xcode, Java 17.

```sh
cd mobile
./gradlew :core:iosSimulatorArm64Test
./gradlew :core:linkDebugFrameworkIosSimulatorArm64
open ios/TestDPLS.xcodeproj
```

Или весь mobile loop:

```sh
bash tools/check_mobile.sh
```

Для реального iPhone нужно выбрать Development Team в Signing & Capabilities. Simulator проверяет build/KMP integration, но не заменяет реальное BLE испытание.

## Особенности iOS

- приложение не получает BLE MAC; endpoint — `CBPeripheral.identifier`;
- `Test-DPLS-XXXX` — только radio/display name, не полный `NodeId`;
- полный device identity подтверждается `DEVICE_INFO_REPORT`;
- CoreBluetooth сам управляет ATT MTU, transport использует `maximumWriteValueLengthForType(.withResponse)`;
- pairing инициируется системой при доступе к защищённому GATT;
- callbacks остаются в `iosMain`, product behavior и UI — в `commonMain`.
