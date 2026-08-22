package ru.bolid.testdpls.web

import kotlin.random.Random
import ru.bolid.testdpls.core.app.DplsPlatformServices
import ru.bolid.testdpls.core.domain.UiTheme

class LabPlatformServices : DplsPlatformServices {
    private val epochBaseMillis = dateNow()
    private val performanceBaseMillis = performanceNow()

    override fun nowMillis(): Long =
        (epochBaseMillis + (performanceNow() - performanceBaseMillis)).toLong()

    override fun secureRandomBytes(count: Int): ByteArray =
        ByteArray(count).also { Random.Default.nextBytes(it) }

    override fun readUiTheme(): UiTheme = UiTheme.fromWire(storageGet(THEME))

    override fun writeUiTheme(theme: UiTheme) {
        storageSet(THEME, theme.wire)
    }

    override fun readKeepScreenOn(): Boolean = storageGet(KEEP_SCREEN) != "0"

    override fun writeKeepScreenOn(enabled: Boolean) {
        storageSet(KEEP_SCREEN, if (enabled) "1" else "0")
    }

    override fun readHapticsEnabled(): Boolean = storageGet(HAPTICS) != "0"

    override fun writeHapticsEnabled(enabled: Boolean) {
        storageSet(HAPTICS, if (enabled) "1" else "0")
    }

    override fun readDeviceVerifier(deviceKey: String): ByteArray? {
        val hex = storageGet(verifierKey(deviceKey)) ?: return null
        return runCatching { hex.fromHex() }.getOrNull()
    }

    override fun writeDeviceVerifier(deviceKey: String, verifier: ByteArray?) {
        val key = verifierKey(deviceKey)
        if (verifier == null) storageRemove(key)
        else storageSet(key, verifier.toHex())
    }

    override fun readDeviceString(key: String): String? = storageGet("dpls.str.$key")

    override fun writeDeviceString(key: String, value: String?) {
        val storageKey = "dpls.str.$key"
        if (value == null) storageRemove(storageKey)
        else storageSet(storageKey, value)
    }

    private fun verifierKey(deviceKey: String): String = "dpls.verifier.$deviceKey"

    companion object {
        private const val THEME = "dpls.theme"
        private const val KEEP_SCREEN = "dpls.keepScreen"
        private const val HAPTICS = "dpls.haptics"
    }
}
