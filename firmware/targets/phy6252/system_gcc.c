#include <stddef.h>
#include <stdint.h>

uint32_t SystemCoreClock = 25000000u;

void SystemCoreClockUpdate(void) {
    SystemCoreClock = 25000000u;
}

void SystemInit(void) {
    SystemCoreClock = 25000000u;
}

void *memcpy(void *dst, const void *src, size_t n) {
    unsigned char *d = dst;
    const unsigned char *s = src;
    while (n > 0u) {
        *d++ = *s++;
        n--;
    }
    return dst;
}

void *memmove(void *dst, const void *src, size_t n) {
    unsigned char *d = dst;
    const unsigned char *s = src;
    if (d == s || n == 0u) {
        return dst;
    }
    if (d < s) {
        return memcpy(dst, src, n);
    }
    d += n;
    s += n;
    while (n > 0u) {
        *--d = *--s;
        n--;
    }
    return dst;
}

void *memset(void *dst, int c, size_t n) {
    unsigned char *d = dst;
    unsigned char v = (unsigned char)c;
    while (n > 0u) {
        *d++ = v;
        n--;
    }
    return dst;
}

int memcmp(const void *a, const void *b, size_t n) {
    const unsigned char *p = a;
    const unsigned char *q = b;
    while (n > 0u) {
        if (*p != *q) {
            return (int)*p - (int)*q;
        }
        p++;
        q++;
        n--;
    }
    return 0;
}

size_t strlen(const char *s) {
    size_t n = 0u;
    while (s[n] != '\0') {
        n++;
    }
    return n;
}
