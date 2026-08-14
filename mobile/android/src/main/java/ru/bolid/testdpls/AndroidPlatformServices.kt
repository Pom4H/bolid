package ru.bolid.testdpls

import java.security.SecureRandom
import ru.bolid.testdpls.core.app.DplsPlatformServices

internal class AndroidPlatformServices : DplsPlatformServices {
    private val random = SecureRandom()

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun secureRandomBytes(count: Int): ByteArray =
        ByteArray(count).also(random::nextBytes)
}
