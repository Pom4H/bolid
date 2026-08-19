#ifndef DPLS_SERVER_H
#define DPLS_SERVER_H

#include "dpls_protocol.h"
#include "dpls_safety.h"

#define DPLS_AUTH_NONCE_SIZE 16u
#define DPLS_AUTH_SALT_SIZE 16u
#define DPLS_AUTH_PROOF_SIZE 32u
#define DPLS_SESSION_TOKEN_SIZE 8u
#define DPLS_COMMAND_CACHE_SIZE 8u
#define DPLS_NAME_MAX 31u
#define DPLS_FW_VERSION_MAJOR 1u
#define DPLS_FW_VERSION_MINOR 4u
#define DPLS_FW_VERSION_PATCH 2u
#define DPLS_TIME_MIN_UNIX_SECONDS 1577836800u
#define DPLS_TIME_MAX_UNIX_SECONDS 4102444799u

enum {
    DPLS_CAP_ADC_PRESENT           = 1u << 0,
    DPLS_CAP_HW_READBACK           = 1u << 1,
    DPLS_CAP_ADC_CALIBRATED        = 1u << 2,
    DPLS_CAP_MULTI_VOLTAGE_REPORT  = 1u << 3,
    DPLS_CAP_HOST_SIM              = 1u << 6,
};

#ifndef DPLS_EVENT_CAPACITY
#define DPLS_EVENT_CAPACITY 200u
#endif
#define DPLS_LOG_CHUNK_EVENTS 15u
