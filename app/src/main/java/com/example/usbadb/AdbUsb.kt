package com.example.usbadb

import android.content.Context
import android.hardware.usb.*
import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/** Implementasi minimal protokol ADB lewat USB host. */
class AdbUsb(
    private val ctx: Context,
    private val usb: UsbManager,
    private val dev: UsbDevice,
    private val log: (String) -> Unit
) {
    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257

        /** Cari device yang punya interface ADB (class 0xFF, subclass 0x42, protocol 1). */
        fun findAdbInterface(d: UsbDevice): UsbInterface? {
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                if (f.interfaceClass == 255 && f.interfaceSubclass == 66 && f.interfaceProtocol == 1) return f
            }
            return null
        }
    }

    class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    private lateinit var conn: UsbDeviceConnection
    private lateinit var ein: UsbEndpoint
    private lateinit var eout: UsbEndpoint
    private var maxData = 4096
    private var nextId = 1

    fun connect() {
        val intf = findAdbInterface(dev) ?: throw IOException("Perangkat tidak punya interface ADB. Aktifkan USB debugging.")
        for (i in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(i)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.direction == UsbConstants.USB_DIR_IN) ein = e else eout = e
            }
        }
        conn = usb.openDevice(dev) ?: throw IOException("Gagal membuka perangkat USB")
        conn.claimInterface(intf, true)

        val kp = keys()
        send(CNXN, 0x01000000, 4096, "host::\u0000".toByteArray())
        var signed = false
        while (true) {
            val m = recv()
            when (m.cmd) {
                CNXN -> { maxData = minOf(m.a1, 4096); log("Terhubung: " + String(m.data).trim('\u0000')); return }
                AUTH -> {
                    if (!signed) {
                        send(AUTH, 2, 0, sign(kp, m.data)); signed = true
                    } else {
                        send(AUTH, 3, 0, pubKeyAndroid(kp.public as RSAPublicKey))
                        log("Cek layar HP target, tap 'Izinkan debugging USB' (centang 'Selalu izinkan').")
                    }
                }
            }
        }
    }

    fun close() { try { conn.close() } catch (_: Exception) {} }

    /** Jalankan service ADB (mis. "shell:ls" atau "exec:cmd package install -S 123"), opsional kirim stream data. */
    fun exec(service: String, input: InputStream? = null): String {
        val lid = nextId++
        send(OPEN, lid, 0, (service + "\u0000").toByteArray())
        var rid: Int
        while (true) {
            val m = recv()
            if (m.cmd == OKAY && m.a1 == lid) { rid = m.a0; break }
            if (m.cmd == CLSE) throw IOException("Service ditolak oleh perangkat")
        }
        val out = StringBuilder()
        if (input != null) {
            val buf = ByteArray(maxData)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                send(WRTE, lid, rid, buf.copyOf(n))
                total += n
                while (true) {
                    val m = recv()
                    if (m.cmd == OKAY) break
                    if (m.cmd == WRTE) { out.append(String(m.data)); send(OKAY, lid, rid) }
                    if (m.cmd == CLSE) return out.toString()
                }
            }
            log("Terkirim ${total / 1024} KB")
        }
        while (true) {
            val m = recv()
            when (m.cmd) {
                WRTE -> { out.append(String(m.data)); send(OKAY, lid, rid) }
                CLSE -> { try { send(CLSE, lid, rid) } catch (_: Exception) {}; return out.toString().trim() }
            }
        }
    }

    // ---- low level ----
    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd xor -1)
        if (conn.bulkTransfer(eout, bb.array(), 24, 10000) < 0) throw IOException("Gagal menulis ke USB")
        if (data.isNotEmpty() && conn.bulkTransfer(eout, data, data.size, 10000) < 0)
            throw IOException("Gagal menulis data ke USB")
    }

    private fun recv(timeout: Int = 30000): Msg {
        val h = ByteArray(24)
        var got = 0
        while (got < 24) {
            val r = conn.bulkTransfer(ein, h, got, 24 - got, timeout)
            if (r < 0) throw IOException("Timeout/gagal membaca USB")
            got += r
        }
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int; val a0 = bb.int; val a1 = bb.int; val len = bb.int
        val data = ByteArray(len)
        var o = 0
        while (o < len) {
            val r = conn.bulkTransfer(ein, data, o, len - o, timeout)
            if (r < 0) throw IOException("Timeout/gagal membaca data USB")
            o += r
        }
        return Msg(cmd, a0, a1, data)
    }

    // ---- auth ----
    private fun keys(): KeyPair {
        val pf = File(ctx.filesDir, "adbkey.priv"); val bf = File(ctx.filesDir, "adbkey.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (pf.exists() && bf.exists()) {
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(bf.readBytes())),
                kf.generatePrivate(PKCS8EncodedKeySpec(pf.readBytes()))
            )
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        pf.writeBytes(kp.private.encoded); bf.writeBytes(kp.public.encoded)
        return kp
    }

    private fun sign(kp: KeyPair, token: ByteArray): ByteArray {
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        val block = ByteArray(256)
        block[1] = 1
        val padEnd = 256 - prefix.size - token.size - 1
        for (i in 2 until padEnd) block[i] = 0xff.toByte()
        System.arraycopy(prefix, 0, block, padEnd + 1, prefix.size)
        System.arraycopy(token, 0, block, padEnd + 1 + prefix.size, token.size)
        val c = Cipher.getInstance("RSA/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, kp.private)
        return c.doFinal(block)
    }

    private fun words(v: BigInteger, count: Int, bb: ByteBuffer) {
        for (i in 0 until count) bb.putInt(v.shiftRight(32 * i).toInt())
    }

    private fun pubKeyAndroid(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(64).putInt(n0inv)
        words(n, 64, bb); words(rr, 64, bb)
        bb.putInt(pub.publicExponent.toInt())
        return Base64.encode(bb.array(), Base64.NO_WRAP) + " usbadb@android\u0000".toByteArray()
    }
}
