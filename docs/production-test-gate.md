# Production test gate Test-DPLS

Этот документ задаёт обязательные доказательства для release-candidate. Цель CI — не показать, что код «обычно работает», а автоматически проверять конечные пространства состояний и отказов там, где это возможно.

## Главные инварианты

1. **Fail-safe:** физический режим, отличный от `NORMAL`, допустим только при активной аутентифицированной сессии, свежих safety-измерениях, нормальном резерве и отсутствии реального КЗ.
2. **Disconnect => NORMAL:** потеря BLE-сессии не может оставить опасный режим включённым.
3. **Unknown != safe:** неизвестные/просроченные измерения не разрешают вход в опасный режим и принудительно возвращают `NORMAL`.
4. **Один владелец состояния:** product session принадлежит `DeviceSession`, wire transaction — `Frame.sequence`, физический BLE link — `connection_handle`, commanded mode — `dpls_server.safety.mode`.
5. **Audit is safety:** физическое изменение, которое невозможно записать в обязательный журнал, не считается успешным и переводит прибор в fail-safe.
6. **Power loss old-or-new:** после reset в любой точке commit durable settings выбирается либо предыдущая валидная generation, либо полностью записанная новая. Полузапись не может стать текущей.
7. **BLE security is event-driven:** plaintext CCCD не является доказательством security; защищённый RX 5/15 означает pairing-required; timeout не превращается в success и не является доказательством stale bond.
8. **Generic SMP failure не стирает bonds.** Удаление ключей разрешено только явным пользовательским/физическим reset flow.
9. **Flash и live BLE разделены:** deferred journal не программирует SNV при активном connection handle; advertising не должен открывать новый link во время storage drain.
10. **Production binaries equivalent by contract:** GNU Arm GCC и Keil/AC6 обязаны собирать один source set и проходить release gate.
11. **Auth lock is durable or fail-closed:** 300-секундная блокировка после пяти неверных паролей не может существовать только в RAM. Ошибка записи/снятия lock переводит сервер в critical fail-safe, сбрасывает authentication и завершает link.

## Локальный обязательный gate

```bash
bash tools/run_host_invariant_gate.sh
```

Он запускает один и тот же набор production-core проверок, который используется в GitHub Actions:

- repository layout;
- CI topology contract;
- protocol CRC contract;
- session-capture parser;
- architecture ownership guard;
- host build с AddressSanitizer + UndefinedBehaviorSanitizer;
- весь CTest suite;
- deterministic protocol fuzz (300k+ входов);
- safety state-space tests;
- deterministic server state/event sequences (96k переходов);
- auth-lock persistence fail-safe tests;
- durable-settings power-cut/corruption matrix;
- ATT simulator tests;
- обязательный differential wire replay Python reference framing ↔ production C simulator.

Фиксированные seeds являются частью контракта: падение всегда воспроизводимо. Новые найденные crash/event traces должны добавляться отдельными regression cases, а не заменяться новым seed.

## Release PR

Любая ветка `release/*` принудительно включает полную матрицу независимо от path filtering:

| Gate | Что доказывает |
| --- | --- |
| Cheap PR smoke | Host invariants, ASan/UBSan, fuzz, state/fault models, differential wire replay |
| Android unit tests | common runtime/core и Android transport policy; lint + APK |
| PHY6252 Firmverse | Реальный production HEX запускается в строгой модели PB-03F |
| Firmware coverage + cppcheck | host coverage и статический анализ C |
| Soft-BLE | Реальный `DplsClient` против реального firmware core simulator |
| PHY6252 GNU Arm GCC | production target собирается pinned SDK 3.1.2 |
| PHY6252 Keil MDK / AC6 | второй production compiler собирает тот же target |
| iOS adapter + Xcode host | Kotlin/Native tests, framework link, Xcode integration smoke |
| RC production gate | Все перечисленные независимые evidence sources имеют `success` |

`RC production gate` должен быть required check в branch protection для релизной ветки. CI-код сам проверяет свою топологию через `tools/test_ci_contract.py`, но repository branch protection остаётся внешним trust boundary.

## Power-loss testing

Durable settings используют две generation-записи с CRC. Host tests моделируют:

- reset после каждого байта erase;
- reset после каждого байта program;
- смесь байтов старой и новой generation;
- torn factory-reset tombstone;
- одиночную corruption каждого байта;
- отсутствие обеих валидных копий;
- wrap generation counter.

Критерий один: новая generation становится authoritative только после полностью валидной записи. Во всех остальных одиночных отказах выбирается предыдущая валидная generation.

## State/event testing

`test_server_state_sequences` выполняет десятки тысяч детерминированных перестановок:

- mode request;
- reserve low / restore;
- real-short / restore;
- measurement lost / restore;
- disconnect / reconnect / re-auth;
- большие и малые tick;
- изменение security link state.

После каждого transition проверяется глобальный safety invariant. Это дополняет exhaustive boolean state-space в `test_safety`: один тест проверяет все снимки входов, второй — историю переходов.

`test_auth_lock_fail_safe` отдельно fault-injects отказ durable auth lock: запись блокировки, снятие истёкшей блокировки и очистку lock перед успешной авторизацией. Во всех трёх случаях ожидается critical fail-safe, а не продолжение с RAM-only security state.

## Differential wire replay

`tools/session_capture/test_differential_replay.py` является обязательной частью host gate. Он гонит каноническую wire-последовательность через production C simulator и декодирует его ответы независимым Python framing implementation. Проверяются type/sequence, стабильные поля challenge и единая реакция на повреждённый CRC. Отсутствие теста является ошибкой gate, а не `skip`.

Реальные телефон ↔ PHY6252 traces из `tmp/sessions` остаются дополнительным аппаратным evidence: подтверждённое расхождение обязательно превращается в committed regression case.

## Что CI принципиально не доказывает

Без аппаратного стенда CI не доказывает:

- RF/SMP особенности конкретного iPhone/Samsung;
- реальную длительность PHY6252 flash stall относительно controller radio events;
- электрические уровни/фронты GPIO и реальное сопротивление КЗ;
- поведение ионистора при переходе на резерв;
- reset cause на реальном silicon под длительной нагрузкой.

Поэтому `RC production gate` — необходимое, но не достаточное условие выпуска.

## Hardware acceptance для релиза

После зелёного RC gate обязательны:

1. clean pairing Android;
2. reconnect с валидным Android bond;
3. stale-bond recovery без retry-loop;
4. clean pairing iOS;
5. reconnect с валидным iOS bond;
6. не менее 100 последовательных connect/auth/disconnect циклов на каждой платформе;
7. длительная encrypted session без `[REST CAUSE] 1`;
8. вход/выход каждого опасного режима и fail-safe при disconnect;
9. потеря/просрочка ADC в опасном режиме => `NORMAL`;
10. заполнение и wrap журнала;
11. power cut во время settings/password/reset операций;
12. переход линия -> резерв -> линия.

Любой аппаратный failure сначала превращается в минимальный воспроизводимый regression test или captured-session fixture; только после этого исправляется production code.
