# Эмуляция PHY6252 в CI

Проверка **реального production Intel HEX** вынесена из Bolid в отдельный open-source проект [Firmverse](https://github.com/Pom4H/firmverse).

Bolid не хранит собственный PHY6252/ZMU emulator. В GitHub Actions используется публичный Action:

```yaml
- uses: Pom4H/firmverse@v1
  with:
    firmware: tmp/test-dpls-firmverse.hex
    board: pb03f-kit
    strict: 'true'
```

`@v1` — compatibility line Firmverse. Action сам подготавливает свой emulator backend и запускает firmware в deterministic single-node режиме.

## Что остаётся в Bolid

| Путь | Назначение |
|---|---|
| `firmware/sim/` | быстрый продуктовый simulator для lab, replay и Soft-BLE; содержит только private ATT queue/pacing mock |
| `tools/dpls-lab/` | host lab с тем же Compose UI |
| `.github/workflows/ci.yml` | сборка production PHY6252 HEX и передача его в Firmverse |

`firmware/sim` не исполняет target HEX и не моделирует Cortex-M0/MMIO/vendor ROM. Это быстрый mock продуктового протокола, а не эмулятор чипа.

## Что удалено из Bolid

- `firmware/phy6252_emu/`;
- `third_party/phy6252-emu` и `.gitmodules`;
- `firmware/zmu/`;
- `tools/fetch_zmu.sh`;
- `tools/zmu_e2e.sh`;
- `tools/zmu_firmware_tests.sh`;
- `tools/zmu_run_all.sh`;
- ZMU-specific mobile interop test.

Таким образом, в репозитории продукта нет второй реализации PHY6252 emulator stack.

## Граница проверки

Production firmware требует factory identity record в `0x1103F000..0x1103FFFF`. Текущий Firmverse Action получает application HEX, но Bolid пока не передаёт ему отдельный factory record. Поэтому Firmverse проверяет загрузку и исполнение target image/CPU/MMIO contract, но CI не должен выдавать это за полноценную проверку production provisioning и BLE commissioning.

Когда Firmverse получит поддерживаемый способ предварительно заполнить factory flash, этот сценарий надо добавить туда, а не возвращать локальный эмулятор в Bolid.
