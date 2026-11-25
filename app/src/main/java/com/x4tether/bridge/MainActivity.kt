package com.x4tether.bridge

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private val testExecutor = Executors.newSingleThreadExecutor()

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "X4 BLE Bridge v2.0"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "BLE GATT HTTP/HTTPS Pseudo-Tethering + Hotspot Control"
            textSize = 13f
            setPadding(0, 4, 0, pad / 2)
        }
        root.addView(subtitle)

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 4, 0, pad / 2)
        }
        root.addView(statusView)

        val btnStart = Button(this).apply {
            text = "Start BLE Proxy Service (Wait for X4 Pro)"
            setOnClickListener {
                if (ensurePermissions()) {
                    startForegroundService(Intent(this@MainActivity, TetherBleService::class.java))
                    statusView.postDelayed({ refreshUi() }, 300)
                }
            }
        }
        root.addView(btnStart)

        val btnStop = Button(this).apply {
            text = "Stop BLE Service"
            setOnClickListener {
                TetherBleService.setDesiredEnabled(this@MainActivity, false)
                stopService(Intent(this@MainActivity, TetherBleService::class.java))
                statusView.postDelayed({ refreshUi() }, 200)
            }
        }
        root.addView(btnStop)

        val btnTestProxy = Button(this).apply {
            text = "Self-Test Mobile HTTP + Zlib Engine"
            setOnClickListener {
                TetherBleService.appendLog(this@MainActivity, "Self-test: fetching Open-Meteo...")
                testExecutor.execute {
                    val sampleReq = """{"u":"https://api.open-meteo.com/v1/forecast?latitude=35.44&longitude=139.64&current=temperature_2m","m":"GET","z":1}"""
                    val res = HttpProxyEngine.executeJsonRequest(sampleReq)
                    runOnUiThread {
                        if (res.isError) {
                            TetherBleService.appendLog(
                                this@MainActivity,
                                "Self-test ERR: ${res.payload.toString(Charsets.UTF_8)}"
                            )
                        } else {
                            TetherBleService.appendLog(
                                this@MainActivity,
                                "Self-test OK: HTTP ${res.httpStatus} (${res.rawLength}B -> ${res.payload.size}B, zlib=${res.isZlib})"
                            )
                        }
                        refreshUi()
                    }
                }
            }
        }
        root.addView(btnTestProxy)

        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad / 4, 0, pad / 4)
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val btnTestOn = Button(this).apply {
            text = "Test Hotspot ON"
            layoutParams = lp
            setOnClickListener {
                TetherBleService.fireMacroDroidIntent(this@MainActivity, BleConstants.ACTION_TETHER_ON)
                refreshUi()
            }
        }
        val btnTestOff = Button(this).apply {
            text = "Test Hotspot OFF"
            layoutParams = lp
            setOnClickListener {
                TetherBleService.fireMacroDroidIntent(this@MainActivity, BleConstants.ACTION_TETHER_OFF)
                refreshUi()
            }
        }
        testRow.addView(btnTestOn)
        testRow.addView(btnTestOff)
        root.addView(testRow)

        val btnBattery = Button(this).apply {
            text = "Open App Battery Settings (Set Unrestricted)"
            setOnClickListener {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(i)
            }
        }
        root.addView(btnBattery)

        val logHeader = TextView(this).apply {
            text = "\nBLE & Proxy Event Log:"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(logHeader)

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(logView)
        }
        root.addView(scroll)

        setContentView(root)
        if (ensurePermissions() && TetherBleService.isDesiredEnabled(this) && !TetherBleService.isRunning) {
            startForegroundService(Intent(this, TetherBleService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(BleConstants.ACTION_LOG_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        refreshUi()
    }

    override fun onPause() {
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    private fun refreshUi() {
        val state = if (TetherBleService.isRunning) TetherBleService.statusText else "Stopped"
        val reqs = TetherBleService.proxyRequestsCount.get()
        val rawKb = TetherBleService.rawBytesTotal.get() / 1024.0
        val bleKb = TetherBleService.bleBytesTotal.get() / 1024.0
        statusView.text = String.format(
            "Status: %s\nProxied: %d reqs  (HTTP %.1f KB -> BLE %.1f KB)",
            state, reqs, rawKb, bleKb
        )
        logView.text = TetherBleService.logs.reversed().joinToString("\n")
    }

    private fun ensurePermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 100)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshUi()
    }
}
