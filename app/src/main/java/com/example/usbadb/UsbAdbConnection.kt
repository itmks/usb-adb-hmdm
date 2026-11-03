package com.example.usbadb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Implementasi minimal protokol ADB (CNXN / AUTH / OPEN / WRTE / OKAY / CLSE) lewat USB host. */
class UsbAdbConnection(
    private val conn: UsbDeviceConnection,
    private val usbIf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val crypto: AdbCrypto
) {
    companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val VERSION = 0x01000000
        const val MAX_DATA = 16384
        private const val USB_CHUNK = 16384 // batas aman per bulkTransfer
    }

    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    private var nextId = 1
    private var maxData = 4096
    private val syncResp = ByteArrayOutputStream()

    private fun write(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = conn.bulkTransfer(epOut, buf, off, minOf(buf.size - off, USB_CHUNK), 5000)
            if (n < 0) throw IOException("Gagal menulis ke USB")
            off += n
        }
    }

    private fun readFully(buf: ByteArray, len: Int, timeout: Int) {
        var off = 0
        while (off < len) {
            val n = conn.bulkTransfer(epIn, buf, off, minOf(len - off, USB_CHUNK), timeout)
            if (n < 0) throw IOException("Gagal membaca dari USB (timeout / terputus)")
            off += n
        }
    }

    private fun checksum(d: ByteArray): Int {
        var s = 0
        for (b in d) s += b.toInt() and 0xFF
        return s
    }

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(a0).putInt(a1)
        h.putInt(data.size).putInt(checksum(data)).putInt(cmd.inv())
        write(h.array())
        if (data.isNotEmpty()) write(data)
    }

    private fun recv(timeout: Int): Msg {
        val hb = ByteArray(24)
        readFully(hb, 24, timeout)
        val h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int; val a0 = h.int; val a1 = h.int; val len = h.int
        h.int; h.int // checksum, magic
        val data = ByteArray(len)
        if (len > 0) readFully(data, len, timeout)
        return Msg(cmd, a0, a1, data)
    }

    /** Handshake + autentikasi. HP target akan menampilkan dialog "Izinkan debugging USB?". */
    fun connect() {
        if (!conn.claimInterface(usbIf, true)) throw IOException("Gagal claim interface ADB")
        send(A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var signatureSent = false
        while (true) {
            val m = recv(60000)
            when (m.cmd) {
                A_CNXN -> {
                    maxData = minOf(MAX_DATA, m.a1).coerceAtLeast(256)
                    return
                }
                A_AUTH -> if (m.a0 == 1) { // TOKEN
                    if (!signatureSent) {
                        send(A_AUTH, 2, 0, crypto.signToken(m.data)) // SIGNATURE
                        signatureSent = true
                    } else {
                        send(A_AUTH, 3, 0, crypto.androidPublicKey()) // RSAPUBLICKEY -> muncul dialog
                    }
                }
            }
        }
    }

    /** Menjalankan perintah `adb shell <cmd>` dan mengalirkan outputnya. */
    fun shell(cmd: String, onOutput: (String) -> Unit) {
        val localId = nextId++
        send(A_OPEN, localId, 0, "shell:$cmd\u0000".toByteArray())
        while (true) {
            val m = recv(30000)
            if (m.a1 != localId) continue
            when (m.cmd) {
                A_WRTE -> {
                    onOutput(String(m.data, Charsets.UTF_8))
                    send(A_OKAY, localId, m.a0)
                }
                A_CLSE -> {
                    send(A_CLSE, localId, m.a0)
                    return
                }
            }
        }
    }

    // ---------- Sync (push file) ----------

    private fun syncHeader(id: String, value: Int): ByteArray {
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        b.put(id.toByteArray(Charsets.US_ASCII))
        b.putInt(value)
        return b.array()
    }

    /** Menunggu OKAY dari device untuk stream ini; WRTE yang datang disimpan di syncResp. */
    private fun waitOkay(localId: Int) {
        while (true) {
            val m = recv(30000)
            if (m.a1 != localId) continue
            when (m.cmd) {
                A_OKAY -> return
                A_WRTE -> {
                    syncResp.write(m.data)
                    send(A_OKAY, localId, m.a0)
                }
                A_CLSE -> throw IOException("Koneksi ditutup oleh perangkat")
            }
        }
    }

    /** Menulis data ke stream, dipecah sesuai maxData, menunggu OKAY tiap potongan. */
    private fun writeStream(localId: Int, remoteId: Int, data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = minOf(maxData, data.size - off)
            send(A_WRTE, localId, remoteId, data.copyOfRange(off, off + n))
            off += n
            waitOkay(localId)
        }
    }

    /** Setara `adb push`: mengirim isi [input] ke [remotePath] dengan [mode] (mis. 33188 = 0644). */
    fun push(remotePath: String, mode: Int, input: InputStream, onProgress: (Long) -> Unit) {
        syncResp.reset()
        val localId = nextId++
        send(A_OPEN, localId, 0, "sync:\u0000".toByteArray())
        var remoteId = -1
        while (remoteId < 0) {
            val m = recv(30000)
            if (m.a1 != localId) continue
            when (m.cmd) {
                A_OKAY -> remoteId = m.a0
                A_CLSE -> throw IOException("Perangkat menolak layanan sync")
            }
        }

        val spec = "$remotePath,$mode".toByteArray()
        writeStream(localId, remoteId, syncHeader("SEND", spec.size) + spec)

        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            writeStream(localId, remoteId, syncHeader("DATA", n) + buf.copyOf(n))
            total += n
            onProgress(total)
        }
        writeStream(localId, remoteId, syncHeader("DONE", (System.currentTimeMillis() / 1000).toInt()))

        while (syncResp.size() < 8) {
            val m = recv(30000)
            if (m.a1 != localId) continue
            when (m.cmd) {
                A_WRTE -> {
                    syncResp.write(m.data)
                    send(A_OKAY, localId, m.a0)
                }
                A_CLSE -> throw IOException("Koneksi ditutup sebelum ada konfirmasi")
            }
        }
        val r = syncResp.toByteArray()
        send(A_CLSE, localId, remoteId)
        if (String(r, 0, 4, Charsets.US_ASCII) != "OKAY") {
            val msg = if (r.size > 8) String(r, 8, r.size - 8) else "tidak diketahui"
            throw IOException("Push gagal: $msg")
        }
    }

    fun close() {
        try { conn.releaseInterface(usbIf) } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }
}
