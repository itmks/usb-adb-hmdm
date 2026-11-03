package id.webadb.hmdm

import android.content.Context
import android.hardware.usb.*
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/** Implementasi protokol ADB di atas USB Host (OTG) Android. */
class AdbConnection(
    private val ctx: Context,
    private val manager: UsbManager,
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

        fun findAdbInterface(d: UsbDevice): UsbInterface? {
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                if (f.interfaceClass == 255 && f.interfaceSubclass == 66 && f.interfaceProtocol == 1) return f
            }
            return null
        }

        private val SHA1_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
    }

    private class Pkt(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    class Stream(val local: Int) {
        var remote = 0
        var okay = false
        var closed = false
        val out = ByteArrayOutputStream()
    }

    private lateinit var conn: UsbDeviceConnection
    private lateinit var itf: UsbInterface
    private lateinit var epIn: UsbEndpoint
    private lateinit var epOut: UsbEndpoint
    private var acc = ByteArray(0)
    private val tmp = ByteArray(16384)
    private var maxData = 4096
    private var nextId = 1
    var banner = ""

    // ---------- koneksi ----------
    fun connect() {
        itf = findAdbInterface(dev) ?: throw IOException("Interface ADB tidak ditemukan pada perangkat")
        for (i in 0 until itf.endpointCount) {
            val e = itf.getEndpoint(i)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.direction == UsbConstants.USB_DIR_IN) epIn = e else epOut = e
            }
        }
        conn = manager.openDevice(dev) ?: throw IOException("Gagal membuka perangkat USB")
        if (!conn.claimInterface(itf, true)) throw IOException("Gagal claim interface USB (coba cabut-pasang kabel)")

        val hello = "host::\u0000".toByteArray()
        send(CNXN, 0x01000001, 1024 * 1024, hello, 0, hello.size)

        val deadline = System.currentTimeMillis() + 90_000
        var sentSig = false
        var sentKey = false
        while (true) {
            val p = readPacket(deadline)
            if (p.cmd == CNXN) {
                maxData = minOf(if (p.arg1 > 0) p.arg1 else 4096, 65536)
                banner = String(p.data)
                return
            } else if (p.cmd == AUTH && p.arg0 == 1) {
                if (!sentSig) {
                    val s = sign(p.data)
                    send(AUTH, 2, 0, s, 0, s.size)
                    sentSig = true
                } else {
                    val k = publicKeyBytes()
                    send(AUTH, 3, 0, k, 0, k.size)
                    if (!sentKey) log("Konfirmasi pop-up USB Debugging di layar HP target (pilih Selalu Izinkan)...")
                    sentKey = true
                }
            }
        }
    }

    fun close() {
        try { conn.releaseInterface(itf) } catch (_: Throwable) {}
        try { conn.close() } catch (_: Throwable) {}
    }

    // ---------- kunci RSA ----------
    private fun keys(): Pair<RSAPrivateKey, RSAPublicKey> {
        val sp = ctx.getSharedPreferences("adbkey", Context.MODE_PRIVATE)
        val kf = KeyFactory.getInstance("RSA")
        var priv = sp.getString("priv", null)
        var pub = sp.getString("pub", null)
        if (priv == null || pub == null) {
            val g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048)
            val kp = g.generateKeyPair()
            priv = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
            pub = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
            sp.edit().putString("priv", priv).putString("pub", pub).apply()
        }
        return Pair(
            kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))) as RSAPrivateKey,
            kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))) as RSAPublicKey
        )
    }

    private fun sign(token: ByteArray): ByteArray {
        val (priv, _) = keys()
        val payload = SHA1_PREFIX + token
        val padded = ByteArray(256)
        padded[1] = 1
        for (i in 2 until 256 - payload.size - 1) padded[i] = 0xFF.toByte()
        System.arraycopy(payload, 0, padded, 256 - payload.size, payload.size)
        val c = Cipher.getInstance("RSA/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, priv)
        return c.doFinal(padded)
    }

    private fun le(b: BigInteger, words: Int): ByteArray {
        val out = ByteArray(words * 4)
        val be = b.toByteArray()
        for (i in be.indices) { if (i < out.size) out[i] = be[be.size - 1 - i] }
        return out
    }

    private fun publicKeyBytes(): ByteArray {
        val (_, pub) = keys()
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(64).putInt(n0inv).put(le(n, 64)).put(le(rr, 64)).putInt(pub.publicExponent.toInt())
        val b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        return (b64 + " hmdm@android\u0000").toByteArray()
    }

    // ---------- paket ----------
    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray, off: Int, len: Int) {
        var sum = 0
        for (i in off until off + len) sum += data[i].toInt() and 0xff
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(cmd.inv())
        usbWrite(h.array(), 0, 24)
        if (len > 0) usbWrite(data, off, len)
    }

    private fun usbWrite(b: ByteArray, off: Int, len: Int) {
        var o = off
        val end = off + len
        while (o < end) {
            val n = minOf(16384, end - o)
            val r = conn.bulkTransfer(epOut, b, o, n, 10_000)
            if (r < 0) throw IOException("Gagal menulis ke USB (kabel terlepas?)")
            o += r
        }
    }

    private fun readExact(n: Int, deadline: Long): ByteArray {
        while (acc.size < n) {
            val r = conn.bulkTransfer(epIn, tmp, tmp.size, 1000)
            if (r > 0) acc += tmp.copyOf(r)
            else if (System.currentTimeMillis() > deadline) throw IOException("Timeout menunggu respon perangkat")
        }
        val out = acc.copyOf(n)
        acc = acc.copyOfRange(n, acc.size)
        return out
    }

    private fun readPacket(deadline: Long): Pkt {
        val h = ByteBuffer.wrap(readExact(24, deadline)).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int; val a0 = h.int; val a1 = h.int; val len = h.int
        h.int; val magic = h.int
        if (magic != cmd.inv()) throw IOException("Paket ADB tidak valid")
        val data = if (len > 0) readExact(len, deadline) else ByteArray(0)
        return Pkt(cmd, a0, a1, data)
    }

    // ---------- stream ----------
    private fun dispatch(s: Stream, p: Pkt) {
        when (p.cmd) {
            OKAY -> { s.remote = p.arg0; s.okay = true }
            WRTE -> { s.out.write(p.data); send(OKAY, s.local, s.remote, ByteArray(0), 0, 0) }
            CLSE -> s.closed = true
        }
    }

    private fun open(service: String): Stream {
        val s = Stream(nextId++)
        val d = (service + "\u0000").toByteArray()
        send(OPEN, s.local, 0, d, 0, d.size)
        val dl = System.currentTimeMillis() + 15_000
        while (!s.okay && !s.closed) dispatch(s, readPacket(dl))
        if (s.closed) throw IOException("Layanan ditolak perangkat: $service")
        return s
    }

    private fun write(s: Stream, b: ByteArray, len: Int) {
        send(WRTE, s.local, s.remote, b, 0, len)
        s.okay = false
        val dl = System.currentTimeMillis() + 60_000
        while (!s.okay && !s.closed) dispatch(s, readPacket(dl))
        if (s.closed) throw IOException("Perangkat menutup koneksi: " + s.out.toString("UTF-8").trim())
    }

    private fun readAll(s: Stream, timeoutMs: Long): String {
        val dl = System.currentTimeMillis() + timeoutMs
        while (!s.closed) dispatch(s, readPacket(dl))
        return s.out.toString("UTF-8").trim()
    }

    // ---------- API publik ----------
    fun shell(cmd: String): String = readAll(open("shell:$cmd"), 60_000)

    fun tcpip(port: Int): String = readAll(open("tcpip:$port"), 15_000)

    fun installApk(input: InputStream, size: Long, onProgress: (Int) -> Unit): String {
        val s = open("exec:cmd package install -S $size")
        val buf = ByteArray(maxData)
        var sent = 0L
        var last = -1
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (n == 0) continue
                write(s, buf, n)
                sent += n
                val pct = (sent * 100 / size).toInt()
                if (pct != last) { last = pct; onProgress(pct) }
            }
        }
        return readAll(s, 180_000)
    }
}
