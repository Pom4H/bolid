package ru.bolid.testdpls.core.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import ru.bolid.testdpls.core.domain.UiTheme
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidPlatformServices(context: Context) : DplsPlatformServices {
    private val appContext = context.applicationContext
    private val random = SecureRandom()
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val verifierStore = AndroidVerifierStore(prefs)

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

    override fun readDeviceVerifier(deviceKey: String): ByteArray? = verifierStore.read(deviceKey)

    override fun writeDeviceVerifier(deviceKey: String, verifier: ByteArray?) {
        verifierStore.write(deviceKey, verifier)
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

    override fun sessionTrace(message: String) {
        android.util.Log.i(SESSION_TAG, message)
        // Phone E2E and session_capture multiplex these tags from logcat.
        if (
            message.startsWith("E2E ") ||
            message.startsWith("LOG_") ||
            message.startsWith("STATE ") ||
            message.startsWith("FRAME ")
        ) {
            android.util.Log.i(BLE_TAG, message)
        }
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
        const val SESSION_TAG = "TestDplsSession"
        const val BLE_TAG = "TestDplsBle"
        val dateTimeFormat = SimpleDateFormat("d MMMM yyyy, HH:mm:ss", Locale.forLanguageTag("ru"))
    }
}

/**
 * Verifiers are authentication-equivalent secrets: possession is enough to build AUTH_PROOF.
 * Keep only AES-GCM ciphertext in SharedPreferences; the AES key itself never leaves Android Keystore.
 */
private class AndroidVerifierStore(
    private val prefs: SharedPreferences,
) {
    fun read(deviceKey: String): ByteArray? {
        val prefKey = DplsPlatformPrefs.verifierKey(deviceKey)
        val stored = prefs.getString(prefKey, null) ?: return null
        if (!stored.startsWith(FORMAT_PREFIX)) return migrateLegacy(prefKey, deviceKey, stored)

        return runCatching {
            val parts = stored.removePrefix(FORMAT_PREFIX).split(':', limit = 2)
            require(parts.size == 2)
            val iv = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
            val ciphertext = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        }.getOrElse {
            prefs.edit { remove(prefKey) }
            null
        }
    }

    fun write(deviceKey: String, verifier: ByteArray?) {
        val prefKey = DplsPlatformPrefs.verifierKey(deviceKey)
        if (verifier == null) {
            prefs.edit { remove(prefKey) }
            return
        }

        val encoded = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(verifier)
            val iv = android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP)
            val body = android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)
            "$FORMAT_PREFIX$iv:$body"
        }.getOrNull() ?: return
        prefs.edit { putString(prefKey, encoded) }
    }

    private fun migrateLegacy(prefKey: String, deviceKey: String, stored: String): ByteArray? {
        val legacy = runCatching {
            android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
        }.getOrNull()
        if (legacy == null || legacy.size != 32) {
            prefs.edit { remove(prefKey) }
            return null
        }
        write(deviceKey, legacy)
        // Never retain the old unprotected form if Keystore migration failed.
        if (prefs.getString(prefKey, null)?.startsWith(FORMAT_PREFIX) != true) {
            prefs.edit { remove(prefKey) }
        }
        return legacy
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "testdpls.device-verifier.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FORMAT_PREFIX = "v1:"
    }
}
