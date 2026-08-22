# Эмуляция PHY6252 в CI

Проверка **реального production Intel HEX** вынесена из Bolid в отдельный open-source проект [Firmverse](https://github.com/Pom4H/firmverse).

Bolid не хранит собственный PHY6252/ZMU emulator.

## Один production image

CI сначала собирает единственный PHY6252 artifact Arm Compiler 6.24.0, затем Firmverse **скачивает именно этот artifact** и исполняет его в strict режиме.

```text
CMSIS project
  ↓ Arm Compiler 6.24.0
TestDPLS-1.5.0.hex
  ├─ release artifact
  ├─ Firmverse strict boot
  ├─ flash harness
  └─ PB-03F hardware
```

Firmverse не имеет собственного build path для Bolid firmware.

## Что остаётся в Bolid

| Путь | Назначение |
|---|---|
| `firmware/sim/` | быстрый продуктовый simulator для lab, replay и Soft-BLE |
| `tools/dpls-lab/` | host lab с тем же Compose UI |
| `.github/workflows/ci.yml` | production build + передача готового HEX в Firmverse |

`firmware/sim` не исполняет target HEX и не моделирует Cortex-M0/MMIO/vendor ROM. Это быстрый mock продуктового протокола, а не эмулятор чипа.

## Что удалено из Bolid

- in-tree PHY6252 emulator stacks;
- ZMU-specific firmware harness;
- отдельные guest-image build paths;
- дублирующие target toolchains.

## Граница проверки

Firmverse проверяет target image/CPU/MMIO execution contract и обязан увидеть включение BLE advertising. Он не заменяет аппаратный acceptance на PB-03F: только реальная плата подтверждает RF, pairing, flash timing, GPIO, ADC, power-cycle и энергопотребление.
