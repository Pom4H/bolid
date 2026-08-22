# Changelog

Все заметные изменения Test-DPLS фиксируются в этом файле. Версии проекта
следуют Semantic Versioning; firmware, Android и iOS для релиза 1.5.0 имеют
одинаковую product version. Wire protocol остаётся на версии 2.

## [1.5.0] — 2026-08-22

### Firmware и безопасность

- PHY6252 runtime разделён на явных владельцев transport, storage, outputs,
  measurements, authentication, supervisor и power state вместо одного
  монолитного adapter.
- Периодический correctness tick заменён единым one-shot scheduler с явными
  дедлайнами. Перед обработкой RX сначала согласуются истёкшие safety-факты.
- Dangerous mode разрешается только при свежих измерениях, нормальном резерве,
  активной защищённой сессии и успешно удерживаемом sleep constraint.
- Disconnect, потеря измерений, низкий резерв, реальное КЗ, timeout и ошибка
  persistence детерминированно возвращают физические выходы в `NORMAL`.
- BLE transport использует подтверждаемые indications, ограниченные очереди и
  явные retry deadlines без постоянного TX pacing timer.
- Добавлены адаптивные BLE connection profiles: активный профиль для команд и
  safety-контроля, низкоактивный профиль для authenticated `NORMAL` idle.
- Persistent settings и журнал получили power-loss/fault проверки, единый
  авторитет sequence и UTC-or-unknown timestamp model.
- Восстановление factory BLE identity не требует разрушительного chip erase;
  обычная прошивка сохраняет factory MAC и SNV.
- Production image компилируется без UART traffic и с connected sleep.

### Android и iOS

- Общая KMP session machine стала единственным владельцем lifecycle и deadline
  соединения на обеих платформах.
- Исправлены pairing/reconnect сценарии Android и iOS, включая GATT 133,
  Samsung notification fallback и повторную защищённую подписку.
- Ошибки initial setup и device protocol теперь показываются по смыслу и могут
  безопасно повторяться без двойного применения транзакции.
- Низкий резерв блокирует опасные элементы управления до отправки команды.
- Журнал использует sequence как порядок событий, а время — только как UTC либо
  явно неизвестное значение; Android, iOS и web используют monotonic epoch clock.
- Версии Android и iOS синхронизированы с firmware: `1.5.0`.

### Сборка, Firmverse и аппаратная разработка

- Оставлен один production target: CMSIS-Toolbox 2.14.1 и Arm Compiler 6.24.0.
  Один и тот же HEX публикуется, исполняется в Firmverse и прошивается на PB-03F.
- CI доказывает цепочку `production HEX → PHY6252 ROM → virtual NOR → reset →
  boot → BLE advertising` и не допускает пересборку другого image для эмулятора.
- Firmverse закреплён на версии с корректной SDK vector table, BLE HCI model и
  безопасным PHY6252 flash harness.
- Добавлен отдельный dev-инструмент `tools/build_debug_firmware.sh`: UART trace,
  power-constraint counters и диагностический image тем же AC6 toolchain.
- Добавлен `tools/flash_debug_firmware.sh`. Первая установка требует один ручной
  вход через KEY1; следующие dev-обновления переводят приложение в ROM по
  специальному UART guard-token и прошиваются Firmverse без нажатия кнопки.
- Production flasher сохраняет ручной vendor-compatible `UXTDWU@9600` путь и
  не включает dev-only UART handoff в релизный HEX.

### Совместимость и обновление

- Wire protocol остаётся `v2`; изменения протокола на линии не требуются.
- Production HEX не содержит SNV/factory sectors и не должен стирать данные
  работающего прибора при обычном обновлении.
- Устаревшие pre-series storage layouts, экспериментальные identity sidecars и
  диагностические RC/bisect branches не являются частью production 1.5.0.

[1.5.0]: https://github.com/Pom4H/bolid/releases/tag/v1.5.0
