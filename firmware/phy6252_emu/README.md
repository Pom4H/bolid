# Host-модель PHY6252

`firmware/phy6252_emu` — переиспользуемая C99-модель особенностей radio/OSAL/ATT/SNV PHY6252/PB-03F. Она **не реализует протокол Test-DPLS**, HMAC или продуктовую логику. Модель можно подключить к другому проекту PHY6252 и передать ей собственный GATT handler.

## Что моделируется

Поведение зафиксировано для используемого в проекте PHY62XX SDK 3.1.2 и проверялось в том числе с Samsung SM-A135F.

| Наблюдаемое поведение | API/состояние модели |
|---|---|
| GATT write только ставит RX в очередь | `phy6252_emu_gatt_write` |
| OSAL turn после `RX_EVT`: RX, затем один TX | `phy6252_emu_run_after_write` |
| одновременно один ATT PDU in flight | `tx.in_flight` |
| Samsung CCCD `0x03` → notify без CFM | `phy6252_emu_set_cccd(0x03)` |
| pacing notify 80 мс | `PHY6252_EMU_NOTIFY_PACE_MS` |
| indication confirm / timeout 2 с | `phy6252_emu_att_cfm` / tick |
| переполнение notify queue → новый пакет отбрасывается | `enqueue_tx == false` |
| переполнение indicate queue → link считается потерянным | `on_indicate_overflow` |
| SNV flush только при свободном TX | `phy6252_emu_snv_mark` |
| LED не должен прокачивать TX queue | правило интеграции |

У стандартного ARMCM0 application stack размер 1024 байта. Большие HMAC/SHA buffers нужно держать в BSS, а не на OSAL stack, иначе возможна порча TX queue и `INVALID_VALUE_SIZE`.

## Минимальная интеграция

```c
static void on_write(void *ctx, const uint8_t *data, uint16_t length)
{
    my_server_receive(ctx, data, length);
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
phy6252_emu_run_after_write(&radio);
phy6252_emu_tick(&radio, now_ms);
phy6252_emu_process_tx(&radio);
```

## Host CLI

`phy6252_emu_host` принимает строковые команды:

```text
CONNECT
DISCONNECT
CCCD <n>
WRITE <hex>
TICK <ms>
CONFIRM
SNV <id> <hex>
STATE
QUIT
```

Test-DPLS использует библиотеку из `firmware/sim/dpls_sim_board.c`.

Эта модель не заменяет аппаратные испытания PHY6252: она фиксирует только явно проверенные особенности host-visible поведения стека.
