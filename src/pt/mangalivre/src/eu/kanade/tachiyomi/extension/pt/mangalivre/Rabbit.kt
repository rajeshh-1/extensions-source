package eu.kanade.tachiyomi.extension.pt.mangalivre

import okio.ByteString.Companion.decodeBase64
import java.security.MessageDigest

/**
 * CryptoJS-compatible Rabbit stream cipher in OpenSSL "Salted__" passphrase mode.
 *
 * toonlivre.net encrypts chapter-page responses as `{"<x-toon-datakey>": "<base64>"}`,
 * where the base64 is produced by `CryptoJS.Rabbit.encrypt(json, passphrase)`. This is a
 * faithful port of crypto-js/rabbit.js + evpkdf.js so the extension can decrypt them.
 *
 * The g-function is computed with exact 64-bit integer math instead of CryptoJS's
 * double-precision expression; the two were verified bit-identical over 2M random inputs.
 */
internal object Rabbit {

    /** Decrypt an OpenSSL-formatted CryptoJS Rabbit ciphertext (base64) with [passphrase]. */
    fun decrypt(cipherBase64: String, passphrase: String): String {
        val raw = cipherBase64.decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("invalid base64")
        require(raw.size > 16 && String(raw, 0, 8, Charsets.US_ASCII) == "Salted__") {
            "missing OpenSSL salt header"
        }
        val salt = raw.copyOfRange(8, 16)
        val ciphertext = raw.copyOfRange(16, raw.size)

        // OpenSSL passphrase mode derives 16-byte key + 8-byte IV via EVP_BytesToKey(MD5).
        val keyIv = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt, 24)
        val key = bytesToWords(keyIv, 0, 16)
        val iv = bytesToWords(keyIv, 16, 8)

