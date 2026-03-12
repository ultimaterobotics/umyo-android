package com.example.uf1bridgedemo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.util.isNotEmpty
import com.example.uf1bridgedemo.ui.theme.UF1BridgeDemoTheme
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

@SuppressLint("MissingPermission") // keep lint noise out while we iterate on Android 11
class MainActivity : ComponentActivity() {

    enum class RunMode { NONE, SYNTH, BLE_SCAN, GATT }

    @Volatile private var runMode: RunMode = RunMode.NONE
    private var worker: Thread? = null
    private var udpSocket: DatagramSocket? = null

    private var bleScanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null

    private var gatt: BluetoothGatt? = null
    private var gattChar: BluetoothGattCharacteristic? = null

    @Volatile private var gattServicesStarted = false
    @Volatile private var gattGotFirstData = false

    private var gattMac: String = "00:00:00:00:00:00"
    private var gattDevId: UInt = 0u
    private var gattSeq: UInt = 0u
    private var lastScanRssi: Int = -128

    // connect-once-per-start for debugging
    private var gattTriedConnect = false

    private var sendQueue: ArrayBlockingQueue<ByteArray>? = null

    private var gattCount = 0
    private var gattWindowStartMs = SystemClock.elapsedRealtime()

    private val umyoServiceUuid = UUID.fromString("93375900-F229-8B49-B397-44B5899B8601")
    private val umyoTelemCharUuid = UUID.fromString("FC7A850D-C1A5-F61F-0DA7-9995621FBD01")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // UF1 flags
    private val flagTimeSrcPresent: UShort = 0x0001u.toUShort()
    private val flagTimeUsIsRx: UShort = 0x0002u.toUShort()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter

