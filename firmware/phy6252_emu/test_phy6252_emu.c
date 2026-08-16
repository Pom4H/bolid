#include "phy6252_emu.h"

#include <assert.h>
#include <string.h>

typedef struct {
    uint8_t last_att[PHY6252_EMU_TX_SLOT];
    uint16_t last_att_len;
    unsigned att_count;
    unsigned rx_count;
    unsigned overflow_count;
    unsigned snv_count;
    uint16_t snv_id;
    bool last_notify;
} capture_t;

static void on_att(void *context, const uint8_t *data, uint16_t length, bool notify)
{
    capture_t *cap = context;

    memcpy(cap->last_att, data, length);
    cap->last_att_len = length;
    cap->last_notify = notify;
    ++cap->att_count;
}

static void on_write(void *context, const uint8_t *data, uint16_t length)
{
    capture_t *cap = context;

    (void)data;
    (void)length;
    ++cap->rx_count;
}

static void on_overflow(void *context)
{
    capture_t *cap = context;
    ++cap->overflow_count;
}

static void on_snv(void *context, uint16_t id, const uint8_t *page, uint16_t length)
{
    capture_t *cap = context;

    (void)page;
    (void)length;
    cap->snv_id = id;
    ++cap->snv_count;
}

typedef struct {
    capture_t cap;
    phy6252_emu_t radio;
} harness_t;

static void on_write_echo(void *context, const uint8_t *data, uint16_t length)
{
    harness_t *h = context;

    ++h->cap.rx_count;
    (void)phy6252_emu_enqueue_tx(&h->radio, data, length);
}

static void bind(harness_t *h, bool echo)
{
    phy6252_emu_hooks_t hooks;

    memset(h, 0, sizeof(*h));
    memset(&hooks, 0, sizeof(hooks));
    hooks.on_att_pdu = on_att;
    hooks.on_gatt_write = echo ? on_write_echo : on_write;
    hooks.on_indicate_overflow = on_overflow;
    hooks.on_snv_flush = on_snv;
    hooks.context = echo ? (void *)h : (void *)&h->cap;
    phy6252_emu_init(&h->radio, &hooks);
}

