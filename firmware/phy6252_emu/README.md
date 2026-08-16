# PHY6252 emulator

Reusable C99 host model of the PHY6252 / PB-03F radio and OSAL turns.
It does **not** implement a product protocol (no DPLS, no HMAC). Drop the
directory into another PHY6252 project and attach your GATT handler.

## What it models

Measured on PHY62XX SDK 3.1.2 with a Samsung SM-A135F:

| Chip fact | API |
| --- | --- |
| GATT write only queues RX | `phy6252_emu_gatt_write` |
| OSAL after `RX_EVT`: RX then one TX | `phy6252_emu_run_after_write` |
| One ATT PDU in flight | `tx.in_flight` |
| Samsung CCCD `0x03` → notify, no CFM | `phy6252_emu_set_cccd(0x03)` |
| Notify pace 80 ms | `PHY6252_EMU_NOTIFY_PACE_MS` |
| Indicate confirm / 2 s timeout | `phy6252_emu_att_cfm` / tick |
| Notify queue full: drop new | `enqueue_tx` returns false |
| Indicate queue full: drop link | `on_indicate_overflow` |
| SNV flush only while TX idle | `phy6252_emu_snv_mark` |
| LED must not pump TX | not in this library |

Default ARMCM0 app stack is 1024 bytes. Keep HMAC/SHA in BSS, not on the
OSAL stack, or the TX queue is corrupted and ATT returns `INVALID_VALUE_SIZE`.

## Minimal product glue

```c
static void on_write(void *ctx, const uint8_t *data, uint16_t length)
{
    my_server_receive(ctx, data, length); /* may enqueue_tx */
}

static void on_att(void *ctx, const uint8_t *data, uint16_t length, bool notify)
{
    (void)ctx;
    (void)notify;
    host_send_to_central(data, length);
}

phy6252_emu_t radio;
phy6252_emu_hooks_t hooks = {
    .on_att_pdu = on_att,
    .on_gatt_write = on_write,
    .context = &my_server,
};
phy6252_emu_init(&radio, &hooks);
phy6252_emu_connect(&radio);
phy6252_emu_set_cccd(&radio, PHY6252_EMU_CCCD_SAMSUNG);

phy6252_emu_gatt_write(&radio, frame, n);
phy6252_emu_run_after_write(&radio);   /* not from LED */
phy6252_emu_tick(&radio, now_ms);      /* notify pace / indicate timeout */
phy6252_emu_process_tx(&radio);        /* TX OSAL turn after the timer */
```

## Host CLI

`phy6252_emu_host` speaks line commands and echoes ATT PDUs. Commands:
`CONNECT`, `DISCONNECT`, `CCCD <n>`, `WRITE <hex>`, `TICK <ms>`, `CONFIRM`,
`SNV <id> <hex>`, `STATE`, `QUIT`.

DPLS uses this library from `Firmware/sim/dpls_sim_board.c`.