        setContent {
            UF1BridgeDemoTheme {
                Surface(color = MaterialTheme.colorScheme.background) {

                    var status by remember { mutableStateOf("Ready") }
                    val updateStatus: (String) -> Unit = { msg ->
                        this@MainActivity.runOnUiThread { status = msg }
                    }

                    var host by rememberSaveable { mutableStateOf("192.168.88.210") }
                    var portStr by rememberSaveable { mutableStateOf("26750") }
                    val portVal = portStr.toIntOrNull()?.coerceIn(1, 65535) ?: 26750

                    val permLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) updateStatus("Location permission granted. You can scan now.")
                        else updateStatus("Location permission denied. BLE scan won’t work on Android 11.")
                    }

                    BridgeScreen(
                        status = status,
                        host = host,
                        portStr = portStr,
                        onHostChange = { host = it },
                        onPortChange = { portStr = it.filter(Char::isDigit).take(5) },

                        onStartSynthetic = {
                            stopAll()
                            updateStatus("Starting synthetic…")
                            startSynthetic(host, portVal, updateStatus)
                        },

                        onStartBleScan = {
                            if (!hasFineLocation()) {
                                updateStatus("Requesting location permission (required for BLE scan on Android 11)…")
                                permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                return@BridgeScreen
                            }
                            if (btAdapter == null || !btAdapter.isEnabled) {
                                updateStatus("Bluetooth is OFF. Turn Bluetooth ON and try again.")
                                return@BridgeScreen
                            }

                            stopAll()
                            updateStatus("Starting BLE scan…")
                            startBleScan(btAdapter, host, portVal, updateStatus)
                        },

                        onStartGattRaw = {
                            if (!hasFineLocation()) {
                                updateStatus("Requesting location permission (required for BLE scan on Android 11)…")
                                permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                return@BridgeScreen
                            }
                            if (btAdapter == null || !btAdapter.isEnabled) {
                                updateStatus("Bluetooth is OFF. Turn Bluetooth ON and try again.")
                                return@BridgeScreen
                            }

                            stopAll()
                            updateStatus("Starting GATT raw…")
                            startGattRaw(btAdapter, host, portVal, updateStatus)
                        },

                        onStop = {
                            stopAll()
                            updateStatus("Stopped")
                        },

                        canEditDest = (runMode == RunMode.NONE)
                    )
                }
            }
        }
    }

    private fun hasFineLocation(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun stopAll() {
        // stop scanning first
        stopBleScan()

        // stop worker
        runMode = RunMode.NONE
        worker?.join(400)
        worker = null

        // close UDP
        udpSocket?.close()
        udpSocket = null

        // disconnect gatt
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        gattChar = null

        // queues
        sendQueue?.clear()
        sendQueue = null
    }

    private fun stopBleScan() {
        try {
            val sc = scanCb
            if (sc != null) bleScanner?.stopScan(sc)
        } catch (_: Exception) {}
        scanCb = null
        bleScanner = null
    }

    // -------------------------
    // Synthetic UF1 sender
    // -------------------------
    private fun startSynthetic(hostIp: String, port: Int, updateStatus: (String) -> Unit) {
        runMode = RunMode.SYNTH

        val addr = InetAddress.getByName(hostIp)
        udpSocket = DatagramSocket()

        val deviceId: UInt = 0x12345678u
        val sampleRateHz = 1150
        val samplesPerFrame = 8
        val framesPerSec = sampleRateHz.toDouble() / samplesPerFrame.toDouble()
        val dtNs = (1_000_000_000.0 / framesPerSec).toLong()

        worker = thread(start = true) {
            var seq: UInt = 0u
            var tSrcSample: UInt = 0u

            val ampl = 800.0
            val freqHz = 2.0

            var sentThisWindow = 0
            var windowStartMs = SystemClock.elapsedRealtime()
            var nextT = SystemClock.elapsedRealtimeNanos()

            while (runMode == RunMode.SYNTH) {
                val nowNs = SystemClock.elapsedRealtimeNanos()
                if (nowNs < nextT) {
                    val sleepMs = ((nextT - nowNs) / 1_000_000L).coerceAtMost(5)
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                    continue
                }
                nextT += dtNs

                val samples = ShortArray(samplesPerFrame)
                for (i in 0 until samplesPerFrame) {
                    val t = (tSrcSample.toLong() + i).toDouble() / sampleRateHz.toDouble()
                    val v = ampl * sin(2.0 * PI * freqHz * t)
                    samples[i] = v.toInt().toShort()
                }

                val tUs = (SystemClock.elapsedRealtimeNanos() / 1000L) and ((1L shl 48) - 1)

                val frame = uf1EncodeFrameStatusEmg(
                    deviceId = deviceId,
                    seq = seq,
                    tUs = tUs,
                    tSrcSample = tSrcSample,
                    sampleRateHz = sampleRateHz,
                    batteryPct = 90,
                    rssiDbm = -128,
                    mode = 0,
                    statusFlags = 0,
                    samples = samples
                )

                try {
                    udpSocket?.send(DatagramPacket(frame, frame.size, addr, port))
                } catch (_: Exception) {}

                seq++
                tSrcSample += samplesPerFrame.toUInt()

                sentThisWindow++
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - windowStartMs >= 1000) {
                    val fps = sentThisWindow / ((nowMs - windowStartMs) / 1000.0)
                    sentThisWindow = 0
                    windowStartMs = nowMs
                    updateStatus("Synthetic → $hostIp:$port  fps≈${"%.1f".format(fps)}")
                }
            }
        }
    }

    // -------------------------
    // BLE scan → UF1 bridge (ADV)
    // -------------------------
    private fun startBleScan(
        btAdapter: BluetoothAdapter,
        hostIp: String,
        port: Int,
        updateStatus: (String) -> Unit
    ) {
        runMode = RunMode.BLE_SCAN

        val addr = InetAddress.getByName(hostIp)
        udpSocket = DatagramSocket()
        sendQueue = ArrayBlockingQueue(2048)
        val q = sendQueue ?: return

        worker = thread(start = true) {
            val sock = udpSocket ?: return@thread
            while (runMode == RunMode.BLE_SCAN) {
                val frame = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try { sock.send(DatagramPacket(frame, frame.size, addr, port)) } catch (_: Exception) {}
            }
        }

        bleScanner = btAdapter.bluetoothLeScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf<ScanFilter>()

        var seq: UInt = 0u
        var seenThisWindow = 0
        var windowStartMs = SystemClock.elapsedRealtime()

        scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (runMode != RunMode.BLE_SCAN) return

                val sr = result.scanRecord ?: return
                val raw = sr.bytes ?: return
                val rssi = result.rssi.coerceIn(-128, 127)

                val mac = result.device?.address ?: "00:00:00:00:00:00"
                val devId = crc32U32(mac.toByteArray(Charsets.UTF_8))

                val tUs = (SystemClock.elapsedRealtimeNanos() / 1000L) and ((1L shl 48) - 1)

                val mfg = sr.manufacturerSpecificData
                val manuId = if (mfg != null && mfg.size() > 0) mfg.keyAt(0) else 0xFFFF

                // BLE_ADV_RAW: manufacturer_id(u16) + rssi(i8) + raw scanRecord bytes
                val advVal = ByteBuffer.allocate(2 + 1 + raw.size)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply {
                        putShort(manuId.toShort())
                        put(rssi.toByte())
                        put(raw)
                    }
                    .array()

                val statusVal = ByteBuffer.allocate(10)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply {
                        putInt(0)
                        putShort(0)
                        put(255.toByte())
                        put(rssi.toByte())
                        put(0)
                        put(0)
                    }
                    .array()

                val frame = uf1EncodeFrameStatusPlusBlock(
                    deviceId = devId,
                    seq = seq,
                    tUs = tUs,
                    statusVal = statusVal,
                    blockType = 0xF0,
                    blockVal = advVal
                )

                seq++
                q.offer(frame)

                seenThisWindow++
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - windowStartMs >= 1000) {
                    val fps = seenThisWindow / ((nowMs - windowStartMs) / 1000.0)
                    seenThisWindow = 0
                    windowStartMs = nowMs
                    updateStatus("BLE scan → $hostIp:$port  adv≈${"%.1f".format(fps)}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                updateStatus("BLE scan failed: $errorCode (Location must be ON on Android 11)")
            }
        }

        try {
            bleScanner?.startScan(filters, settings, scanCb)
            updateStatus("BLE scan started. Ensure Location toggle is ON.")
        } catch (e: Exception) {
            updateStatus("BLE scan exception: ${e.message}")
        }
    }

    // -------------------------
    // GATT connect + notify → UF1
    // -------------------------
    private fun startGattRaw(
        btAdapter: BluetoothAdapter,
        hostIp: String,
        port: Int,
        updateStatus: (String) -> Unit
    ) {
        runMode = RunMode.GATT
        gattTriedConnect = false // IMPORTANT: reset each run

        val addr = InetAddress.getByName(hostIp)
        udpSocket = DatagramSocket()
        sendQueue = ArrayBlockingQueue(4096)
        val q = sendQueue ?: return

        worker = thread(start = true) {
            val sock = udpSocket ?: return@thread
            while (runMode == RunMode.GATT) {
                val frame = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try { sock.send(DatagramPacket(frame, frame.size, addr, port)) } catch (_: Exception) {}
            }
        }

        bleScanner = btAdapter.bluetoothLeScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf<ScanFilter>()

        updateStatus("GATT: scanning…")

        var seen = 0
        var winStart = SystemClock.elapsedRealtime()
        var last = ""

        scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (runMode != RunMode.GATT) return
                if (gattTriedConnect) return

                val sr = result.scanRecord ?: return
                val mac = result.device?.address ?: "??"
                val name = (sr.deviceName ?: result.device?.name ?: "").ifEmpty { "(no name)" }
                val rssi = result.rssi

                // status every 1s regardless of match
                seen++
                last = "$name $mac rssi=$rssi"
                val now = SystemClock.elapsedRealtime()
                if (now - winStart >= 1000) {
                    val fps = seen / ((now - winStart) / 1000.0)
                    updateStatus("GATT scan… seen≈${"%.0f".format(fps)}/s last=$last")
                    seen = 0
                    winStart = now
                }

                // ---- robust-ish uMyo heuristics ----
                val raw = sr.bytes ?: ByteArray(0)
                val rawStr = try { String(raw, Charsets.ISO_8859_1) } catch (_: Exception) { "" }

                val looksLikeNameInRaw = rawStr.contains("uMyo", ignoreCase = true)

                val mfg = sr.manufacturerSpecificData
                var hasMfg15 = false
                if (mfg != null && mfg.isNotEmpty()) {
                    for (i in 0 until mfg.size()) {
                        val b = mfg.valueAt(i)
                        if (b != null && b.size == 15) {
                            hasMfg15 = true
                            break
                        }
                    }
                }

                // This MAC heuristic is optional; can be wrong if stack uses random/static addr.
                val looksLikeMacPattern =
                    mac.startsWith("D4:", ignoreCase = true) ||
                            mac.startsWith("D7:", ignoreCase = true)

                val looksLikeUmyo = looksLikeNameInRaw || hasMfg15 || looksLikeMacPattern

                // If you want “connect to first thing” debugging, set this to true:
                val connectToFirstSeen = false

                if (!(connectToFirstSeen || looksLikeUmyo)) return

                gattTriedConnect = true

                // stop scanning and connect
                try { bleScanner?.stopScan(this) } catch (_: Exception) {}
                scanCb = null

                lastScanRssi = rssi.coerceIn(-128, 127)
                gattMac = mac
                gattDevId = crc32U32(mac.toByteArray(Charsets.UTF_8))
                gattSeq = 0u
                gattCount = 0
                gattWindowStartMs = SystemClock.elapsedRealtime()

                gattServicesStarted = false
                gattGotFirstData = false

                updateStatus("GATT: connecting to $name $mac (rssi=$lastScanRssi)…")

                gatt = result.device.connectGatt(
                    this@MainActivity,
                    false,
                    object : BluetoothGattCallback() {

                        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                            if (runMode != RunMode.GATT) return

                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                val prioOk = try {
                                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                                } catch (_: Exception) {
                                    false
                                }

                                gattServicesStarted = false
                                gattGotFirstData = false

                                val mtuOk = try {
                                    g.requestMtu(64)
                                } catch (_: Exception) {
                                    false
                                }

                                if (!mtuOk) {
                                    updateStatus("GATT: connected, high-priority=$prioOk, mtu request failed, discovering services…")
                                    if (!gattServicesStarted) {
                                        gattServicesStarted = true
                                        g.discoverServices()
                                    }
                                } else {
                                    updateStatus("GATT: connected, high-priority=$prioOk, waiting for MTU…")

                                    // Watchdog: some stacks never call onMtuChanged()
                                    thread(start = true) {
                                        Thread.sleep(1500)
                                        if (runMode == RunMode.GATT && !gattServicesStarted && gatt === g) {
                                            gattServicesStarted = true
                                            runOnUiThread {
                                                updateStatus("GATT: MTU callback timeout, discovering services anyway…")
                                            }
                                            try {
                                                g.discoverServices()
                                            } catch (_: Exception) {
                                            }
                                        }
                                    }
                                }
                            } else {
                                updateStatus("GATT: disconnected (status=$status)")
                            }
                        }

                        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                            if (runMode != RunMode.GATT) return
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                updateStatus("GATT: service discovery failed ($status)")
                                return
                            }

                            val svc = g.getService(umyoServiceUuid)
                            if (svc == null) {
                                updateStatus("GATT: service not found ($umyoServiceUuid)")
                                return
                            }

                            val ch = svc.getCharacteristic(umyoTelemCharUuid)
                            if (ch == null) {
                                updateStatus("GATT: characteristic not found ($umyoTelemCharUuid)")
                                return
                            }
                            gattChar = ch

                            if (!g.setCharacteristicNotification(ch, true)) {
                                updateStatus("GATT: setCharacteristicNotification failed")
                                return
                            }

                            val cccd = ch.getDescriptor(cccdUuid)
                            if (cccd == null) {
                                updateStatus("GATT: CCCD descriptor not found (0x2902)")
                                return
                            }

                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            val w = g.writeDescriptor(cccd)
                            updateStatus("GATT: enabling notifications…")
                            if (!w) updateStatus("GATT: writeDescriptor failed immediately")
                        }

                        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                            if (runMode != RunMode.GATT) return
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                gattGotFirstData = false
                                updateStatus("GATT: notifications enabled ✅ waiting for data…")

                                thread(start = true) {
                                    Thread.sleep(2000)
                                    if (runMode == RunMode.GATT && !gattGotFirstData && gatt === g) {
                                        runOnUiThread {
                                            updateStatus("GATT: notifications enabled but no data after 2s")
                                        }
                                    }
                                }
                            } else {
                                updateStatus("GATT: CCCD write failed ($status)")
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                            if (runMode != RunMode.GATT) return
                            val v = characteristic.value ?: return
                            gattGotFirstData = true
                            handleGattNotify(v, hostIp, port, updateStatus)
                        }
                        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                            if (runMode != RunMode.GATT) return
                            if (gattServicesStarted) return

                            gattServicesStarted = true

                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                updateStatus("GATT: mtu=$mtu status=$status, discovering services…")
                            } else {
                                updateStatus("GATT: mtu change failed ($status), discovering services anyway…")
                            }

                            try {
                                g.discoverServices()
                            } catch (_: Exception) {
                            }
                        }
                    }
                )
            }

            override fun onScanFailed(errorCode: Int) {
                updateStatus("GATT: scan failed $errorCode (Location must be ON)")
            }
        }

        try {
            bleScanner?.startScan(filters, settings, scanCb)
        } catch (e: Exception) {
            updateStatus("GATT: startScan exception: ${e.message}")
        }
    }

    private fun handleGattNotify(v: ByteArray, hostIp: String, port: Int, updateStatus: (String) -> Unit) {
        when (v.size) {
            20, 36, 52 -> {
                val tSrcBase = ByteBuffer.wrap(v, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
                val chunkCount = (v.size - 4) / 16

                for (chunk in 0 until chunkCount) {
                    val samples = ShortArray(8)
                    val bb = ByteBuffer.wrap(v, 4 + chunk * 16, 16).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until 8) samples[i] = bb.short

                    val tUs = (SystemClock.elapsedRealtimeNanos() / 1000L) and ((1L shl 48) - 1)
                    val frame = uf1EncodeFrameStatusEmg(
                        deviceId = gattDevId,
                        seq = gattSeq,
                        tUs = tUs,
                        tSrcSample = tSrcBase + (chunk * 8).toUInt(),
                        sampleRateHz = 1150,
                        batteryPct = 255,
                        rssiDbm = -128,
                        mode = 0,
                        statusFlags = 0,
                        samples = samples
                    )
                    gattSeq++
                    sendQueue?.offer(frame)
                }
            }

            26 -> {
                handleAux26(v)
            }

            60 -> {
                val tSrcBase = ByteBuffer.wrap(v, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()

                for (chunk in 0 until 3) {
                    val samples = ShortArray(8)
                    val bb = ByteBuffer.wrap(v, 4 + chunk * 16, 16).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until 8) samples[i] = bb.short

                    val tUs = (SystemClock.elapsedRealtimeNanos() / 1000L) and ((1L shl 48) - 1)
                    val frame = uf1EncodeFrameStatusEmg(
                        deviceId = gattDevId,
                        seq = gattSeq,
                        tUs = tUs,
                        tSrcSample = tSrcBase + (chunk * 8).toUInt(),
                        sampleRateHz = 1150,
                        batteryPct = 255,
                        rssiDbm = -128,
                        mode = 0,
                        statusFlags = 0,
                        samples = samples
                    )
                    gattSeq++
                    sendQueue?.offer(frame)
                }

                val quatVal = v.copyOfRange(52, 60)
                val tUs = (SystemClock.elapsedRealtimeNanos() / 1000L) and ((1L shl 48) - 1)

                val statusVal = ByteBuffer.allocate(10)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply {
                        putInt(0)
                        putShort(0)
                        put(255.toByte())
                        put(lastScanRssi.toByte())
                        put(0)
                        put(0)
                    }
                    .array()

                val frame = uf1EncodeFrameStatusPlusBlock(
                    deviceId = gattDevId,
                    seq = gattSeq,
                    tUs = tUs,
                    statusVal = statusVal,
                    blockType = 0x05,    // QUAT
                    blockVal = quatVal
                )

                gattSeq++
                sendQueue?.offer(frame)
            }

            else -> {
                val tUs = (SystemClock.elapsedRealtimeNanos() / 1000L) and ((1L shl 48) - 1)

                val statusVal = ByteBuffer.allocate(10)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply {
                        putInt(0)
                        putShort(0)
                        put(255.toByte())
                        put(lastScanRssi.toByte())
                        put(0)
                        put(0)
                    }
                    .array()

                val frame = uf1EncodeFrameStatusPlusBlock(
                    deviceId = gattDevId,
                    seq = gattSeq,
                    tUs = tUs,
                    statusVal = statusVal,
                    blockType = 0xF1,
                    blockVal = v
                )

                gattSeq++
                sendQueue?.offer(frame)
            }
        }

        gattCount++
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - gattWindowStartMs >= 1000) {
            val fps = gattCount / ((nowMs - gattWindowStartMs) / 1000.0)
            gattCount = 0
            gattWindowStartMs = nowMs
            updateStatus("GATT → $hostIp:$port notify≈${"%.1f".format(fps)} mac=$gattMac len=${v.size}")
        }
    }
    private fun handleAux26(v: ByteArray) {
        val imuVal = v.copyOfRange(0, 12)
        val magVal = v.copyOfRange(12, 18)
        val quatVal = v.copyOfRange(18, 26)

        val tUs = (SystemClock.elapsedRealtimeNanos() / 1000L) and ((1L shl 48) - 1)

        val statusVal = ByteBuffer.allocate(10)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(0)                 // no t_src_sample for aux-only frame
                putShort(0)               // sample_rate_hz unknown
                put(255.toByte())         // battery unknown
                put(lastScanRssi.toByte())
                put(0)                    // mode = PhoneOpt
                put(0)                    // status_flags
            }
            .array()

        val imuFrame = uf1EncodeFrameStatusPlusBlock(
            deviceId = gattDevId,
            seq = gattSeq,
            tUs = tUs,
            statusVal = statusVal,
            blockType = 0x03,            // IMU_6DOF
            blockVal = imuVal
        )
        gattSeq++
        sendQueue?.offer(imuFrame)

        val magFrame = uf1EncodeFrameStatusPlusBlock(
            deviceId = gattDevId,
            seq = gattSeq,
            tUs = tUs,
            statusVal = statusVal,
            blockType = 0x04,            // MAG_3
            blockVal = magVal
        )
        gattSeq++
        sendQueue?.offer(magFrame)

        val quatFrame = uf1EncodeFrameStatusPlusBlock(
            deviceId = gattDevId,
            seq = gattSeq,
            tUs = tUs,
            statusVal = statusVal,
            blockType = 0x05,            // QUAT
            blockVal = quatVal
        )
        gattSeq++
        sendQueue?.offer(quatFrame)
    }
    // -------------------------
    // UF1 encode helpers
    // -------------------------
    private fun tlv(type: Int, value: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(1 + 2 + value.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(type.toByte())
        bb.putShort(value.size.toShort())
        bb.put(value)
        return bb.array()
    }

    private fun ByteBuffer.putU48LE(v: Long) {
        for (i in 0 until 6) put(((v shr (8 * i)) and 0xFF).toByte())
    }

    private fun uf1EncodeFrameStatusPlusBlock(
        deviceId: UInt,
        seq: UInt,
        tUs: Long,
        statusVal: ByteArray,
        blockType: Int,
        blockVal: ByteArray
    ): ByteArray {
        val flags = flagTimeUsIsRx
        val payload = tlv(0x06, statusVal) + tlv(blockType, blockVal)
        val frameLen = 24 + payload.size

        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('U'.code.toByte()); put('D'.code.toByte())
            put(1); put(24)
            putShort(frameLen.toShort())
            putShort(flags.toShort())
            putInt(deviceId.toInt())
            put(0); put(0)
            putInt(seq.toInt())
            putU48LE(tUs)
        }.array()

        return header + payload
    }

    private fun uf1EncodeFrameStatusEmg(
        deviceId: UInt,
        seq: UInt,
        tUs: Long,
        tSrcSample: UInt,
        sampleRateHz: Int,
        batteryPct: Int,
        rssiDbm: Int,
        mode: Int,
        statusFlags: Int,
        samples: ShortArray
    ): ByteArray {
        val flags = (flagTimeSrcPresent.toInt() or flagTimeUsIsRx.toInt()).toUShort()

        val statusVal = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(tSrcSample.toInt())
            putShort(sampleRateHz.toShort())
            put(batteryPct.toByte())
            put(rssiDbm.toByte())
            put(mode.toByte())
            put(statusFlags.toByte())
        }.array()

        val emgVal = ByteBuffer.allocate(4 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1) // channel_count
            put(samples.size.toByte()) // samples_per_ch
            put(1) // sample_format=int16
            put(0)
            for (s in samples) putShort(s)
        }.array()

        val payload = tlv(0x06, statusVal) + tlv(0x01, emgVal)
        val frameLen = 24 + payload.size

        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('U'.code.toByte()); put('D'.code.toByte())
            put(1); put(24)
            putShort(frameLen.toShort())
            putShort(flags.toShort())
            putInt(deviceId.toInt())
            put(0); put(0)
            putInt(seq.toInt())
            putU48LE(tUs)
        }.array()

        return header + payload
    }

    private fun crc32U32(data: ByteArray): UInt {
        val crc = CRC32()
        crc.update(data)
        return crc.value.toUInt()
    }
}

@Composable
private fun BridgeScreen(
    status: String,
    host: String,
    portStr: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onStartSynthetic: () -> Unit,
    onStartBleScan: () -> Unit,
    onStartGattRaw: () -> Unit,
    onStop: () -> Unit,
    canEditDest: Boolean
) {
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text("UF1 Bridge Demo", style = MaterialTheme.typography.titleMedium)

        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = host,
            onValueChange = { if (canEditDest) onHostChange(it) },
            label = { Text("PC IP") },
            singleLine = true,
            enabled = canEditDest,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = portStr,
            onValueChange = { if (canEditDest) onPortChange(it) },
            label = { Text("Port") },
            singleLine = true,
            enabled = canEditDest,
            modifier = Modifier.fillMaxWidth()
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onStartSynthetic, modifier = Modifier.fillMaxWidth()) { Text("Start Synthetic") }
            Button(onClick = onStartBleScan, modifier = Modifier.fillMaxWidth()) { Text("Start BLE Scan") }
            Button(onClick = onStartGattRaw, modifier = Modifier.fillMaxWidth()) { Text("Start GATT Raw") }
        }

        Button(onClick = onStop) { Text("Stop") }

        Text(
            "Android 11: Location permission + Location toggle ON for BLE scan.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}