package com.example.usbadb

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** Kunci RSA 2048-bit untuk autentikasi ADB. Disimpan agar HP target bisa "Selalu izinkan". */
class AdbCrypto private constructor(private val priv: PrivateKey, private val pub: PublicKey) {

    /** Menandatangani token 20 byte dari device (sudah berupa hash, jadi tidak di-hash lagi). */
    fun signToken(token: ByteArray): ByteArray {
        val sha1Prefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val s = Signature.getInstance("NONEwithRSA")
        s.initSign(priv)
        s.update(sha1Prefix)
        s.update(token)
        return s.sign()
    }

    /** Public key dalam format struct RSAPublicKey milik Android, di-Base64, diakhiri NUL. */
    fun androidPublicKey(): ByteArray {
        val k = pub as RSAPublicKey
        val n = k.modulus
        val words = 64
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(two32).modInverse(two32).negate().mod(two32).toInt()
        val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)

        val bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(words)
        bb.putInt(n0inv)
        bb.put(littleEndian(n, 256))
        bb.put(littleEndian(rr, 256))
        bb.putInt(k.publicExponent.toInt())

        val b64 = Base64.encode(bb.array(), Base64.NO_WRAP)
        return b64 + " usbadb@android\u0000".toByteArray()
    }

    private fun littleEndian(v: BigInteger, size: Int): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until minOf(be.size, size)) out[i] = be[be.size - 1 - i]
        return out
    }

    companion object {
        fun loadOrCreate(dir: File): AdbCrypto {
            val pf = File(dir, "adb_priv.key")
            val uf = File(dir, "adb_pub.key")
            val kf = KeyFactory.getInstance("RSA")
            if (pf.exists() && uf.exists()) {
                try {
                    return AdbCrypto(
                        kf.generatePrivate(PKCS8EncodedKeySpec(pf.readBytes())),
                        kf.generatePublic(X509EncodedKeySpec(uf.readBytes()))
                    )
                } catch (_: Exception) { /* buat ulang */ }
            }
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(2048)
            val kp = g.generateKeyPair()
            pf.writeBytes(kp.private.encoded)
            uf.writeBytes(kp.public.encoded)
            return AdbCrypto(kp.private, kp.public)
        }
    }
}
