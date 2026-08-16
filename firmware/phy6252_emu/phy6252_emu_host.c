#include "phy6252_emu.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    phy6252_emu_t radio;
    uint8_t last_rx[PHY6252_EMU_RX_SLOT];
    uint16_t last_rx_len;
    unsigned snv_flushes;
} host_t;

static int parse_hex(const char *text, uint8_t *out, size_t cap)
{
    size_t n = 0;

    while (*text != '\0') {
        unsigned value = 0;
        int got;

        while (*text == ' ' || *text == '\t') {
            ++text;
        }
        if (*text == '\0' || *text == '\n') {
            break;
        }
        if (sscanf(text, "%2x%n", &value, &got) != 1 || got != 2) {
            return -1;
        }
        if (n >= cap) {
            return -1;
        }
        out[n++] = (uint8_t)value;
        text += got;
    }
    return (int)n;
}

static void print_hex(const uint8_t *data, uint16_t length)
{
    uint16_t i;

    for (i = 0; i < length; ++i) {
        printf("%02X", data[i]);
    }
}

static void on_att(void *context, const uint8_t *data, uint16_t length, bool notify)
{
    (void)context;
    printf("ATT n=%u notify=%u hex=", (unsigned)length, notify ? 1u : 0u);
    print_hex(data, length);
    printf("\n");
}

static void on_write(void *context, const uint8_t *data, uint16_t length)
{
    host_t *host = context;

    memcpy(host->last_rx, data, length);
    host->last_rx_len = length;
    printf("RX n=%u hex=", (unsigned)length);
    print_hex(data, length);
    printf("\n");
    /* Echo service: enqueue the same payload as a notification. */
    if (!phy6252_emu_enqueue_tx(&host->radio, data, length)) {
        printf("TX_DROP\n");
    }
}

static void on_overflow(void *context)
{
    (void)context;
    printf("INDICATE_OVERFLOW\n");
}

static void on_snv(void *context, uint16_t id, const uint8_t *page, uint16_t length)
{
    host_t *host = context;

    ++host->snv_flushes;
    printf("SNV_FLUSH id=%u n=%u hex=", (unsigned)id, (unsigned)length);
    print_hex(page, length);
    printf("\n");
}

int main(void)
{
    host_t host;
    phy6252_emu_hooks_t hooks;
    char line[512];

    memset(&host, 0, sizeof(host));
    memset(&hooks, 0, sizeof(hooks));
    hooks.on_att_pdu = on_att;
    hooks.on_gatt_write = on_write;
    hooks.on_indicate_overflow = on_overflow;
    hooks.on_snv_flush = on_snv;
    hooks.context = &host;
    phy6252_emu_init(&host.radio, &hooks);

    while (fgets(line, sizeof(line), stdin) != NULL) {
        char *nl = strchr(line, '\n');
        char cmd[32];
        unsigned arg = 0;

        if (nl != NULL) {
            *nl = '\0';
        }
        if (line[0] == '\0' || line[0] == '#') {
            continue;
        }
        if (sscanf(line, "%31s", cmd) != 1) {
            continue;
        }
        if (strcmp(cmd, "CONNECT") == 0) {
            phy6252_emu_connect(&host.radio);
            printf("OK connected=1\n");
        } else if (strcmp(cmd, "DISCONNECT") == 0) {
            phy6252_emu_disconnect(&host.radio);
            printf("OK connected=0\n");
        } else if (sscanf(line, "CCCD %u", &arg) == 1) {
            phy6252_emu_set_cccd(&host.radio, (uint16_t)arg);
            printf("OK cccd=%u notify=%u\n",
                   arg, phy6252_emu_cccd_notify(&host.radio) ? 1u : 0u);
        } else if (strncmp(line, "WRITE ", 6) == 0) {
            uint8_t buf[PHY6252_EMU_RX_SLOT];
            int n = parse_hex(line + 6, buf, sizeof(buf));

            if (n <= 0) {
                printf("ERR write\n");
            } else if (!phy6252_emu_gatt_write(&host.radio, buf, (uint16_t)n)) {
                printf("ERR rx_full\n");
            } else {
                phy6252_emu_run_after_write(&host.radio);
            }
        } else if (sscanf(line, "TICK %u", &arg) == 1) {
            phy6252_emu_tick(&host.radio, host.radio.now_ms + arg);
            phy6252_emu_process_tx(&host.radio);
            printf("OK now=%u inflight=%u q=%u\n",
                   (unsigned)host.radio.now_ms,
                   host.radio.tx.in_flight ? 1u : 0u,
                   (unsigned)host.radio.tx.count);
        } else if (strcmp(cmd, "CONFIRM") == 0) {
            phy6252_emu_att_cfm(&host.radio);
            phy6252_emu_process_tx(&host.radio);
            printf("OK inflight=%u q=%u\n",
                   host.radio.tx.in_flight ? 1u : 0u,
                   (unsigned)host.radio.tx.count);
        } else if (strncmp(line, "SNV ", 4) == 0) {
            unsigned id = 0;
            char hex[256];
            uint8_t page[PHY6252_EMU_SNV_PAGE];
            int n;

            if (sscanf(line, "SNV %u %255s", &id, hex) != 2) {
                printf("ERR snv\n");
                continue;
            }
            n = parse_hex(hex, page, sizeof(page));
            if (n <= 0) {
                printf("ERR snv_hex\n");
                continue;
            }
            phy6252_emu_snv_mark(&host.radio, (uint16_t)id, page, (uint16_t)n);
            printf("OK snv_dirty=1 id=%u\n", id);
        } else if (strcmp(cmd, "STATE") == 0) {
            printf("STATE connected=%u cccd=%u notify=%u rx=%u tx=%u inflight=%u "
                   "sent=%u snv_dirty=%u snv_flushes=%u now=%u\n",
                   host.radio.connected ? 1u : 0u,
                   (unsigned)host.radio.cccd,
                   phy6252_emu_cccd_notify(&host.radio) ? 1u : 0u,
                   (unsigned)host.radio.rx.count,
                   (unsigned)host.radio.tx.count,
                   host.radio.tx.in_flight ? 1u : 0u,
                   host.radio.att_sent,
                   host.radio.snv_dirty ? 1u : 0u,
                   host.snv_flushes,
                   (unsigned)host.radio.now_ms);
        } else if (strcmp(cmd, "QUIT") == 0) {
            break;
        } else {
            printf("ERR unknown\n");
        }
        fflush(stdout);
    }
    return 0;
}
