package com.example.usbadb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
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
        const val MAX_DATA = 4096
    }

    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    private var nextId = 1

    private fun write(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = conn.bulkTransfer(epOut, buf, off, buf.size - off, 5000)
            if (n < 0) throw IOException("Gagal menulis ke USB")
            off += n
        }
    }

    private fun readFully(buf: ByteArray, len: Int, timeout: Int) {
        var off = 0
        while (off < len) {
            val n = conn.bulkTransfer(epIn, buf, off, len - off, timeout)
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
                A_CNXN -> return
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

    fun close() {
        try { conn.releaseInterface(usbIf) } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }
}
