package ru.bolid.testdpls.core.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import ru.bolid.testdpls.core.domain.UiTheme
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale

class AndroidPlatformServices(context: Context) : DplsPlatformServices {
    private val appContext = context.applicationContext
    private val random = SecureRandom()
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun secureRandomBytes(count: Int): ByteArray =
        ByteArray(count).also(random::nextBytes)

    override fun formatLocalDateTime(epochSeconds: Long): String =
        synchronized(dateTimeFormat) { dateTimeFormat.format(java.util.Date(epochSeconds * 1000L)) }

    override fun readUiTheme(): UiTheme = UiTheme.fromWire(prefs.getString(DplsPlatformPrefs.THEME, null))

    override fun writeUiTheme(theme: UiTheme) {
        prefs.edit { putString(DplsPlatformPrefs.THEME, theme.wire) }
    }

    override fun readKeepScreenOn(): Boolean =
        if (prefs.contains(DplsPlatformPrefs.KEEP_SCREEN_ON)) {
            prefs.getBoolean(DplsPlatformPrefs.KEEP_SCREEN_ON, true)
        } else {
            true
        }

    override fun writeKeepScreenOn(enabled: Boolean) {
        prefs.edit { putBoolean(DplsPlatformPrefs.KEEP_SCREEN_ON, enabled) }
    }

    override fun readHapticsEnabled(): Boolean =
        if (prefs.contains(DplsPlatformPrefs.HAPTICS)) {
            prefs.getBoolean(DplsPlatformPrefs.HAPTICS, true)
        } else {
            true
        }

    override fun writeHapticsEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(DplsPlatformPrefs.HAPTICS, enabled) }
    }

    override fun readDeviceVerifier(deviceKey: String): ByteArray? {
        val raw = prefs.getString(DplsPlatformPrefs.verifierKey(deviceKey), null) ?: return null
        return try {
            android.util.Base64.decode(raw, android.util.Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun writeDeviceVerifier(deviceKey: String, verifier: ByteArray?) {
        val key = DplsPlatformPrefs.verifierKey(deviceKey)
        prefs.edit {
            if (verifier == null) {
                remove(key)
            } else {
                putString(key, android.util.Base64.encodeToString(verifier, android.util.Base64.NO_WRAP))
            }
        }
    }

    override fun readDeviceString(key: String): String? = prefs.getString(key, null)

    override fun writeDeviceString(key: String, value: String?) {
        prefs.edit {
            if (value == null) remove(key)
            else putString(key, value)
        }
    }

    override fun openBluetoothSettings(): Boolean {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent) }.isSuccess
    }

    override fun canOpenSystemBluetoothSettings(): Boolean = true

    override fun keepConnectionAlive(active: Boolean) {
        val intent = Intent(appContext, AndroidBleKeepAlive::class.java)
        if (active) {
            ContextCompat.startForegroundService(appContext, intent)
        } else {
            appContext.stopService(intent)
        }
    }

    override fun notifyOperator(title: String, body: String) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels()
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        } ?: Intent()
        val content = PendingIntent.getActivity(
            appContext,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(appContext, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        appContext.getSystemService(NotificationManager::class.java)
            .notify(body.hashCode(), notification)
    }

    private fun ensureChannels() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "События Test-DPLS", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Ошибки и возврат устройства в режим Норма" },
        )
    }

    private companion object {
        const val PREFS = "testdpls"
        const val ALERT_CHANNEL_ID = "dpls_alerts"
        val dateTimeFormat = SimpleDateFormat("d MMMM yyyy, HH:mm:ss", Locale.forLanguageTag("ru"))
    }
}
