# Сборка Android-клиента Test-DPLS

## Требования

- JDK 17;
- Android SDK Platform 35;
- Android Build Tools, устанавливаемые Gradle/Android Studio;
- доступ к Maven Central и Google Maven.

## Проверка

Из каталога `TestDPLS`:

```bash
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease
```

Результаты:

- debug APK: `app/build/outputs/apk/debug/app-debug.apk`;
- unsigned release APK без локального keystore: `app/build/outputs/apk/release/`;
- release AAB: `app/build/outputs/bundle/release/`;
- unit-test report: `app/build/reports/tests/testDebugUnitTest/`;
- lint report: `app/build/reports/lint-results-debug.html`.

## Подпись release

Создать `TestDPLS/keystore.properties` локально, не добавляя его и keystore в Git:

```properties
storeFile=/absolute/path/test-dpls.jks
storePassword=...
keyAlias=test-dpls
keyPassword=...
```

После этого `assembleRelease` и `bundleRelease` используют указанную подпись.

## Поддерживаемые версии

- `minSdk 26` — Android 8.0;
- `targetSdk 35`;
- портретная ориентация;
- разрешения BLE запрашиваются отдельно для Android 8–11 и Android 12+.

GitHub Actions выполняет ту же последовательность задач и сохраняет APK/AAB и отчёты как workflow artifacts.
