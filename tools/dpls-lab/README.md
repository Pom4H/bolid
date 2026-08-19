# Test-DPLS lab / wasm-клиент

Одной командой запускаются host simulator и тот же Compose client, который используется на Android/iOS:

```sh
bash tools/dpls_lab.sh
# http://127.0.0.1:8787
```

Transport — WebSocket к `dpls_simulator` через `LabBleTransport`. Для factory-сценария simulator использует пароль `TestDpls01`.

`dpls-ble` — только опциональный CoreBluetooth adapter ноутбука. Он не является вторым product client и не содержит отдельную бизнес-логику.

Guest HEX emulator PHY6252 находится в отдельном проекте `third_party/phy6252-emu`; его граница описана в [docs/chip-emulator.md](../../docs/chip-emulator.md).

Device-free product E2E без второго launcher:

```sh
bash tools/soft_ble_e2e.sh
```

Lab должен следовать текущему BLE-контракту продукта: discovery по Service UUID/имени, без Manufacturer Specific Data и без legacy Company ID.
