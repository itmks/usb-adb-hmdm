package com.example.usbadb

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private val actionPerm = "com.example.usbadb.USB_PERMISSION"
    private val reqPickApk = 1001
    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var adb: UsbAdbConnection? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != actionPerm) return
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null)
                connect(dev)
            else setStatus("Izin USB ditolak")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(USB_SERVICE) as UsbManager

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { text = "Belum terhubung"; textSize = 16f }
        val btnConnect = Button(this).apply {
            text = "Hubungkan perangkat"
            setOnClickListener { findAndConnect() }
        }
        input = EditText(this).apply {
            hint = "Perintah shell, mis. getprop ro.product.model"
            setSingleLine()
        }
        val btnRun = Button(this).apply {
            text = "Jalankan"
            setOnClickListener { runCommand() }
        }
        val btnInstall = Button(this).apply {
            text = "Instal APK"
            setOnClickListener { pickApk() }
        }
        val btnOwner = Button(this).apply {
            text = "Set Device Owner (HMDM)"
            setOnClickListener { confirmSetDeviceOwner() }
        }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(output) }

        root.addView(status)
        root.addView(btnConnect)
        root.addView(input)
        root.addView(btnRun)
        root.addView(btnInstall)
        root.addView(btnOwner)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val f = IntentFilter(actionPerm)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)

        // Jika dibuka karena kabel dicolok
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) findAndConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        adb?.close()
        io.shutdown()
    }

    private fun findAdbInterface(d: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (n in 0 until d.interfaceCount) {
            val i = d.getInterface(n)
            if (i.interfaceClass == 0xFF && i.interfaceSubclass == 0x42 && i.interfaceProtocol == 1) {
                var ein: UsbEndpoint? = null
                var eout: UsbEndpoint? = null
                for (e in 0 until i.endpointCount) {
                    val ep = i.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) ein = ep else eout = ep
                    }
                }
                if (ein != null && eout != null) return Triple(i, ein, eout)
            }
        }
        return null
    }

    private fun findAndConnect() {
        val dev = usb.deviceList.values.firstOrNull { findAdbInterface(it) != null }
        if (dev == null) {
            setStatus("Tidak ada perangkat ADB. Pakai kabel OTG dan aktifkan USB debugging di HP target.")
            return
        }
        if (usb.hasPermission(dev)) {
            connect(dev)
        } else {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(actionPerm).setPackage(packageName), flags)
            usb.requestPermission(dev, pi)
        }
    }

    private fun connect(dev: UsbDevice) {
        val (iface, ein, eout) = findAdbInterface(dev) ?: return
        val c = usb.openDevice(dev)
        if (c == null) { setStatus("Gagal membuka perangkat"); return }
        setStatus("Menghubungkan… setujui dialog \"Izinkan debugging USB\" di HP target")
        io.execute {
            try {
                adb?.close()
                val crypto = AdbCrypto.loadOrCreate(filesDir)
                val a = UsbAdbConnection(c, iface, ein, eout, crypto)
                a.connect()
                adb = a
                setStatus("Terhubung ✔  (${dev.productName ?: dev.deviceName})")
            } catch (e: Exception) {
                setStatus("Gagal: ${e.message}")
            }
        }
    }

    private fun runCommand() {
        val a = adb
        if (a == null) { setStatus("Belum terhubung"); return }
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty()) return
        append("$ $cmd\n")
        io.execute {
            try {
                a.shell(cmd) { append(it) }
                append("\n")
            } catch (e: Exception) {
                append("[error] ${e.message}\n")
            }
        }
    }

    private fun pickApk() {
        if (adb == null) { setStatus("Belum terhubung"); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, reqPickApk)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqPickApk && resultCode == RESULT_OK) data?.data?.let { installApk(it) }
    }

    private fun installApk(uri: Uri) {
        val a = adb
        if (a == null) { setStatus("Belum terhubung"); return }
        var size = -1L
        var name = "aplikasi.apk"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = c.getString(ni) ?: name
            }
        }
        val remote = "/data/local/tmp/usbadb_install.apk"
        append("Instal $name…\n")
        io.execute {
            try {
                var lastPct = -2
                val ins = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Tidak bisa membuka file")
                ins.use {
                    a.push(remote, 33188, it) { sent ->
                        val pct = if (size > 0) (sent * 100 / size).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            setStatus(if (pct >= 0) "Mengunggah $name… $pct%" else "Mengunggah $name… ${sent / 1024} KB")
                        }
                    }
                }
                setStatus("Memasang $name…")
                val sb = StringBuilder()
                a.shell("pm install -r -t $remote") { sb.append(it); append(it) }
                a.shell("rm $remote") { }
                append("\n")
                setStatus(if (sb.contains("Success")) "Berhasil memasang $name ✔" else "Gagal memasang $name (lihat log)")
            } catch (e: Exception) {
                setStatus("Gagal: ${e.message}")
                append("[error] ${e.message}\n")
            }
        }
    }

    private fun confirmSetDeviceOwner() {
        if (adb == null) { setStatus("Belum terhubung"); return }
        AlertDialog.Builder(this)
            .setTitle("Set Device Owner")
            .setMessage("Menjalankan:\ndpm set-device-owner com.hmdm.launcher/.AdminReceiver\n\n" +
                "Pastikan aplikasi Headwind MDM sudah terpasang di HP target dan tidak ada akun " +
                "(Google dll.) yang login. Lanjutkan?")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Jalankan") { _, _ -> setDeviceOwner() }
            .show()
    }

    private fun setDeviceOwner() {
        val a = adb
        if (a == null) { setStatus("Belum terhubung"); return }
        val cmd = "dpm set-device-owner com.hmdm.launcher/.AdminReceiver"
        append("$ $cmd\n")
        setStatus("Menjalankan set-device-owner…")
        io.execute {
            try {
                val sb = StringBuilder()
                a.shell(cmd) { sb.append(it); append(it) }
                append("\n")
                setStatus(
                    if (sb.contains("Success")) "Device owner berhasil diatur ✔"
                    else "Gagal mengatur device owner (lihat log)"
                )
            } catch (e: Exception) {
                setStatus("Gagal: ${e.message}")
                append("[error] ${e.message}\n")
            }
        }
    }

    private fun append(s: String) = runOnUiThread {
        output.append(s)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }
}
