#include "dpls_protocol.h"
#include <string.h>

static uint16_t read_u16(const uint8_t *p) { return (uint16_t)(p[0] | ((uint16_t)p[1] << 8)); }
static void write_u16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }

uint16_t dpls_crc16(const uint8_t *data, size_t length) {
    uint16_t crc = 0xffffu;
    size_t i;
    for (i = 0; i < length; ++i) {
        uint8_t bit;
        crc ^= (uint16_t)data[i] << 8;
        for (bit = 0; bit < 8; ++bit) crc = (crc & 0x8000u) ? (uint16_t)((crc << 1) ^ 0x1021u) : (uint16_t)(crc << 1);
    }
    return crc;
}

bool dpls_frame_decode(const uint8_t *data, size_t length, dpls_frame_t *out) {
    uint16_t payload_length;
    if (!data || !out || length < DPLS_PROTOCOL_OVERHEAD || data[0] != DPLS_PROTOCOL_VERSION) return false;
    payload_length = read_u16(data + 5);
    if (payload_length > DPLS_MAX_PAYLOAD || length != DPLS_PROTOCOL_OVERHEAD + payload_length) return false;
    if (read_u16(data + length - 2) != dpls_crc16(data, length - 2)) return false;
    out->type = data[1]; out->flags = data[2]; out->sequence = read_u16(data + 3); out->payload_length = payload_length;
    if (payload_length) memcpy(out->payload, data + 7, payload_length);
    return true;
}

size_t dpls_frame_encode(const dpls_frame_t *frame, uint8_t *out, size_t capacity) {
    size_t length;
    if (!frame || !out || frame->payload_length > DPLS_MAX_PAYLOAD) return 0;
    length = DPLS_PROTOCOL_OVERHEAD + frame->payload_length;
    if (capacity < length) return 0;
    out[0] = DPLS_PROTOCOL_VERSION; out[1] = frame->type; out[2] = frame->flags;
    write_u16(out + 3, frame->sequence); write_u16(out + 5, frame->payload_length);
    if (frame->payload_length) memcpy(out + 7, frame->payload, frame->payload_length);
    write_u16(out + length - 2, dpls_crc16(out, length - 2));
    return length;
}
