package com.example.uf1bridgedemo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * All per-device GATT state for one connected uMyo.
 *
 * Compose-observable fields (deviceName, status, lastScanRssi, notifyRateFps,
 * lastPayloadLen) are always written on the main thread via [mainHandler].
 * Internal bookkeeping fields are volatile or accessed only from GATT callbacks.
 */
@SuppressLint("MissingPermission")
class DeviceSession(
    val mac: String,
    val devId: UInt,
    initialRssi: Int,
) {
    enum class Status { CONNECTING, CONNECTED, STREAMING, DISCONNECTED, ERROR }

    // ---- UI-observable state ----
    var deviceName by mutableStateOf(mac)   // updated after name-char read
        private set
    var status by mutableStateOf(Status.CONNECTING)
        private set
    var lastScanRssi by mutableIntStateOf(initialRssi)
        private set
    var notifyRateFps by mutableDoubleStateOf(0.0)
        private set
    var lastPayloadLen by mutableIntStateOf(0)
        private set

    // ---- GATT lifecycle ----
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var gattServicesStarted  = false
    @Volatile private var gattGotFirstData     = false
    // Set to true when enableTelemetry() is first called. Used by the name-read watchdog
    // to detect whether the handshake is stuck waiting for onCharacteristicRead.
    @Volatile private var telemetryStarted     = false
    // Name captured synchronously on the GATT thread — safe to read without posting to main thread.
    // Guaranteed non-null before first onCharacteristicChanged fires: handleNameRead sets it
    // before calling enableTelemetry, and notifications only start after enableTelemetry completes.
    @Volatile private var resolvedName: String? = null

    // ---- Per-device UF1 sequencing ----
    private val seqCounter = AtomicInteger(0)
    private fun nextSeq(): UInt = seqCounter.getAndIncrement().toUInt()

    // ---- Notify-rate stats ----
    private var notifyCount   = 0
    private var windowStartMs = SystemClock.elapsedRealtime()

    // ---- Send queue (assigned in connect(), used in GATT callbacks) ----
    private lateinit var sendQueue: ArrayBlockingQueue<ByteArray>

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- UUIDs ----
    private val umyoServiceUuid   = UUID.fromString("93375900-F229-8B49-B397-44B5899B8601")
    private val umyoTelemCharUuid = UUID.fromString("FC7A850D-C1A5-F61F-0DA7-9995621FBD01")
    private val umyoNameCharUuid  = UUID.fromString("FC7A850D-C1A5-F61F-0DA7-9995621FBD02")
    private val cccdUuid          = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------

    /**
     * Initiate GATT connection and start telemetry pipeline.
     * Safe to call from any thread.
     */
    fun connect(
        context: Context,
        device: BluetoothDevice,
        queue: ArrayBlockingQueue<ByteArray>,
        isStreaming: () -> Boolean,
        onLog: (String) -> Unit,
    ) {
        sendQueue = queue
        gattServicesStarted = false
        gattGotFirstData    = false

        gatt = device.connectGatt(context, false, buildGattCallback(isStreaming, onLog))
    }

    /** Update RSSI from scanner (called on main thread by UmyoApp). */
    fun updateRssi(rssi: Int) {
        lastScanRssi = rssi
    }

    /**
     * Re-enqueue the 0x07 name frame into the shared send queue.
     * Called by UmyoApp.startStreaming() for every connected session so the
     * workbench always receives the name frame even if streaming was stopped
     * and restarted after the initial namePending handshake completed.
     * No-op if the name has not yet been resolved from firmware.
     */
    fun reEnqueueNameFrame(queue: ArrayBlockingQueue<ByteArray>) {
        // Only resend for active sessions (first data already arrived and set resolvedName).
        // Mid-handshake sessions (gattGotFirstData=false) will send the name frame themselves
        // on their first onCharacteristicChanged.
        if (!gattGotFirstData) {
            Log.d("DeviceSession", "[$mac] reEnqueueNameFrame: skipped (session not yet active)")
            return
        }
        val name = resolvedName
        Log.d("DeviceSession", "[$mac] reEnqueueNameFrame: resolvedName=$name queueSize=${queue.size}")
        if (name == null) return
        val offered = queue.offer(uf1EncodeDeviceName(devId, nextSeq(), name))
        Log.d("DeviceSession", "[$mac] reEnqueueNameFrame: offer=$offered queueSize=${queue.size}")
    }

    /** Cleanly close the GATT connection. */
    fun disconnect() {
        mainHandler.post { status = Status.DISCONNECTED }
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close()     } catch (_: Exception) {}
        gatt = null
    }

    // ------------------------------------------------------------
    // GATT callback
    // ------------------------------------------------------------

    private fun buildGattCallback(
        isStreaming: () -> Boolean,
        onLog: (String) -> Unit,
    ) = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (status == Status.DISCONNECTED) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                catch (_: Exception) {}

                gattServicesStarted = false
                gattGotFirstData    = false
                telemetryStarted    = false
                resolvedName        = null
                mainHandler.post { status = Status.CONNECTED }

                val mtuOk = try { g.requestMtu(64) } catch (_: Exception) { false }
                if (!mtuOk) {
                    if (!gattServicesStarted) {
                        gattServicesStarted = true
                        g.discoverServices()
                    }
                } else {
                    // Watchdog: some stacks never deliver onMtuChanged
                    thread {
                        Thread.sleep(1500)
                        if (status != Status.DISCONNECTED && !gattServicesStarted && gatt === g) {
                            gattServicesStarted = true
                            try { g.discoverServices() } catch (_: Exception) {}
                        }
                    }
                }
            } else {
                mainHandler.post { status = Status.DISCONNECTED }
                onLog("[$mac] disconnected (status=$st)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            if (status == Status.DISCONNECTED || gattServicesStarted) return
            gattServicesStarted = true
            try { g.discoverServices() } catch (_: Exception) {}
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            if (status == Status.DISCONNECTED) return
            if (st != BluetoothGatt.GATT_SUCCESS) {
                onLog("[$mac] service discovery failed ($st)")
                return
            }
            val svc = g.getService(umyoServiceUuid) ?: run {
                onLog("[$mac] uMyo service not found")
                return
            }

            // Try to read device name before enabling telemetry
            val nameChar = svc.getCharacteristic(umyoNameCharUuid)
            Log.d("DeviceSession", "[$mac] onServicesDiscovered: fbd02 char=${if (nameChar != null) "found" else "null"}")
            if (nameChar != null) {
                val ok = g.readCharacteristic(nameChar)
                Log.d("DeviceSession", "[$mac] readCharacteristic(fbd02)=$ok")
                if (!ok) {
                    // Stack refused the read — skip name, proceed without it
                    Log.d("DeviceSession", "[$mac] readCharacteristic failed, enabling telemetry without name")
                    enableTelemetry(g, svc, onLog)
                } else {
                    // Watchdog: if onCharacteristicRead never fires (stack swallowed the callback),
                    // enableTelemetry will never be called and the device hangs forever.
                    // After 5 s, fall through without the name rather than stay stuck.
                    thread {
                        Thread.sleep(5000)
                        if (!telemetryStarted && status != Status.DISCONNECTED && gatt === g) {
                            Log.d("DeviceSession", "[$mac] name-read watchdog fired — onCharacteristicRead never arrived, enabling telemetry without name")
                            onLog("[$mac] name read timed out, proceeding without name")
                            val svcRetry = g.getService(umyoServiceUuid)
                            if (svcRetry != null) enableTelemetry(g, svcRetry, onLog)
                        }
                    }
                }
                // else (ok=true): enableTelemetry() called from onCharacteristicRead after name arrives
            } else {
                enableTelemetry(g, svc, onLog)
            }
        }

        // API < 33 path
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            st: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleNameRead(g, characteristic.uuid, characteristic.value ?: ByteArray(0), st)
            }
        }

        // API 33+ path
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            st: Int,
        ) {
            handleNameRead(g, characteristic.uuid, value, st)
        }

        private fun handleNameRead(g: BluetoothGatt, uuid: UUID, value: ByteArray, st: Int) {
            if (uuid != umyoNameCharUuid) return
            Log.d("DeviceSession", "[$mac] onCharacteristicRead fbd02: status=$st bytes=${value.size} raw=${value.toList()} instance=${System.identityHashCode(this@DeviceSession)}")
            if (st == BluetoothGatt.GATT_SUCCESS) {
                val name = value.toString(Charsets.UTF_8)
                    .trim { it == '\u0000' || it.isWhitespace() }
                    .takeIf { it.isNotEmpty() }
                if (name != null) {
                    resolvedName = name                     // sync — set before enableTelemetry
                    mainHandler.post { deviceName = name }  // async — UI update
                    Log.d("DeviceSession", "[$mac] resolvedName=\"$resolvedName\" instance=${System.identityHashCode(this@DeviceSession)}")
                }
            }
            // enableTelemetry is always called here, after resolvedName is set.
            // Notifications cannot start until after this call completes (writeDescriptor → onDescriptorWrite),
            // so resolvedName is guaranteed non-null before the first onCharacteristicChanged fires.
            val svc = g.getService(umyoServiceUuid)
            if (svc != null) enableTelemetry(g, svc, onLog)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            st: Int,
        ) {
            if (st == BluetoothGatt.GATT_SUCCESS) {
                gattGotFirstData = false
                onLog("[$mac] notifications enabled, waiting for data…")
                thread {
                    Thread.sleep(2000)
                    if (!gattGotFirstData && status != Status.DISCONNECTED) {
                        onLog("[$mac] no data after 2s — check firmware version")
                    }
                }
            } else {
                onLog("[$mac] CCCD write failed ($st)")
            }
        }

        // API < 33 path
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val v = characteristic.value ?: return
                val firstData = !gattGotFirstData
                gattGotFirstData = true
                Log.d("DeviceSession", "[$mac] onCharacteristicChanged API<33: firstData=$firstData isStreaming=${isStreaming()} instance=${System.identityHashCode(this@DeviceSession)}")
                if (firstData) {
                    val nameForFrame = resolvedName ?: mac
                    val offered = sendQueue.offer(uf1EncodeDeviceName(devId, nextSeq(), nameForFrame))
                    Log.d("DeviceSession", "[$mac] name frame: resolvedName=\"$nameForFrame\" offer=$offered")
                }
                if (!isStreaming()) return
                mainHandler.post { status = Status.STREAMING }
                handleGattNotify(v)
            }
        }

        // API 33+ path
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val firstData = !gattGotFirstData
            gattGotFirstData = true
            Log.d("DeviceSession", "[$mac] onCharacteristicChanged API33+: firstData=$firstData isStreaming=${isStreaming()} instance=${System.identityHashCode(this@DeviceSession)}")
            if (firstData) {
                val nameForFrame = resolvedName ?: mac
                val offered = sendQueue.offer(uf1EncodeDeviceName(devId, nextSeq(), nameForFrame))
                Log.d("DeviceSession", "[$mac] name frame: resolvedName=\"$nameForFrame\" offer=$offered")
            }
            if (!isStreaming()) return
            mainHandler.post { status = Status.STREAMING }
            handleGattNotify(value)
        }
    }

    // ------------------------------------------------------------
    // GATT helpers
    // ------------------------------------------------------------

    private fun enableTelemetry(g: BluetoothGatt, svc: BluetoothGattService, onLog: (String) -> Unit) {
        telemetryStarted = true
        val ch = svc.getCharacteristic(umyoTelemCharUuid) ?: run {
            onLog("[$mac] telem characteristic not found")
            return
        }

        if (!g.setCharacteristicNotification(ch, true)) {
            onLog("[$mac] setCharacteristicNotification failed")
            return
        }

        val cccd = ch.getDescriptor(cccdUuid) ?: run {
            onLog("[$mac] CCCD descriptor not found")
            return
        }

        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!g.writeDescriptor(cccd)) onLog("[$mac] writeDescriptor failed")
    }

    // ------------------------------------------------------------
    // UF1 fanout — same logic as before, now per-session
    // ------------------------------------------------------------

    // Connected GATT payload mapping:
    // 20/36/52 = raw-only packet family:
    //            4-byte tSrc base + 1/2/3 raw EMG chunks (8 int16 samples per chunk)
    // 26        = S2 aux packet: IMU + MAG + QUAT
    // 60        = S1 packet: raw3 + QUAT
    // other     = forwarded as unknown/debug block 0xF1
    private fun handleGattNotify(v: ByteArray) {
        when (v.size) {
            20, 36, 52 -> {
                val tSrcBase  = ByteBuffer.wrap(v, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
                val chunkCount = (v.size - 4) / 16
                for (chunk in 0 until chunkCount) {
                    val samples = ShortArray(8)
                    val bb = ByteBuffer.wrap(v, 4 + chunk * 16, 16).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until 8) samples[i] = bb.short
                    sendQueue.offer(
                        uf1EncodeFrameStatusEmg(
                            deviceId = devId, seq = nextSeq(), tUs = tUs(),
                            tSrcSample = tSrcBase + (chunk * 8).toUInt(),
                            sampleRateHz = 1150, batteryPct = 255, rssiDbm = lastScanRssi,
                            mode = 0, statusFlags = 0, samples = samples
                        )
                    )
                }
            }

            26 -> handleAux26(v)

            60 -> {
                // S1: 3× EMG chunks (48 bytes) + 8-byte QUAT
                val tSrcBase = ByteBuffer.wrap(v, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
                for (chunk in 0 until 3) {
                    val samples = ShortArray(8)
                    val bb = ByteBuffer.wrap(v, 4 + chunk * 16, 16).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until 8) samples[i] = bb.short
                    sendQueue.offer(
                        uf1EncodeFrameStatusEmg(
                            deviceId = devId, seq = nextSeq(), tUs = tUs(),
                            tSrcSample = tSrcBase + (chunk * 8).toUInt(),
                            sampleRateHz = 1150, batteryPct = 255, rssiDbm = lastScanRssi,
                            mode = 0, statusFlags = 0, samples = samples
                        )
                    )
                }
                sendQueue.offer(
                    uf1EncodeFrameStatusPlusBlock(
                        deviceId = devId, seq = nextSeq(), tUs = tUs(),
                        statusVal = makeStatusVal(), blockType = 0x05,
                        blockVal = v.copyOfRange(52, 60)  // QUAT
                    )
                )
            }

            else -> {
                sendQueue.offer(
                    uf1EncodeFrameStatusPlusBlock(
                        deviceId = devId, seq = nextSeq(), tUs = tUs(),
                        statusVal = makeStatusVal(), blockType = 0xF1, blockVal = v
                    )
                )
            }
        }

        // Update notify-rate stats once per second
        notifyCount++
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - windowStartMs >= 1000) {
            val fps = notifyCount / ((nowMs - windowStartMs) / 1000.0)
            val len = v.size
            notifyCount   = 0
            windowStartMs = nowMs
            mainHandler.post {
                notifyRateFps  = fps
                lastPayloadLen = len
            }
        }
    }

    // Split S2 aux payload into UF1 blocks:
    // 0x03 = IMU_6DOF, 0x04 = MAG_3, 0x05 = QUAT
    private fun handleAux26(v: ByteArray) {
        val sv  = makeStatusVal()
        val tUs = tUs()
        sendQueue.offer(uf1EncodeFrameStatusPlusBlock(devId, nextSeq(), tUs, sv, 0x03, v.copyOfRange(0,  12)))
        sendQueue.offer(uf1EncodeFrameStatusPlusBlock(devId, nextSeq(), tUs, sv, 0x04, v.copyOfRange(12, 18)))
        sendQueue.offer(uf1EncodeFrameStatusPlusBlock(devId, nextSeq(), tUs, sv, 0x05, v.copyOfRange(18, 26)))
    }

    private fun tUs(): Long =
        (SystemClock.elapsedRealtimeNanos() / 1000L) and ((1L shl 48) - 1)

    private fun makeStatusVal(): ByteArray =
        ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0); putShort(0)
            put(255.toByte()); put(lastScanRssi.toByte())
            put(0); put(0)
        }.array()
}
