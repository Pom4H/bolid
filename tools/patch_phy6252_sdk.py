#!/usr/bin/env python3
"""Apply the minimal product patches to the pinned PHY62XX SDK 3.1.2.

The upstream 3.1.2 `hal_adc_start(INTERRUPT_MODE)` routes ordinary conversions
through `hal_ADC_compare_IRQHandler`, a compare/debug handler that pulses P1 and
uses `hal_adc_stop()` instead of the normal one-shot cleanup path. The SDK's
`hal_ADC_IRQHandler` already implements the ordinary data interrupt correctly
and finishes with `hal_poilling_adc_stop()`, which resets the ADCC clock and
clears pending IRQ state before releasing the driver's power lock.

The GAP bond manager also treats its documented peer ``addrType`` argument as
the local address type and forcibly distributes the peripheral IRK whenever an
Android central uses a private address. Test-DPLS has a stable public address;
that unsolicited legacy identity exchange never completes on current Samsung
Android and leaves an otherwise encrypted bond pending until timeout.

We keep the vendor tree out of git and patch only the exact pinned checkout at
build time. Every replacement is fail-closed: an upstream source drift makes
the build fail instead of silently applying a fuzzy patch.
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADC = ROOT / "Firmware/sdk/PHY62XX_SDK_3.1.2/components/driver/adc/adc.c"
BOND_MGR = ROOT / "Firmware/sdk/PHY62XX_SDK_3.1.2/components/profiles/Roles/gapbondmgr.c"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"PHY6252 SDK patch: expected one {label}, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    text = ADC.read_text(encoding="utf-8-sig")

    text = replace_once(
        text,
        """    if( adc_mode == POLLING_MODE )\n        JUMP_FUNCTION(ADCC_IRQ_HANDLER)                  =   (uint32_t)&hal_ADC_IRQHandler;\n    else\n        JUMP_FUNCTION(ADCC_IRQ_HANDLER)                  =   (uint32_t)&hal_ADC_compare_IRQHandler;\n""",
        """    if( adc_mode == POLLING_MODE || adc_mode == INTERRUPT_MODE )\n        JUMP_FUNCTION(ADCC_IRQ_HANDLER)                  =   (uint32_t)&hal_ADC_IRQHandler;\n    else\n        JUMP_FUNCTION(ADCC_IRQ_HANDLER)                  =   (uint32_t)&hal_ADC_compare_IRQHandler;\n""",
        "INTERRUPT_MODE handler selection",
    )

    # The compare ISR contains vendor scope/debug pulses. They are unrelated to
    # the ADC API and must never toggle physical product pins.
    text = replace_once(
        text,
        """    gpio_write(P1, 1);\n    gpio_write(P1, 0);\n""",
        """    /* product patch: removed vendor P1 scope pulse */\n""",
        "P1 debug pulse",
    )
    text = replace_once(
        text,
        """        gpio_write(P0, 1);\n        gpio_write(P0, 0);\n\n""",
        """        /* product patch: removed vendor P0 scope pulse */\n\n""",
        "P0 debug pulse",
    )

    ADC.write_text(text, encoding="utf-8")

    bond_mgr = BOND_MGR.read_text(encoding="utf-8-sig")
    bond_mgr = replace_once(
        bond_mgr,
        """    if ( gapBond_Bonding && addrType != ADDRTYPE_PUBLIC )
    {
        // Force a slave ID key
        params.secReqs.keyDist.sIdKey = TRUE;
    }
""",
        """    /* Product patch: addrType is the peer address type, not our local
     * identity type. Respect GAPBOND_KEY_DIST_LIST instead of forcing an
     * unused peripheral IRK when an Android central connects with an RPA. */
""",
        "peer-address slave ID key override",
    )
    BOND_MGR.write_text(bond_mgr, encoding="utf-8")

    print("Patched PHY62XX SDK 3.1.2 ADC and BLE bond-manager paths")


if __name__ == "__main__":
    main()
