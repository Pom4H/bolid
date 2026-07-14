#!/usr/bin/env python3
"""Apply the two source-level API changes required by PHY62XX SDK 3.1.2.

The migration branch keeps the production adapter shared with main while the
new SDK is being validated. The build script applies this transformation to the
working-tree copy and restores the original file through a shell trap.

Every replacement is exact and count-checked: if the shared source changes,
the target build fails rather than silently producing a partially migrated
image.
"""
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    args = parser.parse_args()

    path = args.source
    text = path.read_text(encoding="utf-8")

    text = replace_once(
        text,
        "#define DPLS_ADC_SAMPLING 0",
        "#define DPLS_ADC_SAMPLING 1",
        "enable ADC sampling",
    )

    text = replace_once(
        text,
        "    hal_adc_start();\n",
        """    if (hal_adc_start(INTERRUPT_MODE) != PPlus_SUCCESS) {
        /* Configuration has already claimed the analog pins and ADCC power
         * lock. Release both if the 3.1.2 start operation is rejected. */
        (void)hal_adc_stop();
        adc_busy = false;
    }
""",
        "adapt hal_adc_start API",
    )

    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