        val plain = process(bytesToWords(ciphertext, 0, ciphertext.size), ciphertext.size, key, iv)
        return String(plain, Charsets.UTF_8)
    }

    /** EVP_BytesToKey with MD5, 1 iteration (CryptoJS OpenSSLKdf default). */
    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, total: Int): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        val out = ByteArray(total)
        var filled = 0
        var block = ByteArray(0)
        while (filled < total) {
            md5.reset()
            md5.update(block)
            md5.update(password)
            block = md5.digest(salt) // MD5(prevBlock || password || salt)
            val n = minOf(block.size, total - filled)
            System.arraycopy(block, 0, out, filled, n)
            filled += n
        }
        return out
    }

    // Big-endian bytes -> 32-bit words (last partial word zero-padded in its low bytes).
    private fun bytesToWords(bytes: ByteArray, offset: Int, length: Int): IntArray {
        val words = IntArray((length + 3) / 4)
        for (i in 0 until length) {
            words[i ushr 2] = words[i ushr 2] or
                ((bytes[offset + i].toInt() and 0xff) shl (24 - (i % 4) * 8))
        }
        return words
    }

    private fun wordsToBytes(words: IntArray, sigBytes: Int): ByteArray {
        val out = ByteArray(sigBytes)
        for (i in 0 until sigBytes) {
            out[i] = ((words[i ushr 2] ushr (24 - (i % 4) * 8)) and 0xff).toByte()
        }
        return out
    }

    private val ADD = intArrayOf(
        0x4d34d34d, 0xd34d34d3.toInt(), 0x34d34d34, 0x4d34d34d,
        0xd34d34d3.toInt(), 0x34d34d34, 0x4d34d34d, 0xd34d34d3.toInt(),
    )

    private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun process(cipherWords: IntArray, sigBytes: Int, keyWords: IntArray, ivWords: IntArray): ByteArray {
        val k = IntArray(4) { keyWords[it] }
        // Swap endian of the key words.
        for (i in 0 until 4) {
            k[i] = (((k[i] shl 8) or (k[i] ushr 24)) and 0x00ff00ff) or
                (((k[i] shl 24) or (k[i] ushr 8)) and 0xff00ff00.toInt())
        }

        val x = intArrayOf(
            k[0], (k[3] shl 16) or (k[2] ushr 16),
            k[1], (k[0] shl 16) or (k[3] ushr 16),
            k[2], (k[1] shl 16) or (k[0] ushr 16),
            k[3], (k[2] shl 16) or (k[1] ushr 16),
        )
        val c = intArrayOf(
            (k[2] shl 16) or (k[2] ushr 16), (k[0] and 0xffff0000.toInt()) or (k[1] and 0x0000ffff),
            (k[3] shl 16) or (k[3] ushr 16), (k[1] and 0xffff0000.toInt()) or (k[2] and 0x0000ffff),
            (k[0] shl 16) or (k[0] ushr 16), (k[2] and 0xffff0000.toInt()) or (k[3] and 0x0000ffff),
            (k[1] shl 16) or (k[1] ushr 16), (k[3] and 0xffff0000.toInt()) or (k[0] and 0x0000ffff),
        )
        val b = intArrayOf(0) // carry bit, mutable holder

        repeat(4) { nextState(x, c, b) }
        for (i in 0 until 8) c[i] = c[i] xor x[(i + 4) and 7]

        // IV setup.
        val iv0 = ivWords[0]
        val iv1 = ivWords[1]
        val i0 = (((iv0 shl 8) or (iv0 ushr 24)) and 0x00ff00ff) or (((iv0 shl 24) or (iv0 ushr 8)) and 0xff00ff00.toInt())
        val i2 = (((iv1 shl 8) or (iv1 ushr 24)) and 0x00ff00ff) or (((iv1 shl 24) or (iv1 ushr 8)) and 0xff00ff00.toInt())
        val i1 = (i0 ushr 16) or (i2 and 0xffff0000.toInt())
        val i3 = (i2 shl 16) or (i0 and 0x0000ffff)
        c[0] = c[0] xor i0; c[1] = c[1] xor i1; c[2] = c[2] xor i2; c[3] = c[3] xor i3
        c[4] = c[4] xor i0; c[5] = c[5] xor i1; c[6] = c[6] xor i2; c[7] = c[7] xor i3
        repeat(4) { nextState(x, c, b) }

        val nWords = (sigBytes + 3) / 4
        val nBlocks = (nWords + 3) / 4
        val m = IntArray(nBlocks * 4)
        System.arraycopy(cipherWords, 0, m, 0, cipherWords.size)

        val s = IntArray(4)
        var offset = 0
        while (offset < nBlocks * 4) {
            nextState(x, c, b)
            s[0] = x[0] xor (x[5] ushr 16) xor (x[3] shl 16)
            s[1] = x[2] xor (x[7] ushr 16) xor (x[5] shl 16)
            s[2] = x[4] xor (x[1] ushr 16) xor (x[7] shl 16)
            s[3] = x[6] xor (x[3] ushr 16) xor (x[1] shl 16)
            for (i in 0 until 4) {
                val swapped = (((s[i] shl 8) or (s[i] ushr 24)) and 0x00ff00ff) or
                    (((s[i] shl 24) or (s[i] ushr 8)) and 0xff00ff00.toInt())
                m[offset + i] = m[offset + i] xor swapped
            }
            offset += 4
        }
        return wordsToBytes(m, sigBytes)
    }

    private fun nextState(x: IntArray, c: IntArray, b: IntArray) {
        val cOld = IntArray(8) { c[it] }

        c[0] = c[0] + ADD[0] + b[0]
        for (i in 1 until 8) {
            val carry = if (c[i - 1].toUInt() < cOld[i - 1].toUInt()) 1 else 0
            c[i] = c[i] + ADD[i] + carry
        }
        b[0] = if (c[7].toUInt() < cOld[7].toUInt()) 1 else 0

        val g = IntArray(8)
        for (i in 0 until 8) {
            val gx = (x[i] + c[i]).toLong() and 0xffffffffL
            val square = gx * gx
            g[i] = ((square and 0xffffffffL) xor ((square ushr 32) and 0xffffffffL)).toInt()
        }

        x[0] = g[0] + rotl(g[7], 16) + rotl(g[6], 16)
        x[1] = g[1] + rotl(g[0], 8) + g[7]
        x[2] = g[2] + rotl(g[1], 16) + rotl(g[0], 16)
        x[3] = g[3] + rotl(g[2], 8) + g[1]
        x[4] = g[4] + rotl(g[3], 16) + rotl(g[2], 16)
        x[5] = g[5] + rotl(g[4], 8) + g[3]
        x[6] = g[6] + rotl(g[5], 16) + rotl(g[4], 16)
        x[7] = g[7] + rotl(g[6], 8) + g[5]
    }
}