int main(void)
{
    harness_t h;
    uint8_t a[4] = {0x01u, 0x02u, 0x03u, 0x04u};
    uint8_t b[4] = {0x11u, 0x12u, 0x13u, 0x14u};
    uint8_t page[4] = {0xAAu, 0xBBu, 0xCCu, 0xDDu};
    unsigned i;

    /* GATT write queues; product and TX run only on OSAL turns. */
    bind(&h, true);
    phy6252_emu_connect(&h.radio);
    phy6252_emu_set_cccd(&h.radio, PHY6252_EMU_CCCD_SAMSUNG);
    assert(phy6252_emu_gatt_write(&h.radio, a, 4u));
    assert(h.cap.rx_count == 0u);
    assert(h.radio.rx.count == 1u);
    phy6252_emu_process_rx(&h.radio);
    assert(h.cap.rx_count == 1u);
    assert(h.radio.tx.count == 1u);
    assert(h.cap.att_count == 0u);
    phy6252_emu_process_tx(&h.radio);
    assert(h.cap.att_count == 1u);
    assert(h.cap.last_notify);
    assert(h.radio.tx.in_flight);
    assert(h.radio.tx.count == 1u);

    /* Second PDU stays queued: one in flight, no nested pump. */
    assert(phy6252_emu_enqueue_tx(&h.radio, b, 4u));
    phy6252_emu_process_tx(&h.radio);
    assert(h.cap.att_count == 1u);
    assert(h.radio.tx.count == 2u);

    /* Samsung never CFMs; 80 ms notify pace advances the queue. */
    phy6252_emu_tick(&h.radio, 79u);
    phy6252_emu_process_tx(&h.radio);
    assert(h.cap.att_count == 1u);
    phy6252_emu_tick(&h.radio, 80u);
    assert(!h.radio.tx.in_flight);
    phy6252_emu_process_tx(&h.radio);
    assert(h.cap.att_count == 2u);
    assert(h.cap.last_att[0] == 0x11u);
    assert(h.radio.tx.in_flight);

    /* Indicate-only: 80 ms does not complete; ATT CFM or 2 s timeout does. */
    bind(&h, false);
    phy6252_emu_connect(&h.radio);
    phy6252_emu_set_cccd(&h.radio, PHY6252_EMU_CCCD_INDICATE);
    assert(phy6252_emu_enqueue_tx(&h.radio, a, 4u));
    phy6252_emu_process_tx(&h.radio);
    assert(h.cap.att_count == 1u);
    assert(!h.cap.last_notify);
    phy6252_emu_tick(&h.radio, 80u);
    phy6252_emu_process_tx(&h.radio);
    assert(h.radio.tx.in_flight);
    assert(h.cap.att_count == 1u);
    phy6252_emu_att_cfm(&h.radio);
    assert(!h.radio.tx.in_flight);
    assert(phy6252_emu_enqueue_tx(&h.radio, b, 4u));
    phy6252_emu_process_tx(&h.radio);
    phy6252_emu_tick(&h.radio, h.radio.now_ms + PHY6252_EMU_INDICATE_TIMEOUT_MS);
    assert(!h.radio.tx.in_flight);

    /* Notify queue full: drop the new frame, keep the link. */
    bind(&h, false);
    phy6252_emu_connect(&h.radio);
    phy6252_emu_set_cccd(&h.radio, PHY6252_EMU_CCCD_SAMSUNG);
    for (i = 0; i < PHY6252_EMU_TX_DEPTH; ++i) {
        assert(phy6252_emu_enqueue_tx(&h.radio, a, 4u));
    }
    assert(!phy6252_emu_enqueue_tx(&h.radio, b, 4u));
    assert(h.radio.connected);
    assert(h.cap.overflow_count == 0u);
    assert(h.radio.tx.count == PHY6252_EMU_TX_DEPTH);

    /* Indicate queue full: empty TX and report overflow. */
    bind(&h, false);
    phy6252_emu_connect(&h.radio);
    phy6252_emu_set_cccd(&h.radio, PHY6252_EMU_CCCD_INDICATE);
    for (i = 0; i < PHY6252_EMU_TX_DEPTH; ++i) {
        assert(phy6252_emu_enqueue_tx(&h.radio, a, 4u));
    }
    assert(!phy6252_emu_enqueue_tx(&h.radio, b, 4u));
    assert(h.cap.overflow_count == 1u);
    assert(h.radio.tx.count == 0u);

    /* SNV flush waits until TX is idle. */
    bind(&h, false);
    phy6252_emu_connect(&h.radio);
    phy6252_emu_set_cccd(&h.radio, PHY6252_EMU_CCCD_SAMSUNG);
    assert(phy6252_emu_enqueue_tx(&h.radio, a, 4u));
    phy6252_emu_process_tx(&h.radio);
    phy6252_emu_snv_mark(&h.radio, 0x80u, page, 4u);
    phy6252_emu_tick(&h.radio, 10u);
    assert(h.cap.snv_count == 0u);
    phy6252_emu_tick(&h.radio, PHY6252_EMU_NOTIFY_PACE_MS);
    assert(h.cap.snv_count == 1u);
    assert(h.cap.snv_id == 0x80u);

    /* run_after_write: RX then one TX, not a nested pump. */
    bind(&h, true);
    phy6252_emu_connect(&h.radio);
    phy6252_emu_set_cccd(&h.radio, PHY6252_EMU_CCCD_SAMSUNG);
    assert(phy6252_emu_gatt_write(&h.radio, a, 4u));
    assert(phy6252_emu_gatt_write(&h.radio, b, 4u));
    phy6252_emu_run_after_write(&h.radio);
    assert(h.cap.rx_count == 2u);
    assert(h.cap.att_count == 1u);
    assert(h.radio.tx.in_flight);
    assert(h.radio.tx.count == 2u);

    return 0;
}
