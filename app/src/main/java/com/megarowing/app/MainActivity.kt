package com.megarowing.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.Executors

/**
 * MEGA Rowing companion app.
 *
 * This is intentionally a thin shell: all UI, charts, history, pixel art,
 * theming etc. live in the existing web app (loaded from the Pi, same as
 * in a browser). The ONLY thing this native wrapper adds is real USB
 * serial access to the S4 monitor via usb-serial-for-android — something
 * no Android browser can do for CDC-ACM devices.
 *
 * Bridge contract with the web page (see index.html):
 *   JS -> Kotlin:  window.MegaRowingNative.connect() / .disconnect()
 *   Kotlin -> JS:  window.onNativeSerialLine(line)
 *                  window.onNativeSerialStatus("true" | "false")
 */
class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "MegaRowing"
        private const val ACTION_USB_PERMISSION = "com.megarowing.app.USB_PERMISSION"
        private const val BAUD_RATE = 115200

        // Edit this to match wherever the web app is actually hosted on your Pi.
        // Plain http:// is fine here — this is a native WebView, not a browser
        // tab, so the Web Serial "secure context" restriction does not apply.
        private const val APP_URL = "http://192.168.6.100:8088"
    }

    private lateinit var webView: WebView
    private lateinit var usbManager: UsbManager
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val lineBuffer = StringBuilder()

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            synchronized(this) {
                val device: UsbDevice? =
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    openDevice(device)
                } else {
                    Log.w(TAG, "USB permission denied for $device")
                    notifyStatus(false)
                }
            }
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                // Device was just plugged in — if the page already asked to
                // connect, this gives near-instant pickup without the user
                // needing to tap Connect again.
                Log.d(TAG, "USB device attached")
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                Log.d(TAG, "USB device detached")
                closeSerial()
                notifyStatus(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")
            }
        }

        webView.addJavascriptInterface(NativeBridge(), "MegaRowingNative")

        webView.loadUrl(APP_URL)

        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbAttachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, permFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachReceiver, attachFilter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeSerial()
        try {
            unregisterReceiver(usbPermissionReceiver)
            unregisterReceiver(usbAttachReceiver)
        } catch (e: Exception) { /* already unregistered */ }
        executor.shutdownNow()
    }

    // ─────────────────────────────────────────────────────────────────
    // JS <-> Kotlin bridge
    // ─────────────────────────────────────────────────────────────────
    inner class NativeBridge {
        @JavascriptInterface
        fun connect() {
            runOnUiThread { requestUsbConnection() }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread {
                closeSerial()
                notifyStatus(false)
            }
        }

        @JavascriptInterface
        fun writeLine(line: String) {
            // Allows the JS polling loop (S4_POLL_CMDS) to send commands
            // through the same native serial connection.
            executor.execute {
                try {
                    serialPort?.write((line).toByteArray(Charsets.US_ASCII), 1000)
                } catch (e: IOException) {
                    Log.e(TAG, "Write failed", e)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // USB connection flow
    // ─────────────────────────────────────────────────────────────────
    private fun requestUsbConnection() {
        val availableDrivers: List<UsbSerialDriver> =
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            Log.w(TAG, "No USB serial drivers found — is the S4 plugged in?")
            notifyStatus(false)
            return
        }

        // The S4 is the only thing usually plugged in via OTG; take the first
        // recognised CDC/ACM-class driver.
        val driver = availableDrivers[0]
        val device = driver.device

        if (usbManager.hasPermission(device)) {
            openDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
            // openDevice() continues in usbPermissionReceiver once granted
        }
    }

    private fun openDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                .firstOrNull { it.device == device }

        if (driver == null) {
            Log.e(TAG, "No compatible driver for $device")
            notifyStatus(false)
            return
        }

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            Log.e(TAG, "Failed to open device connection")
            notifyStatus(false)
            return
        }

        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(
                BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open/configure serial port", e)
            notifyStatus(false)
            return
        }

        serialPort = port
        ioManager = SerialInputOutputManager(port, this).also { executor.execute(it) }

        // S4 handshake — same as the protocol used everywhere else in this app.
        try {
            port.write("USB\r\n".toByteArray(Charsets.US_ASCII), 1000)
        } catch (e: IOException) {
            Log.e(TAG, "Handshake write failed", e)
        }

        notifyStatus(true)
        Log.d(TAG, "S4 connected via native USB serial")
    }

    private fun closeSerial() {
        ioManager?.let {
            it.listener = null
            it.stop()
        }
        ioManager = null
        try {
            serialPort?.close()
        } catch (e: IOException) {
            // ignore — already closed/disconnected
        }
        serialPort = null
        lineBuffer.clear()
    }

    // ─────────────────────────────────────────────────────────────────
    // SerialInputOutputManager.Listener — fires on a background thread
    // ─────────────────────────────────────────────────────────────────
    override fun onNewData(data: ByteArray) {
        val chunk = String(data, Charsets.US_ASCII)
        synchronized(lineBuffer) {
            lineBuffer.append(chunk)
            var newlineIdx: Int
            while (lineBuffer.indexOf("\n").also { newlineIdx = it } >= 0) {
                val line = lineBuffer.substring(0, newlineIdx).trim()
                lineBuffer.delete(0, newlineIdx + 1)
                if (line.isNotEmpty()) {
                    pushLineToJs(line)
                }
            }
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial IO error", e)
        runOnUiThread {
            closeSerial()
            notifyStatus(false)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Kotlin -> JS push helpers
    // ─────────────────────────────────────────────────────────────────
    private fun pushLineToJs(line: String) {
        val escaped = line.replace("\\", "\\\\").replace("'", "\\'")
        runOnUiThread {
            webView.evaluateJavascript("window.onNativeSerialLine && window.onNativeSerialLine('$escaped');", null)
        }
    }

    private fun notifyStatus(connected: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript(
                "window.onNativeSerialStatus && window.onNativeSerialStatus('$connected');", null
            )
        }
    }
}
