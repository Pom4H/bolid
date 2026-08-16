# Differential session replay

`tools/session_capture/differential_replay.py` turns a captured phone ↔ real PHY6252 BLE session into a semantic regression against the host `dpls_simulator`.

The old replay helper can feed captured phone writes into the simulator, but authenticated DPLS v2 requests are bound to the original session: challenge nonce, `sessionId`, HMAC proof and session token are different on a fresh device instance. Replaying those bytes literally therefore cannot validate behavior after authentication.

Differential replay transplants the request stream onto a fresh simulator session:

1. keep the captured HELLO/client nonce;
2. accept the simulator's fresh `AUTH_CHALLENGE`;
3. derive the verifier from the supplied password and simulator salt;
4. recompute `AUTH_PROOF` for the simulator nonce + `sessionId`;
5. capture the simulator session token after successful authentication;
6. rewrite the 12-byte authenticated prefix (`sessionId` + token) of later requests;
7. compare captured and simulator responses by stable protocol semantics instead of random/session-local bytes.

The comparator currently checks, depending on message type:

- response/error/event flags and message type;
- authentication status and retry delay;
- command status and resulting mode;
- state mode, power source, low-reserve and real-short flags;
- device protocol/hardware/capability/settings shape;
- journal counts/page positions;
- stable status fields for settings and errors.

It intentionally ignores values that are expected to differ between two runs, such as authentication nonces, session tokens, uptime and raw voltage samples.

## Real capture → regression

```sh
python3 tools/session_capture/record_session.py --name field
python3 tools/session_capture/parse_session.py tmp/sessions/session-*-field.log

cmake -S firmware -B firmware/build
cmake --build firmware/build --target dpls_simulator

python3 tools/session_capture/differential_replay.py \
  tmp/sessions/session-*-field.frames.txt \
  --simulator firmware/build/dpls_simulator \
  --password 'TestDpls01' \
  --environment lab \
  --verbose
```

Exit code `0` means all captured responses matched the simulator's stable semantics. Exit code `1` means at least one response diverged and every divergence is printed as `MISMATCH ...` with the request sequence and message type.

`--environment lab` reproduces the USB-powered PB-03F lab profile used by the existing capture tooling. `--environment line` starts with healthy 12 V line inputs, which is useful for dangerous-mode regression scenarios. `--environment none` leaves the simulator defaults untouched.

## Self-test

`test_differential_replay.py` uses the real C simulator twice. It first creates a reference session, then deliberately corrupts the captured authentication proof and authenticated session prefix before replaying it into a fresh simulator. A clean replay therefore proves that session transplantation is actually happening.

The second pass changes the final captured `STATE_REPORT` from `NORMAL` to `SHORT_1` while keeping a valid frame CRC. Differential replay must reject that trace, proving that the test is semantic rather than a parser smoke test.

```sh
DPLS_SIMULATOR=firmware/build/dpls_simulator \
  python3 tools/session_capture/test_differential_replay.py
```

## Mutation probe

`mutation_probe.py` measures the detector instead of trusting the self-test. It freezes one reference trace from a known-good simulator, then builds temporary mutant simulators from copied firmware sources and replays the unchanged trace against each mutant.

```sh
python3 tools/session_capture/mutation_probe.py firmware/build/dpls_simulator
```

The initial mutation set deliberately includes two BLE-observable defects and one hidden hardware defect:

- `STATE_REPORT` always claims `NORMAL` even while `SHORT_1` is active — differential replay must kill this mutant;
- PHY notify pacing changes from the measured 80 ms to 160 ms — differential replay must kill this mutant;
- `SHORT_1` drives simulated `KZ2` instead of `KZ1` while BLE still reports `SHORT_1` — this mutant is expected to survive a BLE-only trace.

The surviving GPIO mutation is intentional documentation of the method boundary: a phone ↔ BLE capture can validate only behavior visible through that capture. Physical GPIO/readback equivalence needs an additional hardware-side oracle such as UART breadcrumbs, electrical readback, or another captured channel.
