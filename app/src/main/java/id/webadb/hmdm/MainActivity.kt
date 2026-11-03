package id.webadb.hmdm

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var adb: AdbConnection? = null

    private class Apk(val uri: Uri, val name: String, val size: Long)
    @Volatile private var picked: List<Apk> = emptyList()

    private val ACTION_PERM = "id.webadb.hmdm.USB_PERMISSION"
    private val REQ_PICK = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        adb?.close()
        super.onDestroy()
    }

    // ---------- util JS ----------
    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }
    private fun logJs(msg: String, type: String = "info") =
        js("window.__log(${JSONObject.quote(msg)}, ${JSONObject.quote(type)})")

    private fun resolve(id: String, ok: Boolean, data: String = "", err: String = "") {
        val o = JSONObject().put("ok", ok).put("data", data).put("error", err)
        js("window.__resolve(${JSONObject.quote(id)}, $o)")
    }

    private fun task(id: String, block: () -> String) = exec.execute {
        try { resolve(id, true, block()) }
        catch (t: Throwable) { resolve(id, false, err = t.message ?: t.toString()) }
    }

    private fun connected(): AdbConnection = adb ?: throw IOException("Belum ada perangkat terhubung")

    // ---------- izin USB ----------
    private fun requestPermission(um: UsbManager, dev: UsbDevice) {
        val latch = CountDownLatch(1)
        var granted = false
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(rcv, f)
        try {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags)
            um.requestPermission(dev, pi)
            if (!latch.await(60, TimeUnit.SECONDS) || !granted) throw IOException("Izin akses USB ditolak")
        } finally {
            unregisterReceiver(rcv)
        }
    }

    // ---------- pilih APK ----------
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { for (i in 0 until it.itemCount) uris.add(it.getItemAt(i).uri) }
        data.data?.let { if (uris.isEmpty()) uris.add(it) }
        val list = ArrayList<Apk>()
        for (u in uris) {
            var name = "file.apk"; var size = -1L
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            if (name.endsWith(".apk", true) && size > 0) list.add(Apk(u, name, size))
        }
        picked = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("size", it.size)) }
        js("window.__apksPicked($arr)")
    }

    // ---------- jembatan JS ----------
    inner class Bridge {
        @JavascriptInterface
        fun connect(id: String) = task(id) {
            adb?.close(); adb = null
            val um = getSystemService(Context.USB_SERVICE) as UsbManager
            val dev = um.deviceList.values.firstOrNull { AdbConnection.findAdbInterface(it) != null }
                ?: throw IOException("Tidak ada perangkat ADB terdeteksi. Sambungkan HP target lewat kabel OTG dan aktifkan USB Debugging.")
            if (!um.hasPermission(dev)) requestPermission(um, dev)
            val c = AdbConnection(this@MainActivity, um, dev) { logJs(it) }
            c.connect()
            adb = c
            try { dev.serialNumber ?: "unknown" } catch (_: Exception) { "unknown" }
        }

        @JavascriptInterface
        fun disconnect(id: String) = task(id) { adb?.close(); adb = null; "" }

        @JavascriptInterface
        fun shell(id: String, cmd: String) = task(id) { connected().shell(cmd) }

        @JavascriptInterface
        fun tcpip(id: String, port: Int) = task(id) { connected().tcpip(port) }

        @JavascriptInterface
        fun pickApks() = runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            startActivityForResult(i, REQ_PICK)
        }

        @JavascriptInterface
        fun install(id: String) = task(id) {
            val c = connected()
            val files = picked
            if (files.isEmpty()) throw IOException("Silakan pilih minimal satu file APK.")
            files.forEachIndexed { i, a ->
                logJs("[${i + 1}/${files.size}] Memulai pemasangan ${a.name} (${"%.2f".format(a.size / 1048576.0)} MB)...")
                js("window.__progress(${i + 1}, ${files.size}, ${JSONObject.quote(a.name)}, 0)")
                val stream = contentResolver.openInputStream(a.uri) ?: throw IOException("Tidak bisa membuka ${a.name}")
                val out = c.installApk(stream, a.size) { p ->
                    js("window.__progress(${i + 1}, ${files.size}, ${JSONObject.quote(a.name)}, $p)")
                }
                logJs(out)
                if (!Regex("Success", RegexOption.IGNORE_CASE).containsMatchIn(out))
                    throw IOException("Gagal menginstal ${a.name}. Detail: $out")
                logJs("Berhasil memasang ${a.name}!")
            }
            "ok"
        }
    }
}
