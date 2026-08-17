# Chip emulator boundary

The PHY6252 hex runner lives in **[phy6252-emu](https://github.com/Pom4H/phy6252-emu)** (`third_party/phy6252-emu`). It is a **guest**, not a Test-DPLS product module. Bolid must not absorb zmu, MMIO, or kit-demo firmware as architecture.

## Two different zmu uses

| Path | What it is | Where it lives |
|---|---|---|
| `firmware/zmu/` | Portable C99 + `sim/` board compiled for Cortex-M0 | Product E2E. Stays in this repo. |
| `third_party/phy6252-emu/` | Intel HEX on zmu Cortex-M0 + PHY bus + ATT mailbox | Chip emulator. Source: [Pom4H/phy6252-emu](https://github.com/Pom4H/phy6252-emu). |

`firmware/phy6252_emu/` is a third thing: host ATT/OSAL/SNV model in C. It is not the hex runner.

Bolid launches the **host** emulator one way: `bash tools/dpls_lab.sh` (`dpls_simulator` + wasm phone). To run the guest hex runner, use that repo:

```sh
cd third_party/phy6252-emu
cargo run --release -- --raw
```

## What Bolid must not grow

- A second copy of the hex runner under `tools/phy6252-zmu` or `firmware/`
- A second Bolid launcher for the same host simulator (`dpls_board.sh`, `run_phy6252_zmu.sh`)
- A second protocol crate or `tools/dpls-lab/src/protocol.ts` for hex GATT
- HLE / MMIO / TinyCrypt code under `firmware/` or `mobile/`
