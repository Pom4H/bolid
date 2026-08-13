# Keil MDK build

This target keeps a dedicated Keil MDK / Arm Compiler 6 build for PHY6252.
It is independent from the GCC build path.

The build uses the existing CMSIS solution and scatter file:

- `test-dpls.csolution.yml`
- `test-dpls.cproject.yml`
- `scatter_load.sct`
- pinned PHY62XX SDK 3.1.2
- Keil MDK Community / Arm Compiler 6

## Build

From the repository root, after activating the toolchain described by
`vcpkg-configuration.json`:

```sh
DPLS_ADC=1 tools/build_firmware_keil.sh tmp/test-dpls-sdk-3.1.2.hex
DPLS_ADC=0 tools/build_firmware_keil.sh tmp/test-dpls-adcoff.hex
```

The script fetches the pinned SDK, runs `cbuild` with AC6, extracts the AXF
load regions with `fromelf`, and emits the flashable Intel HEX expected by the
PHY6252 flashing flow.

GitHub Actions runs both ADC-enabled and ADC-disabled variants and uploads the
HEX, AXF, MAP and build logs as one artifact.

This is a headless Keil MDK build; µVision is not required in CI.
