@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.bolid.testdpls.core.app

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.memcpy

internal fun secureRandomBytes(count: Int): ByteArray {
    require(count >= 0)
    if (count == 0) return ByteArray(0)
    return ByteArray(count).also { bytes ->
        val status = bytes.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pinned.addressOf(0))
        }
        check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    }
}

internal fun NSData.toByteArrayCopy(): ByteArray {
    if (length == 0UL) return ByteArray(0)
    val source = bytes ?: return ByteArray(0)
    return ByteArray(length.toInt()).also { target ->
        target.usePinned { pinned -> memcpy(pinned.addressOf(0), source, length) }
    }
}

internal fun ByteArray.toNSDataCopy(): NSData = usePinned { pinned ->
    NSData.create(
        bytes = if (isEmpty()) null else pinned.addressOf(0),
        length = size.toULong(),
    )
}
