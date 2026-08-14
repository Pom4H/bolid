#ifndef DPLS_PROTOCOL_H
#define DPLS_PROTOCOL_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define DPLS_PROTOCOL_VERSION 1u
#define DPLS_PROTOCOL_OVERHEAD 9u
#define DPLS_MAX_PAYLOAD 235u
#define DPLS_MAX_FRAME (DPLS_PROTOCOL_OVERHEAD + DPLS_MAX_PAYLOAD)

typedef enum {
    DPLS_MSG_HELLO = 0x01,
    DPLS_MSG_AUTH_CHALLENGE = 0x02,
    DPLS_MSG_AUTH_PROOF = 0x03,
    DPLS_MSG_AUTH_RESULT = 0x04,
    DPLS_MSG_SETUP = 0x05,
    DPLS_MSG_DEVICE_INFO_GET = 0x06,
    DPLS_MSG_DEVICE_INFO_REPORT = 0x07,
    DPLS_MSG_NAME_SET = 0x08,
    DPLS_MSG_PASSWORD_SET = 0x09,
    DPLS_MSG_SETTINGS_RESULT = 0x0a,
    DPLS_MSG_TIME_SYNC = 0x0b,
    DPLS_MSG_STATE_GET = 0x10,
    DPLS_MSG_STATE_REPORT = 0x11,
    DPLS_MSG_MODE_SET = 0x12,
    DPLS_MSG_COMMAND_RESULT = 0x13,
    DPLS_MSG_IDENTIFY_START = 0x14,
    DPLS_MSG_IDENTIFY_STOP = 0x15,
    DPLS_MSG_LOG_START = 0x20,
    DPLS_MSG_LOG_INFO = 0x21,
    DPLS_MSG_LOG_CHUNK = 0x22,
    DPLS_MSG_LOG_ACK = 0x23,
    DPLS_MSG_LOG_FINISH = 0x24,
    DPLS_MSG_LOG_RESULT = 0x25,
    DPLS_MSG_KEEP_ALIVE = 0x30,
    DPLS_MSG_ERROR = 0x7f
} dpls_message_type_t;

typedef struct {
    uint8_t type;
    uint8_t flags;
    uint16_t sequence;
    uint16_t payload_length;
    uint8_t payload[DPLS_MAX_PAYLOAD];
} dpls_frame_t;

uint16_t dpls_crc16(const uint8_t *data, size_t length);
bool dpls_frame_decode(const uint8_t *data, size_t length, dpls_frame_t *out);
size_t dpls_frame_encode(const dpls_frame_t *frame, uint8_t *out, size_t capacity);

#endif
