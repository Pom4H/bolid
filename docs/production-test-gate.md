# Production test gate Test-DPLS

Этот документ задаёт обязательные evidence sources для release-candidate.

## Главные инварианты

1. **Fail-safe:** физический режим, отличный от `NORMAL`, допустим только при активной аутентифицированной сессии, свежих safety-измерениях, нормальном резерве и отсутствии реального КЗ.
2. **Disconnect => NORMAL.**
3. **Unknown != safe:** неизвестные/просроченные измерения не разрешают dangerous mode.
4. **Один владелец состояния:** product session — `DeviceSession`, wire transaction — `Frame.sequence`, BLE link — `connection_handle`, commanded mode — `dpls_server.safety.mode`.
5. **Audit is safety:** физическое изменение без обязательной записи переводит прибор в fail-safe.
6. **Power loss old-or-new:** durable settings выбирают предыдущую или полностью записанную новую generation.
7. **Generic SMP failure не стирает bonds.**
8. **Flash и live BLE разделены:** app-owned persistent write выполняется только offline.
9. **Один production firmware artifact:** target собирается один раз Arm Compiler 6.24.0; этот же HEX используется Firmverse и hardware flow.
10. **Auth lock durable or fail-closed.**

## Локальный gate

```sh
bash tools/run_host_invariant_gate.sh
```

Он проверяет repository/CI contracts, protocol CRC, architecture ownership, ASan/UBSan, CTest, deterministic fuzz/state sequences, durable-settings fault matrix, ATT simulator и differential wire replay.

## Release PR

| Gate | Что доказывает |
| --- | --- |
| Cheap PR smoke | Host invariants, sanitizers, fuzz, state/fault models, differential replay |
| Android unit tests | common runtime/core и Android transport policy |
| PHY6252 firmware / Arm Compiler 6.24 | единственный production target реально собирается |
| PHY6252 Firmverse | тот же production HEX запускается в strict PB-03F model и доходит до advertising |
| Firmware coverage + cppcheck | host coverage и статический анализ C |
| Soft-BLE | `DplsClient` против firmware core simulator |
| iOS adapter + Xcode host | Kotlin/Native + Xcode integration smoke |
| RC production gate | все затронутые evidence sources имеют `success` |

`RC production gate` должен быть required check в branch protection релизной ветки.

## Single-artifact rule

Production job создаёт `TestDPLS-1.4.2-rc9.hex` один раз. Firmverse скачивает этот artifact через Actions и не запускает вторую target-сборку. Flash harness и аппаратная приёмка используют тот же формат image.

`tools/check_repo_layout.sh` и `tools/test_ci_contract.py` запрещают возврат второго PHY6252 production toolchain/source graph.

## Power-loss testing

Host tests моделируют reset во время erase/program, torn generation, corruption, отсутствие обеих валидных копий и wrap generation counter. Новая generation authoritative только после полной валидной записи.

## State/event testing

`test_server_state_sequences` переставляет mode request, reserve, real-short, measurement validity, disconnect/reconnect/re-auth, tick и security events. После каждого transition проверяется глобальный safety invariant.

## Что CI принципиально не доказывает

Без аппаратного стенда CI не доказывает:

- RF/SMP особенности конкретного телефона;
- реальную длительность flash stall относительно radio events;
- электрические уровни/фронты GPIO и сопротивление КЗ;
- поведение ионистора;
- reset cause на реальном silicon под длительной нагрузкой;
- абсолютный ток изделия.

## Hardware acceptance

После зелёного RC gate обязательны:

1. clean pairing Android;
2. reconnect с валидным Android bond;
3. stale-bond recovery без retry-loop;
4. clean pairing iOS;
5. reconnect с валидным iOS bond;
6. не менее 100 connect/auth/disconnect циклов на каждой платформе;
7. длительная encrypted session без unexpected reset;
8. вход/выход dangerous modes и fail-safe при disconnect;
9. measurement lost в dangerous mode => `NORMAL`;
10. заполнение/wrap журнала;
11. power cut во время settings/password/reset;
12. линия → резерв → линия;
13. power/current protocol из `rc9-power-measurement.md`.

Аппаратный failure сначала превращается в минимальный regression test или captured-session fixture, затем исправляется production code.
