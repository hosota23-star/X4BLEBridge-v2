package com.x4tether.bridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TetherBleService : Service() {

    companion object {
        private const val CHANNEL_ID = "x4_tether_bridge_channel"
        private const val NOTIF_ID = 1042
        private const val PREFS = "x4_tether_prefs"
        private const val KEY_ENABLED = "service_enabled"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var statusText: String = "Stopped"
            private set

        val proxyRequestsCount = AtomicInteger(0)
        val rawBytesTotal = AtomicLong(0)
        val bleBytesTotal = AtomicLong(0)

        val logs = CopyOnWriteArrayList<String>()

        fun setDesiredEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun isDesiredEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }

        fun appendLog(context: Context, msg: String) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logs.add("$ts  $msg")
            while (logs.size > 40) {
                logs.removeAt(0)
            }
            context.sendBroadcast(
                Intent(BleConstants.ACTION_LOG_UPDATED).setPackage(context.packageName)
            )
        }

        fun fireMacroDroidIntent(context: Context, action: String) {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
            appendLog(context, "Broadcast -> $action")
        }
    }

    private data class QueuedNotify(
        val chr: BluetoothGattCharacteristic,
        val bytes: ByteArray
    )

    private class DeviceSession(val device: BluetoothDevice) {
        @Volatile
        var mtu: Int = 23

        // Request assembly state (X4 Pro -> Android)
        var rxReqId: Int = 0
        var rxExpectedSeq: Int = 0
        val rxBuffer = ByteArrayOutputStream()

        // Active HTTP call & response streaming state (Android -> X4 Pro)
        var activeReqId: Int = 0
        var cancelFlag: AtomicBoolean? = null
        var heartbeatRunnable: Runnable? = null
        var respChunks: List<ByteArray> = emptyList()

        // Serialized notification queue (main thread only)
        val notifyQueue = ArrayDeque<QueuedNotify>()
        var notifyInFlight: Boolean = false
        var notifyWatchdog: Runnable? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpExecutor = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, DeviceSession>()

    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var chrRespRef: BluetoothGattCharacteristic? = null
    private var isAdvertising = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            statusText = "Waiting for X4 Pro (HTTP Proxy + Tether)"
            appendLog(this@TetherBleService, "BLE Advertise started (Balanced)")
            updateNotification(statusText)
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            statusText = "Advertise failed (code=$errorCode)"
            appendLog(this@TetherBleService, "BLE Advertise failed: $errorCode")
            updateNotification(statusText)
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address ?: "unknown"
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                sessions.computeIfAbsent(addr) { DeviceSession(device) }
                appendLog(this@TetherBleService, "Connected: $addr")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val removed = sessions.remove(addr)
                if (removed != null) {
                    mainHandler.post { cleanupSessionTimers(removed) }
                }
                appendLog(this@TetherBleService, "Disconnected: $addr")
                mainHandler.postDelayed({ ensureAdvertising() }, 350)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val addr = device.address ?: return
            val s = sessions.computeIfAbsent(addr) { DeviceSession(device) }
            s.mtu = mtu.coerceIn(23, 517)
            appendLog(this@TetherBleService, "MTU negotiated: ${s.mtu} ($addr)")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val addr = device.address ?: return
            val s = sessions[addr] ?: return
            mainHandler.post {
                s.notifyWatchdog?.let { mainHandler.removeCallbacks(it) }
                s.notifyWatchdog = null
                s.notifyInFlight = false
                pumpNotifyQueue(s)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val payload = "X4BP/2.0".toByteArray(Charsets.UTF_8)
            val slice = if (offset < payload.size) payload.copyOfRange(offset, payload.size) else ByteArray(0)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (descriptor.uuid != BleConstants.CCCD_UUID) return
            val enabled = value != null && value.isNotEmpty() && value[0].toInt() != 0
            if (!enabled) return

            val addr = device.address ?: "unknown"
            val session = sessions.computeIfAbsent(addr) { DeviceSession(device) }
            val chr = descriptor.characteristic

            if (chr.uuid == BleConstants.CHR_RESP_UUID) {
                mainHandler.post {
                    resetProxySession(session)
                    val mtu = session.mtu.coerceIn(23, 517)
                    val readyPkt = byteArrayOf(
                        BleConstants.PKT_READY,
                        ((mtu shr 8) and 0xFF).toByte(),
                        (mtu and 0xFF).toByte(),
                        0x01
                    )
                    mainHandler.postDelayed({
                        enqueueNotify(session, chr, readyPkt)
                    }, 60)
                }
                return
            }

            // Legacy TetherCtl subscribe-triggered commands
            val reply: String = when (chr.uuid) {
                BleConstants.CHR_ON_UUID -> {
                    appendLog(this@TetherBleService, "CMD: TETHER ON")
                    fireMacroDroidIntent(this@TetherBleService, BleConstants.ACTION_TETHER_ON)
                    "OK:ON"
                }
                BleConstants.CHR_OFF_UUID -> {
                    appendLog(this@TetherBleService, "CMD: TETHER OFF")
                    fireMacroDroidIntent(this@TetherBleService, BleConstants.ACTION_TETHER_OFF)
                    "OK:OFF"
                }
                BleConstants.CHR_STATUS_UUID -> {
                    val st = queryHotspotState()
                    appendLog(this@TetherBleService, "CMD: STATUS -> $st")
                    st
                }
                else -> "ERR:UNKNOWN_CHR"
            }

            mainHandler.postDelayed({
                enqueueNotify(session, chr, reply.toByteArray(Charsets.UTF_8))
            }, 100)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (characteristic.uuid != BleConstants.CHR_REQ_UUID || value == null || value.isEmpty()) {
                return
            }
            val packet = value.copyOf()
            val addr = device.address ?: "unknown"
            val session = sessions.computeIfAbsent(addr) { DeviceSession(device) }
            mainHandler.post {
                handleProxyRequestPacket(session, packet)
            }
        }
    }

    private fun handleProxyRequestPacket(session: DeviceSession, pkt: ByteArray) {
        val respChr = chrRespRef ?: return
        val b0 = pkt[0].toInt() and 0xFF

        // 0x81: CMD_RX_ACK [0x81, req_id, next_hi, next_lo]
        if (b0 == BleConstants.CMD_RX_ACK) {
            if (pkt.size < 4) return
            val reqId = pkt[1].toInt() and 0xFF
            val nextIdx = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
            if (reqId == session.activeReqId && session.respChunks.isNotEmpty()) {
                sendChunkWindow(session, respChr, reqId, nextIdx)
            }
            return
        }

        // 0x82: CMD_CANCEL [0x82, req_id]
        if (b0 == BleConstants.CMD_CANCEL) {
            if (pkt.size >= 2) {
                val reqId = pkt[1].toInt() and 0xFF
                if (reqId == session.activeReqId) {
                    resetProxySession(session)
                    appendLog(this, "Req #$reqId cancelled by X4 Pro")
                }
            }
            return
        }

        // 1..127: Request payload chunk [req_id, flags, seq, ...json_slice]
        if (b0 in 1..127 && pkt.size >= 3) {
            val reqId = b0
            val flags = pkt[1].toInt() and 0xFF
            val seq = pkt[2].toInt() and 0xFF

            if ((flags and BleConstants.REQ_FLAG_FIRST) != 0) {
                if (reqId == session.activeReqId) {
                    // Duplicate single-chunk retry while HTTP is already active/done for this reqId
                    enqueueNotify(session, respChr, byteArrayOf(BleConstants.PKT_HEARTBEAT, reqId.toByte()))
                    return
                }
                resetProxySession(session)
                session.rxReqId = reqId
                session.rxExpectedSeq = 0
                session.rxBuffer.reset()
            }

            if (reqId != session.rxReqId) {
                if (reqId == session.activeReqId && (flags and BleConstants.REQ_FLAG_LAST) != 0) {
                    enqueueNotify(session, respChr, byteArrayOf(BleConstants.PKT_HEARTBEAT, reqId.toByte()))
                }
                return
            }

            var acceptedChunk = false
            if (seq == session.rxExpectedSeq) {
                if (pkt.size > 3) {
                    session.rxBuffer.write(pkt, 3, pkt.size - 3)
                }
                session.rxExpectedSeq = (session.rxExpectedSeq + 1) and 0xFF
                acceptedChunk = true
            }

            if ((flags and BleConstants.REQ_FLAG_LAST) == 0) {
                val ackPkt = byteArrayOf(BleConstants.PKT_REQ_ACK, reqId.toByte(), seq.toByte())
                enqueueNotify(session, respChr, ackPkt)
            } else if (acceptedChunk) {
                val reqJson = session.rxBuffer.toByteArray().toString(Charsets.UTF_8)
                session.rxBuffer.reset()
                session.rxReqId = 0
                launchHttpProxy(session, respChr, reqId, reqJson)
            } else if (reqId == session.activeReqId) {
                enqueueNotify(session, respChr, byteArrayOf(BleConstants.PKT_HEARTBEAT, reqId.toByte()))
            }
        }
    }

    private fun launchHttpProxy(
        session: DeviceSession,
        respChr: BluetoothGattCharacteristic,
        reqId: Int,
        reqJson: String
    ) {
        session.activeReqId = reqId
        val cancelled = AtomicBoolean(false)
        session.cancelFlag = cancelled

        // Immediate heartbeat + 2.5s periodic keepalive while mobile HTTP runs
        enqueueNotify(session, respChr, byteArrayOf(BleConstants.PKT_HEARTBEAT, reqId.toByte()))
        val hb = object : Runnable {
            override fun run() {
                if (!cancelled.get() && session.activeReqId == reqId && isRunning) {
                    enqueueNotify(session, respChr, byteArrayOf(BleConstants.PKT_HEARTBEAT, reqId.toByte()))
                    mainHandler.postDelayed(this, 2500)
                }
            }
        }
        session.heartbeatRunnable = hb
        mainHandler.postDelayed(hb, 2500)

        httpExecutor.execute {
            val t0 = System.currentTimeMillis()
            val res = HttpProxyEngine.executeJsonRequest(reqJson) { cancelled.get() }
            val elapsedMs = System.currentTimeMillis() - t0

            mainHandler.post {
                if (cancelled.get() || session.activeReqId != reqId || !isRunning) return@post
                session.heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
                session.heartbeatRunnable = null

                val chunkMax = (session.mtu - 7).coerceIn(16, 236)
                val chunks = ArrayList<ByteArray>()
                var pos = 0
                while (pos < res.payload.size) {
                    val end = (pos + chunkMax).coerceAtMost(res.payload.size)
                    chunks.add(res.payload.copyOfRange(pos, end))
                    pos = end
                }
                session.respChunks = chunks

                var rflags = 0
                if (res.isZlib) rflags = rflags or BleConstants.RESP_FLAG_ZLIB
                if (res.isError) rflags = rflags or BleConstants.RESP_FLAG_ERR

                val totalLen = res.payload.size
                val totalChunks = chunks.size
                val st = res.httpStatus.coerceIn(0, 65535)

                val meta = byteArrayOf(
                    BleConstants.PKT_RESP_META,
                    reqId.toByte(),
                    rflags.toByte(),
                    ((st shr 8) and 0xFF).toByte(),
                    (st and 0xFF).toByte(),
                    ((totalLen ushr 24) and 0xFF).toByte(),
                    ((totalLen ushr 16) and 0xFF).toByte(),
                    ((totalLen ushr 8) and 0xFF).toByte(),
                    (totalLen and 0xFF).toByte(),
                    ((totalChunks shr 8) and 0xFF).toByte(),
                    (totalChunks and 0xFF).toByte()
                )
                enqueueNotify(session, respChr, meta)
                if (totalChunks > 0) {
                    sendChunkWindow(session, respChr, reqId, 0)
                }

                val count = proxyRequestsCount.incrementAndGet()
                rawBytesTotal.addAndGet(res.rawLength.toLong())
                bleBytesTotal.addAndGet(totalLen.toLong())

                val zNote = if (res.isZlib) "->${totalLen}B zlib" else "${totalLen}B"
                if (res.isError) {
                    appendLog(this, "ERR #${reqId} ${res.hostSummary}: ${res.payload.toString(Charsets.UTF_8)}")
                } else {
                    appendLog(
                        this,
                        "HTTP ${res.httpStatus} ${res.hostSummary} (${res.rawLength}B$zNote, ${totalChunks}pkts, ${elapsedMs}ms)"
                    )
                }
                updateNotification("Active (Proxied $count reqs)")
            }
        }
    }

    private fun sendChunkWindow(
        session: DeviceSession,
        respChr: BluetoothGattCharacteristic,
        reqId: Int,
        startIdx: Int
    ) {
        val chunks = session.respChunks
        if (startIdx < 0 || startIdx >= chunks.size) return
        val endExclusive = (startIdx + BleConstants.WINDOW_SIZE).coerceAtMost(chunks.size)
        for (idx in startIdx until endExclusive) {
            val data = chunks[idx]
            val pkt = ByteArray(4 + data.size)
            pkt[0] = BleConstants.PKT_RESP_DATA
            pkt[1] = reqId.toByte()
            pkt[2] = ((idx shr 8) and 0xFF).toByte()
            pkt[3] = (idx and 0xFF).toByte()
            System.arraycopy(data, 0, pkt, 4, data.size)
            enqueueNotify(session, respChr, pkt)
        }
    }

    private fun resetProxySession(session: DeviceSession) {
        session.cancelFlag?.set(true)
        session.cancelFlag = null
        session.heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        session.heartbeatRunnable = null
        session.activeReqId = 0
        session.respChunks = emptyList()
        session.rxBuffer.reset()
        session.notifyQueue.clear()
    }

    private fun cleanupSessionTimers(session: DeviceSession) {
        resetProxySession(session)
        session.notifyWatchdog?.let { mainHandler.removeCallbacks(it) }
        session.notifyWatchdog = null
        session.notifyInFlight = false
    }

    private fun enqueueNotify(session: DeviceSession, chr: BluetoothGattCharacteristic, bytes: ByteArray) {
        session.notifyQueue.addLast(QueuedNotify(chr, bytes))
        pumpNotifyQueue(session)
    }

    @SuppressLint("MissingPermission")
    private fun pumpNotifyQueue(session: DeviceSession) {
        if (session.notifyInFlight) return
        val server = gattServer ?: run {
            session.notifyQueue.clear()
            return
        }
        val next = session.notifyQueue.pollFirst() ?: return
        session.notifyInFlight = true

        val watchdog = Runnable {
            session.notifyWatchdog = null
            if (session.notifyInFlight) {
                session.notifyInFlight = false
                pumpNotifyQueue(session)
            }
        }
        session.notifyWatchdog = watchdog
        mainHandler.postDelayed(watchdog, 350)

        try {
            val dispatched: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val code = server.notifyCharacteristicChanged(session.device, next.chr, false, next.bytes)
                code == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                next.chr.value = next.bytes
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(session.device, next.chr, false)
            }
            if (!dispatched) {
                mainHandler.removeCallbacks(watchdog)
                session.notifyWatchdog = null
                session.notifyInFlight = false
                mainHandler.postDelayed({ pumpNotifyQueue(session) }, 30)
            }
        } catch (e: Exception) {
            mainHandler.removeCallbacks(watchdog)
            session.notifyWatchdog = null
            session.notifyInFlight = false
            appendLog(this, "Notify err: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setDesiredEnabled(this, true)
        isRunning = true
        statusText = "Starting BLE GATT Server..."

        val notif = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        startBleServerAndAdvertising()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        statusText = "Stopped"
        stopBle()
        appendLog(this, "Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startBleServerAndAdvertising() {
        stopBle()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            statusText = "Bluetooth is OFF"
            appendLog(this, "Bluetooth adapter unavailable or OFF")
            updateNotification(statusText)
            return
        }

        try {
            gattServer = bluetoothManager?.openGattServer(this, gattCallback)
            val service = BluetoothGattService(
                BleConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // 1. Legacy TetherCtl characteristics
            service.addCharacteristic(makeNotifyCharacteristic(BleConstants.CHR_ON_UUID))
            service.addCharacteristic(makeNotifyCharacteristic(BleConstants.CHR_OFF_UUID))
            service.addCharacteristic(makeNotifyCharacteristic(BleConstants.CHR_STATUS_UUID))

            // 2. X4BP v1 HTTP Proxy Request & Response characteristics
            val reqChr = BluetoothGattCharacteristic(
                BleConstants.CHR_REQ_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val respChr = makeNotifyCharacteristic(BleConstants.CHR_RESP_UUID, readable = true)
            chrRespRef = respChr

            service.addCharacteristic(reqChr)
            service.addCharacteristic(respChr)

            val added = gattServer?.addService(service) ?: false
            appendLog(this, "GATT Proxy+Tether Service added: $added")

            advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                statusText = "BLE Peripheral mode unsupported"
                appendLog(this, statusText)
                updateNotification(statusText)
                return
            }
            startAdvertising()
        } catch (e: SecurityException) {
            statusText = "Missing Bluetooth permission"
            appendLog(this, "SecurityException: ${e.message}")
            updateNotification(statusText)
        } catch (e: Exception) {
            statusText = "Start error: ${e.message}"
            appendLog(this, statusText)
            updateNotification(statusText)
        }
    }

    private fun makeNotifyCharacteristic(uuid: UUID, readable: Boolean = false): BluetoothGattCharacteristic {
        val props = if (readable) {
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ
        } else {
            BluetoothGattCharacteristic.PROPERTY_NOTIFY
        }
        val chr = BluetoothGattCharacteristic(
            uuid,
            props,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        chr.addDescriptor(cccd)
        return chr
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val adv = advertiser ?: return
        // BALANCED (~250ms interval): fast ~0.3s discovery from X4 Pro with negligible battery use
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        val scanResp = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        adv.startAdvertising(settings, data, scanResp, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun ensureAdvertising() {
        if (!isRunning) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            startAdvertising()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        for ((_, s) in sessions) {
            cleanupSessionTimers(s)
        }
        sessions.clear()
        try {
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (_: Exception) {
        }
        isAdvertising = false
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        chrRespRef = null
    }

    private fun queryHotspotState(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getDeclaredMethod("getWifiApState")
            method.isAccessible = true
            val state = method.invoke(wm) as Int
            when (state) {
                13, 12 -> "STATE:ON"
                11, 10 -> "STATE:OFF"
                else -> "STATE:UNKNOWN"
            }
        } catch (_: Exception) {
            "STATE:UNKNOWN"
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "X4 BLE Proxy & Tether Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Relays HTTP/HTTPS over BLE GATT for XTEINK X4 Pro without Wi-Fi Hotspot"
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("X4 BLE Bridge v2.0")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
