# Сборка Android-клиента Test-DPLS

## Требования

- JDK 17;
- Android SDK Platform 35;
- Android Build Tools, устанавливаемые Gradle/Android Studio;
- доступ к Maven Central и Google Maven.

## Проверка и release-сборка

Из каталога `TestDPLS`:

```bash
./gradlew --no-daemon clean testDebugUnitTest jacocoTestReport lintRelease assembleRelease bundleRelease
```

`testDebugUnitTest` используется только как JVM-вариант для unit-тестов и JaCoCo. Debug APK не собирается и не публикуется.

Результаты:

- minified release APK: `app/build/outputs/apk/release/`;
- minified release AAB: `app/build/outputs/bundle/release/`;
- R8/ProGuard mapping: `app/build/outputs/mapping/release/`;
- unit-test report: `app/build/reports/tests/testDebugUnitTest/`;
- coverage report: `app/build/reports/jacoco/`;
- release lint report: `app/build/reports/lint-results-release.html`.

Release-конфигурация включает `isMinifyEnabled = true`, `isShrinkResources = true` и `proguard-android-optimize.txt`.

## Подпись release

Создать `TestDPLS/keystore.properties` локально, не добавляя его и keystore в Git:

```properties
storeFile=/absolute/path/test-dpls.jks
storePassword=...
keyAlias=test-dpls
keyPassword=...
```

После этого `assembleRelease` и `bundleRelease` используют указанную подпись. Без `keystore.properties` Gradle создаёт неподписанные release APK/AAB.

## Поддерживаемые версии

- `minSdk 26` — Android 8.0;
- `targetSdk 35`;
- портретная ориентация;
- разрешения BLE запрашиваются отдельно для Android 8–11 и Android 12+.

GitHub Actions сохраняет только minified release APK/AAB, mapping и отчёты. Pre-release дополнительно публикует эти файлы в GitHub Release.
