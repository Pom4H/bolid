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

`@v1` — compatibility line Firmverse. Action сам подготавливает emulator backend и запускает firmware в deterministic single-node режиме.

## Что остаётся в Bolid

| Путь | Назначение |
|---|---|
| `firmware/sim/` | быстрый продуктовый simulator для lab, replay и Soft-BLE |
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

Production firmware снова имеет **один application HEX**. Отдельного factory flash image больше нет, поэтому тот же artifact используется для GCC/Keil/Firmverse и для реальной платы через `wh`.

Firmverse проверяет target image/CPU/MMIO execution contract, но не заменяет аппаратный acceptance на PB-03F. В частности, только реальная плата подтверждает vendor BLE stack, RF/advertising, SNV timing, pairing и поведение после power-cycle.
