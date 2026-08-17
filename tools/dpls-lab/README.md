# Test-DPLS wasm phone

One command starts the host emulator and the Compose client:

```sh
bash tools/dpls_lab.sh
# http://127.0.0.1:8787
```

Same `DplsApp` as Android/iOS. Transport is WebSocket to `dpls_simulator` (`LabBleTransport`). Factory password: `TestDpls01`.

`dpls-ble` is only the laptop radio adapter (optional: real phone sees the sim in the air). Chip hex emulator is the guest repo, not this command: [docs/chip-emulator.md](../../docs/chip-emulator.md).

Device-free protocol E2E (not a second launcher): `bash tools/soft_ble_e2e.sh`.
