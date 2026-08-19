# Эмуляция PHY6252 в CI

Проверка **реального production Intel HEX** вынесена из Bolid в отдельный open-source проект [Firmverse](https://github.com/Pom4H/firmverse).

Bolid не должен хранить собственный ZMU/guest HEX runner или vendored-копию эмулятора. В GitHub Actions используется публичный Action:

```yaml
- uses: Pom4H/firmverse@v1
  with:
    firmware: tmp/test-dpls-firmverse.hex
    board: pb03f-kit
    strict: 'true'
```

`@v1` — поддерживаемая compatibility line самого Firmverse. Action сам устанавливает Rust, подготавливает pinned zmu backend, собирает Firmverse и запускает firmware в deterministic single-node режиме.

## Что остаётся в Bolid

| Путь | Назначение |
|---|---|
| `firmware/phy6252_emu/` | лёгкая host-модель ATT/OSAL/SNV для продуктового simulator; **не запускает production HEX** |
| `firmware/sim/` | Test-DPLS simulator для lab, replay и Soft-BLE сценариев |
| `tools/dpls-lab/` | host lab с тем же Compose UI |
| `.github/workflows/ci.yml` | сборка production PHY6252 HEX и передача его в Firmverse |

Host simulator нужен для быстрых protocol/UI сценариев. Он не является доказательством того, что target image работает на PHY6252, и не заменяет Firmverse.

## Что удалено из Bolid

- `third_party/phy6252-emu` и `.gitmodules`;
- `firmware/zmu/`;
- `tools/fetch_zmu.sh`;
- `tools/zmu_e2e.sh`;
- `tools/zmu_firmware_tests.sh`;
- `tools/zmu_run_all.sh`;
- ZMU-specific mobile interop test.

Таким образом, в репозитории продукта больше нет собственного real-HEX emulator stack.

## Ограничение текущей проверки

Production firmware требует factory identity record в `0x1103F000..0x1103FFFF`. Текущий Firmverse Action принимает application HEX, но Bolid пока не передаёт ему отдельный factory record. Поэтому CI подтверждает загрузку/исполнение target HEX и fail-closed MMIO/vendor-ROM contract, но не должен называться полноценной проверкой production identity/BLE commissioning.

Когда Firmverse получит поддерживаемый способ предварительно заполнить factory flash, в CI можно добавить отдельный сценарий provisioning/advertising без возврата локального эмулятора в Bolid.
