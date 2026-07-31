#!/usr/bin/env python3
"""Enable ADC sampling in the PHY6252 adapter for a target build.

The committed adapter keeps DPLS_ADC_SAMPLING at 0 so a bare kit build without
this step stays safe (floating P20/P23 read as low reserve). Product builds with
DPLS_ADC=1 flip the define on a working-tree copy; the build script restores the
file afterwards.

SDK 3.1.2 API usage (hal_adc_start(INTERRUPT_MODE)) and sequential single-channel
kicks live in the committed source — this script only toggles the enable flag.
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

    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
