package ru.bolid.testdpls.core.protocol

/** Small dependency-free SHA-256/HMAC/PBKDF2 implementation used by Test-DPLS authentication. */
object DplsCrypto {
    fun deriveVerifier(password: String, salt: ByteArray): ByteArray =
        pbkdf2HmacSha256(password.encodeToByteArray(), salt, DplsAuth.PBKDF2_ITERATIONS, DplsAuth.VERIFIER_SIZE)

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val blockKey = if (key.size > 64) sha256(key) else key.copyOf()
        val block = ByteArray(64)
        blockKey.copyInto(block)
        val innerPad = ByteArray(64)
        val outerPad = ByteArray(64)
        for (i in 0 until 64) {
            innerPad[i] = (block[i].toInt() xor 0x36).toByte()
            outerPad[i] = (block[i].toInt() xor 0x5c).toByte()
        }
        val inner = sha256(innerPad + message)
        val result = sha256(outerPad + inner)
        blockKey.fill(0)
        block.fill(0)
        innerPad.fill(0)
        outerPad.fill(0)
        inner.fill(0)
        return result
    }

    fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, outputBytes: Int): ByteArray {
        require(iterations > 0)
        require(outputBytes > 0)
        val result = ByteArray(outputBytes)
        val blockCount = (outputBytes + 31) / 32
        var outputOffset = 0
        for (blockIndex in 1..blockCount) {
            val suffix = byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte(),
            )
            var u = hmacSha256(password, salt + suffix)
            val t = u.copyOf()
            repeat(iterations - 1) {
                val next = hmacSha256(password, u)
                u.fill(0)
                u = next
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val count = minOf(32, outputBytes - outputOffset)
            t.copyInto(result, outputOffset, 0, count)
            outputOffset += count
            u.fill(0)
            t.fill(0)
        }
        return result
    }

    internal fun sha256(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8L
        val paddedLength = ((input.size + 9 + 63) / 64) * 64
        val data = ByteArray(paddedLength)
        input.copyInto(data)
        data[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            data[paddedLength - 1 - i] = (bitLength ushr (i * 8)).toByte()
        }

        var h0 = 0x6a09e667
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19
        val w = IntArray(64)

        for (chunk in data.indices step 64) {
            for (i in 0 until 16) {
                val o = chunk + i * 4
                w[i] = ((data[o].toInt() and 0xff) shl 24) or
                    ((data[o + 1].toInt() and 0xff) shl 16) or
                    ((data[o + 2].toInt() and 0xff) shl 8) or
                    (data[o + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h0 += a; h1 += b; h2 += c; h3 += d
            h4 += e; h5 += f; h6 += g; h7 += h
        }

        data.fill(0)
        w.fill(0)
        return ByteArray(32).also { out ->
            val words = intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
            for (i in words.indices) {
                out[i * 4] = (words[i] ushr 24).toByte()
                out[i * 4 + 1] = (words[i] ushr 16).toByte()
                out[i * 4 + 2] = (words[i] ushr 8).toByte()
                out[i * 4 + 3] = words[i].toByte()
            }
        }
    }

    private val K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )
}
