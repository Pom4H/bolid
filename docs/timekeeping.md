# Timekeeping and event timestamps

Test-DPLS deliberately keeps two time concepts separate.

## Monotonic time

PHY6252 integration obtains milliseconds from `osal_GetSystemClock()`. This monotonic value is the only clock used for safety and protocol deadlines: dangerous-mode timeout, session timeout, identify timeout, factory-reset hold timing, BLE TX deadlines and similar logic.

Changing or synchronizing calendar time cannot extend or shorten any of those deadlines.

The PHY62XX SDK derives its timing from the chip's low-frequency clock domain. The target currently uses the SDK's internal 32 kHz RC clock configuration. No periodic flash write is involved: current monotonic time lives in volatile runtime state.

## Calendar UTC for the journal

After successful DPLS authentication, the mobile client sends `TIME_SYNC (0x0b)`:

- `session_id`: 4 bytes, little-endian
- `session_token`: 8 bytes
- Unix UTC seconds: 4 bytes, little-endian

The firmware accepts the message only for the authenticated session and rejects clearly invalid dates outside 2020-01-01 through 2099-12-31.

The synchronized UTC anchor is RAM-only. Firmware advances it from the same monotonic millisecond source on each server update. This means time continues through normal PHY6252 sleep without writing flash every second.

When a journal event occurs:

- if UTC has been synchronized since boot, `timestamp_seconds` is Unix UTC seconds;
- otherwise `timestamp_seconds` remains uptime seconds, preserving useful ordering before the first phone connection and compatibility with existing journal records.

Only the event record itself is written to SNV/flash. `TIME_SYNC` does not write the clock to flash.

## Power loss

A complete power loss invalidates the calendar anchor. On the next boot the device immediately has monotonic uptime again, but it cannot know how long it was unpowered. Calendar time becomes valid again after the next authenticated phone connection.

Consequently, an event generated after a cold boot but before the first synchronization has uptime rather than a calendar timestamp. Guaranteeing calendar time during a fully unpowered interval would require a separately powered RTC/time source.

## Mobile presentation

The shared Android/iOS application recognizes timestamps in the supported Unix range as UTC and formats them as `YYYY-MM-DD HH:MM:SS UTC`. Older/pre-sync records remain displayed as `+HH:MM:SS` uptime. CSV export includes a `time_basis` column (`utc` or `uptime`) so mixed journals remain unambiguous.
