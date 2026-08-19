# Граница эмулятора PHY6252

HEX-runner PHY6252 живёт в отдельном проекте **[phy6252-emu](https://github.com/Pom4H/phy6252-emu)** и подключён сюда как `third_party/phy6252-emu`. Это внешний guest-компонент, а не модуль продукта Test-DPLS. Архитектура Bolid не должна поглощать его zmu/MMIO/HLE-код.

## Три разных слоя эмуляции

| Путь | Назначение |
|---|---|
| `firmware/zmu/` | переносимый C99 firmware core + `sim/`, собранный под Cortex-M0; продуктовый E2E этого репозитория |
| `firmware/phy6252_emu/` | host C-модель специфики ATT/OSAL/SNV PHY6252 |
| `third_party/phy6252-emu/` | отдельный guest emulator: Intel HEX на zmu + PHY bus/ATT mailbox |

Эти слои решают разные задачи и не должны сливаться.

## Запуск

Основной host lab Test-DPLS:

```sh
bash tools/dpls_lab.sh
```

Он запускает `dpls_simulator` и общий wasm-клиент.

Guest HEX runner запускается из своего проекта:

```sh
cd third_party/phy6252-emu
cargo run --release -- --raw
```

## Чего не должно появляться в Bolid

- второй HEX runner под `tools/` или `firmware/`;
- второй launcher для того же `dpls_simulator`;
- отдельная TypeScript-копия Test-DPLS wire protocol только ради emulator bridge;
- копии HLE/MMIO/TinyCrypt из `phy6252-emu` внутри product firmware/mobile;
- зависимость продукта от внутренних деталей guest emulator.

Если эмулятору нужна новая возможность, сначала меняется его собственный контракт, а Bolid использует этот контракт через узкую границу.
