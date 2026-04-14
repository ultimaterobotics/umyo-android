package com.example.uf1bridgedemo

import android.annotation.SuppressLint
import android.util.Log
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
class UmyoApp : Application() {

    companion object {
        const val MAX_DEVICES = 6
    }

    // ---- Observable device list (Compose-safe) ----
    val deviceList = mutableStateListOf<DeviceSession>()

    // ---- Scanner state ----
    var isScanning by mutableStateOf(false)
        private set

    // ---- Streaming state ----
    var isStreaming by mutableStateOf(false)
        private set
    var streamLog by mutableStateOf("Idle")
        private set

    // ---- Saved destination (persists across activity transitions) ----
    var host by mutableStateOf("192.168.88.210")
    var portStr by mutableStateOf("26750")

    // ---- Internal state ----
    // Single send queue shared across all sessions
    private val sendQueue = ArrayBlockingQueue<ByteArray>(4096)

    private var udpSocket: DatagramSocket? = null
    private var workerThread: Thread? = null

    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null

    // MAC → session mapping (concurrent; deviceList is the Compose-observable mirror)
    private val deviceMap = ConcurrentHashMap<String, DeviceSession>()

    private val mainHandler = Handler(Looper.getMainLooper())

    // -----------------------------------------------------------------------
    // Streaming (UDP worker thread)
    // -----------------------------------------------------------------------

    fun startStreaming(hostIp: String, port: Int) {
        if (isStreaming) return
        val addr = try { InetAddress.getByName(hostIp) } catch (e: Exception) {
            streamLog = "Bad address: ${e.message}"
            return
        }
        val sock = DatagramSocket()
        udpSocket   = sock
        isStreaming  = true
        streamLog    = "Streaming → $hostIp:$port"

        workerThread = thread {
            while (isStreaming) {
                val frame = sendQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try { sock.send(DatagramPacket(frame, frame.size, addr, port)) }
                catch (_: Exception) {}
            }
        }

        // Re-enqueue the 0x07 name frame for every already-connected session.
        // stopStreaming() clears the queue, so any name frame buffered before streaming
        // started is gone. Each session re-sends its name so the workbench can label lanes.
        Log.d("UmyoApp", "startStreaming: deviceList.size=${deviceList.size} queueSize=${sendQueue.size}")
        deviceList.forEach { it.reEnqueueNameFrame(sendQueue) }
        Log.d("UmyoApp", "startStreaming: reEnqueueNameFrame done queueSize=${sendQueue.size}")
    }

    fun stopStreaming() {
        isStreaming = false
        workerThread?.join(400)
        workerThread = null
        udpSocket?.close()
        udpSocket = null
        sendQueue.clear()
        streamLog = "Idle"
    }

    // -----------------------------------------------------------------------
    // BLE scanner — keeps running until stopScan() / MAX_DEVICES reached
    // -----------------------------------------------------------------------

    fun startScan(btAdapter: BluetoothAdapter, onLog: (String) -> Unit) {
        if (isScanning) return
        isScanning  = true
        bleScanner  = btAdapter.bluetoothLeScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        var seen     = 0
        var winStart = SystemClock.elapsedRealtime()

        scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!isScanning) return

                val mac  = result.device?.address ?: return
                val rssi = result.rssi.coerceIn(-128, 127)

                // Update RSSI for already-tracked device
                deviceMap[mac]?.let { session ->
                    mainHandler.post { session.updateRssi(rssi) }
                }

                // Scan-rate stats
                seen++
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - winStart >= 1000) {
                    val fps = seen / ((nowMs - winStart) / 1000.0)
                    seen     = 0
                    winStart = nowMs
                    mainHandler.post { streamLog = "Scanning… ${"%.0f".format(fps)} adv/s" }
                }

                // Already have this device? Nothing more to do.
                if (deviceMap.containsKey(mac)) return

                // Enforce device cap
                if (deviceMap.size >= MAX_DEVICES) return

                // Filter: advertised name must start with "uMyo, after decoding UTF"
                val devName = result.scanRecord?.deviceName ?: return
                val advName = URLDecoder.decode(devName, "UTF-8");
                if (!advName.startsWith("uMyo")) return

                // Register and connect
                val devId   = crc32U32(mac.toByteArray(Charsets.UTF_8))
                val session = DeviceSession(mac, devId, rssi)
                deviceMap[mac] = session
                mainHandler.post { deviceList.add(session) }

                onLog("Connecting to $advName ($mac)…")

                session.connect(
                    context     = applicationContext,
                    device      = result.device,
                    queue       = sendQueue,
                    isStreaming = { isStreaming },
                    onLog       = onLog,
                )
            }

            override fun onScanFailed(errorCode: Int) {
                mainHandler.post {
                    isScanning = false
                    onLog("Scan failed: $errorCode")
                }
            }
        }

        try {
            bleScanner?.startScan(emptyList(), settings, scanCb)
            onLog("Scanning for uMyo devices (max $MAX_DEVICES)…")
        } catch (e: Exception) {
            isScanning = false
            onLog("startScan failed: ${e.message}")
        }
    }

    fun stopScan() {
        try {
            val cb = scanCb
            if (cb != null) bleScanner?.stopScan(cb)
        } catch (_: Exception) {}
        scanCb    = null
        bleScanner = null
        isScanning = false
    }

    // -----------------------------------------------------------------------
    // Device management
    // -----------------------------------------------------------------------

    fun disconnectDevice(mac: String) {
        val session = deviceMap.remove(mac) ?: return
        session.disconnect()
        mainHandler.post { deviceList.remove(session) }
    }

    fun disconnectAll() {
        deviceMap.keys.toList().forEach { disconnectDevice(it) }
    }

}
