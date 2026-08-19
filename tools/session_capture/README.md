# Запись реальных BLE-сессий

Цель — записывать поведение **реального телефона ↔ реальной платы PHY6252** и использовать трассы для повышения точности `firmware/sim/dpls_simulator.c` и soft-BLE сценариев.

Replay использует `CCCD 3`, затем `LAB`: это воспроизводит проверенное поведение Samsung notify и лабораторных напряжений PB-03F. Notify TX продвигается через `TICK`, а не `CONFIRM`.

Host simulator не считается эквивалентом реального PHY6252: трассы нужны именно для обнаружения расхождений.

## Быстрый сценарий

```sh
# 1. Debug APK на телефоне, плата включена и advertising активен

# 2. Запись logcat; Ctrl-C или --duration N для остановки
python3 tools/session_capture/record_session.py --name lab

# при необходимости одновременно UART
python3 tools/session_capture/record_session.py --name lab --serial /dev/ttyUSB0

# 3. Разбор в NDJSON и список frames
python3 tools/session_capture/parse_session.py tmp/sessions/session-*-lab.log

# 4. Replay phone→device writes в simulator
cmake -S firmware -B firmware/build
cmake --build firmware/build --target dpls_simulator
python3 tools/session_capture/replay_to_simulator.py \
  tmp/sessions/session-*-lab.frames.txt \
  --simulator firmware/build/dpls_simulator
```

Артефакты создаются в `tmp/sessions/`; `tmp/` исключён из git.

## Что записывается

| Источник | Tag/stream | Содержимое |
|---|---|---|
| Android BLE | `TestDplsBle` | GATT lifecycle, TX/RX frames |
| `DplsClient` | `TestDplsSession` | E2E markers, `STATE`, journal events |
| E2E driver | `TestDplsE2e` | автоматизация режимов/export |
| UART | serial | boot/vendor diagnostics |

## Offline smoke

```sh
python3 tools/session_capture/test_session_capture.py
```

## Как использовать трассы

1. Сопоставить simulator `TX` с captured `RX` для одного request sequence.
2. Любое подтверждённое расхождение превратить в host test или soft-BLE scenario.
3. При необходимости добавить узкие UART breadcrumbs в firmware.

Трассы не должны использоваться для возврата старого advertising/manufacturer контракта: текущая production-схема не содержит Manufacturer Specific Data.
