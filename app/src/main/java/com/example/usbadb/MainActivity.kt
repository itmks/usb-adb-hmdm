package com.example.usbadb

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ACTION_PERM = "com.example.usbadb.USB_PERMISSION"
    private val REQ_APK = 100
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var compEdit: EditText
    private var adb: AdbUsb? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION")
            val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) connect(d)
            else log("Izin USB ditolak")
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        usb = getSystemService(USB_SERVICE) as UsbManager
        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }.also { root.addView(it) }

        btn("1. Hubungkan perangkat USB") { scan() }
        btn("2. Pilih & instal APK") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/vnd.android.package-archive"
            }, REQ_APK)
        }
        compEdit = EditText(this).apply {
            setText("com.hmdm.launcher/.AdminReceiver"); inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(compEdit)
        btn("3. Set Device Owner") { confirmOwner() }

        scroll = ScrollView(this)
        logView = TextView(this).apply { setTextIsSelectable(true); textSize = 12f }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        log("Colok HP target via kabel OTG, aktifkan USB debugging di target.")
    }

    override fun onDestroy() { unregisterReceiver(receiver); adb?.close(); super.onDestroy() }

    private fun log(m: String) = runOnUiThread { logView.append(m + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }

    private fun scan() {
        val d = usb.deviceList.values.firstOrNull { AdbUsb.findAdbInterface(it) != null }
        if (d == null) { log("Tidak ada perangkat ADB. Cek kabel OTG & USB debugging."); return }
        if (usb.hasPermission(d)) { connect(d); return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags)
        usb.requestPermission(d, pi)
    }

    private fun connect(d: UsbDevice) = io.execute {
        try {
            adb?.close()
            adb = AdbUsb(this, usb, d, ::log).also { it.connect() }
        } catch (e: Exception) { log("Gagal konek: ${e.message}"); adb = null }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (req != REQ_APK || res != RESULT_OK) return
        val a = adb ?: return log("Belum terhubung.")
        io.execute {
            try {
                var size = -1L
                contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) size = it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE))
                }
                if (size <= 0) return@execute log("Ukuran APK tidak diketahui")
                log("Menginstal ($size byte)...")
                val r = contentResolver.openInputStream(uri)!!.use { a.exec("exec:cmd package install -S $size", it) }
                log("Hasil: $r")
            } catch (e: Exception) { log("Error: ${e.message}") }
        }
    }

    private fun confirmOwner() {
        val comp = compEdit.text.toString().trim()
        AlertDialog.Builder(this)
            .setTitle("Set Device Owner")
            .setMessage("Menjalankan:\ndpm set-device-owner $comp\n\nHanya berhasil jika target belum punya akun Google/user tambahan.")
            .setPositiveButton("Jalankan") { _, _ ->
                val a = adb ?: return@setPositiveButton log("Belum terhubung.")
                io.execute {
                    try { log("Hasil: " + a.exec("shell:dpm set-device-owner $comp")) }
                    catch (e: Exception) { log("Error: ${e.message}") }
                }
            }.setNegativeButton("Batal", null).show()
    }
}
