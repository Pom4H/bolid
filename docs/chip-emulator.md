# Chip emulator boundary

The PHY6252 hex runner lives in **[phy6252-emu](https://github.com/Pom4H/phy6252-emu)** (`third_party/phy6252-emu`). It is a **guest**, not a Test-DPLS product module. Bolid must not absorb zmu, MMIO, or kit-demo firmware as architecture.

## Two different zmu uses

| Path | What it is | Where it lives |
|---|---|---|
| `firmware/zmu/` | Portable C99 + `sim/` board compiled for Cortex-M0 | Product E2E. Stays in this repo. |
| `third_party/phy6252-emu/` | Intel HEX on zmu Cortex-M0 + PHY bus + ATT mailbox | Chip emulator. Source: [Pom4H/phy6252-emu](https://github.com/Pom4H/phy6252-emu). |

`firmware/phy6252_emu/` is a third thing: host ATT/OSAL/SNV model in C. It is not the hex runner.

## What Bolid may know

- Path to the `phy6252` **binary** (`DPLS_ZMU`)
- Path to a **hex** (`DPLS_HEX`; default is `third_party/phy6252-emu/firmware/kit-demo.hex`)
- The [line protocol](../third_party/phy6252-emu/PROTOCOL.md): `WRITE`, `CONNECT`, `GPIO`, `FRAME`, `READY`, … (`phy6252 --raw`)

Kit eyes and wires stay here: `tools/dpls_board.sh` → `tools/dpls-lab/cli.ts`, `tools/dpls_bench.sh`. They consume the chip; they do not own it.

The kit image speaks an ATT **mailbox**, not Test-DPLS GATT. Product lab stays `bash tools/dpls_lab.sh` (host sim + wasm phone).

## What Bolid must not grow

- A second copy of the emulator under `tools/phy6252-zmu` or `firmware/`
- A second protocol crate or `tools/dpls-lab/src/protocol.ts` for hex GATT
- HLE / MMIO / TinyCrypt code under `firmware/` or `mobile/`

```sh
bash tools/dpls_board.sh --air --listen
# or a prebuilt binary:
export DPLS_ZMU=/path/to/phy6252
```
