@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.bolid.testdpls.core.app

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSMutableData
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.gettimeofday
import platform.posix.memcpy
import platform.posix.timeval

import ru.bolid.testdpls.core.domain.UiTheme

internal object IosPlatformServices : DplsPlatformServices {
    override fun nowMillis(): Long = memScoped {
        val tv = alloc<timeval>()
        gettimeofday(tv.ptr, null)
        tv.tv_sec * 1_000L + tv.tv_usec / 1_000L
    }

    override fun secureRandomBytes(count: Int): ByteArray {
        require(count >= 0)
        if (count == 0) return ByteArray(0)
        return ByteArray(count).also { bytes ->
            val status = bytes.usePinned { pinned ->
                SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pinned.addressOf(0))
            }
            check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
        }
    }

    override fun formatLocalDateTime(epochSeconds: Long): String {
        val formatter = NSDateFormatter()
        formatter.locale = NSLocale(localeIdentifier = "ru_RU")
        formatter.dateFormat = "d MMMM yyyy, HH:mm:ss"
        return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble()))
    }

    override fun readUiTheme(): UiTheme =
        UiTheme.fromWire(NSUserDefaults.standardUserDefaults.stringForKey(DplsPlatformPrefs.THEME))

    override fun writeUiTheme(theme: UiTheme) {
        NSUserDefaults.standardUserDefaults.setObject(theme.wire, DplsPlatformPrefs.THEME)
    }

    override fun readKeepScreenOn(): Boolean = readFlag(DplsPlatformPrefs.KEEP_SCREEN_ON, true)

    override fun writeKeepScreenOn(enabled: Boolean) = writeFlag(DplsPlatformPrefs.KEEP_SCREEN_ON, enabled)

    override fun readHapticsEnabled(): Boolean = readFlag(DplsPlatformPrefs.HAPTICS, true)

    override fun writeHapticsEnabled(enabled: Boolean) = writeFlag(DplsPlatformPrefs.HAPTICS, enabled)

    override fun readDeviceVerifier(deviceKey: String): ByteArray? {
        val data = NSUserDefaults.standardUserDefaults.dataForKey(DplsPlatformPrefs.verifierKey(deviceKey)) ?: return null
        return data.toByteArrayCopy()
    }

    override fun writeDeviceVerifier(deviceKey: String, verifier: ByteArray?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = DplsPlatformPrefs.verifierKey(deviceKey)
        if (verifier == null) defaults.removeObjectForKey(key)
        else defaults.setObject(verifier.toNSDataCopy(), key)
    }

    override fun readDeviceString(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(key)

    override fun writeDeviceString(key: String, value: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (value == null) defaults.removeObjectForKey(key)
        else defaults.setObject(value, key)
    }

    override fun openBluetoothSettings(): Boolean = false

    override fun canOpenSystemBluetoothSettings(): Boolean = false

    override fun keepConnectionAlive(active: Boolean) = Unit

    override fun notifyOperator(title: String, body: String) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
            if (!granted) return@requestAuthorizationWithOptions
            val content = UNMutableNotificationContent()
            content.setTitle(title)
            content.setBody(body)
            content.setSound(UNNotificationSound.defaultSound)
            val request = UNNotificationRequest.requestWithIdentifier(
                "dpls-${body.hashCode()}",
                content,
                null,
            )
            center.addNotificationRequest(request, null)
        }
    }

    private fun readFlag(key: String, default: Boolean): Boolean {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(key) == null) return default
        return defaults.boolForKey(key)
    }

    private fun writeFlag(key: String, enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, key)
    }
}

internal fun NSData.toByteArrayCopy(): ByteArray {
    if (length == 0UL) return ByteArray(0)
    val source = bytes ?: return ByteArray(0)
    return ByteArray(length.toInt()).also { target ->
        target.usePinned { pinned -> memcpy(pinned.addressOf(0), source, length) }
    }
}

internal fun ByteArray.toNSDataCopy(): NSData {
    if (isEmpty()) return NSMutableData()
    val data = NSMutableData()
    data.setLength(size.toULong())
    val dest = data.mutableBytes ?: return data
    usePinned { pinned ->
        memcpy(dest, pinned.addressOf(0), size.toULong())
    }
    return data
}
