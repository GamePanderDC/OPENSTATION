package com.openstation.launcher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams 16 kHz mono 16-bit PCM from the paired Yandex BLE Remote microphone
 * to the local openstationd daemon on loopback UDP 8088 ("RMC1" + PCM16 LE),
 * so the remote mic works in tandem with the station's built-in microphone array.
 *
 * Primary transport uses Yandex's preinstalled HIDL service
 * (`vendor.yandex.rcuvoiceserver@1.0::IRcuVoiceServer/rcuvoiceservice`).
 * If that service is absent or fails to start, falls back directly to the
 * OmniRCU BLE GATT ADPCM voice service (`65fa9513-e8ca-4efe-b5ba-67b7c44101dc`).
 */
class RemoteMicStreamer(
    private val context: Context,
    private val bleRemoteManager: BleRemoteManager?
) {
    companion object {
        private const val TAG = "RemoteMicStreamer"
        private const val IFACE_NAME = "vendor.yandex.rcuvoiceserver@1.0::IRcuVoiceServer"
        private const val INSTANCE_NAME = "rcuvoiceservice"
        private const val TX_START_MIC = 1
        private const val TX_STOP_MIC = 2
        private const val TX_READ_VOICE = 3
        private const val UDP_REMOTE_MIC_PORT = 8088
        private const val MAX_SESSION_MS = 35_000L

        val VOICE_SERVICE_UUID: UUID = UUID.fromString("65fa9513-e8ca-4efe-b5ba-67b7c44101dc")
        val MIC_CONTROL_UUID: UUID = UUID.fromString("20d695c7-1f7b-4d11-afec-c6c5cfae7f52")
        val VOICE_DATA_UUID: UUID = UUID.fromString("9c98fa55-3de4-4361-8f26-ba1c62c8f222")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val PACKET_MAGIC = byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    }

    /**
     * Exact IMA-ADPCM decoder matching `ru.yandex.omniremote.ADPCMDecoder`:
     * high nibble first per byte, 89-entry stepSizeTable, clipped to 16-bit signed PCM LE.
     */
    class ImaAdpcmDecoder {
        private var valpred = 0
        private var index = 0

        fun reset() {
            valpred = 0
            index = 0
        }

        fun decodeBlockToPcmLeBytes(packet: ByteArray, offset: Int = 6): ByteArray {
            if (offset >= packet.size) return ByteArray(0)
            val adpcmLen = packet.size - offset
            val out = ByteArray(adpcmLen * 4)
            var outIdx = 0
            var curPred = valpred
            var curIdx = index.coerceIn(0, 88)
            var step = STEP_SIZE_TABLE[curIdx]

            for (i in offset until packet.size) {
                val b = packet[i].toInt() and 0xFF
                // OmniRCU processes high nibble first, then low nibble.
                for (shift in 4 downTo 0 step 4) {
                    val delta = (b ushr shift) and 0x0F
                    curIdx = (curIdx + INDEX_TABLE[delta]).coerceIn(0, 88)

                    val sign = (delta and 8) != 0
                    val mag = delta and 7
                    var diff = step ushr 3
                    if ((mag and 4) != 0) diff += step
                    if ((mag and 2) != 0) diff += step ushr 1
                    if ((mag and 1) != 0) diff += step ushr 2

                    curPred = if (sign) curPred - diff else curPred + diff
                    if (curPred > 32767) curPred = 32767
                    else if (curPred < -32768) curPred = -32768

                    step = STEP_SIZE_TABLE[curIdx]
                    out[outIdx++] = (curPred and 0xFF).toByte()
                    out[outIdx++] = ((curPred ushr 8) and 0xFF).toByte()
                }
            }

            valpred = curPred
            index = curIdx
            return out
        }

        companion object {
            private val INDEX_TABLE = intArrayOf(
                -1, -1, -1, -1, 2, 4, 6, 8,
                -1, -1, -1, -1, 2, 4, 6, 8
            )
            private val STEP_SIZE_TABLE = intArrayOf(
                7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
                19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
                50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
                130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
                337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
                876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
                2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
                5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
                15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
            )
        }
    }

    private val wantActive = AtomicBoolean(false)
    private val workerRunning = AtomicBoolean(false)
    private val lastActivateMs = AtomicLong(0L)
    private val lock = Any()
    @Volatile private var workerThread: Thread? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var activeGatt: BluetoothGatt? = null
    @Volatile private var activeCtrlChar: BluetoothGattCharacteristic? = null

    fun setActive(active: Boolean, reason: String = "") {
        if (active) {
            lastActivateMs.set(SystemClock.elapsedRealtime())
            val wasWanted = wantActive.getAndSet(true)
            if (wasWanted && workerRunning.get()) {
                return
            }
            // Do not wake BT needlessly if no remote is paired/connected at all, unless
            // IRcuVoiceServer is hosted for a live Yandex remote.
            val st = bleRemoteManager?.getRemoteStatus()
            if (st != null && !st.bonded && !st.connected) {
                wantActive.set(false)
                return
            }
            synchronized(lock) {
                if (workerRunning.compareAndSet(false, true)) {
                    val t = Thread({ runStreamingLoop(reason) }, "RemoteMicStreamer")
                    t.isDaemon = true
                    workerThread = t
                    t.start()
                }
            }
        } else {
            wantActive.set(false)
            closeGattQuiet()
        }
    }

    fun release() {
        wantActive.set(false)
        closeGattQuiet()
        try { workerThread?.interrupt() } catch (_: Throwable) {}
        try { udpSocket?.close() } catch (_: Throwable) {}
        udpSocket = null
    }

    private fun ensureSocket(): DatagramSocket? {
        val existing = udpSocket
        if (existing != null && !existing.isClosed) return existing
        return try {
            val sock = DatagramSocket()
            udpSocket = sock
            sock
        } catch (t: Throwable) {
            Log.w(TAG, "UDP socket create failed: ${t.message}")
            null
        }
    }

    private fun sendPcmChunkToDaemon(pcm: ByteArray, len: Int) {
        if (len < 2) return
        val sock = ensureSocket() ?: return
        val loop = InetAddress.getLoopbackAddress()
        var off = 0
        val usableLen = len and 0x7FFFFFFE // even sample boundary
        while (off < usableLen) {
            val chunk = minOf(1280, usableLen - off)
            val pkt = ByteArray(4 + chunk)
            System.arraycopy(PACKET_MAGIC, 0, pkt, 0, 4)
            System.arraycopy(pcm, off, pkt, 4, chunk)
            try {
                sock.send(DatagramPacket(pkt, pkt.size, loop, UDP_REMOTE_MIC_PORT))
            } catch (t: Throwable) {
                Log.w(TAG, "UDP 8088 send failed: ${t.message}")
                break
            }
            off += chunk
        }
    }

    private fun runStreamingLoop(reason: String) {
        try {
            val hidlOk = tryStreamViaHidl(reason)
            if (!hidlOk && wantActive.get()) {
                val st = bleRemoteManager?.getRemoteStatus()
                if (st?.connected == true) {
                    tryStreamViaDirectGatt()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Remote mic loop error: ${t.message}")
        } finally {
            closeGattQuiet()
            workerRunning.set(false)
        }
    }

    private fun getRcuVoiceBinder(): Any? {
        return try {
            val cls = Class.forName("android.os.HwBinder")
            try {
                val m3 = cls.getMethod(
                    "getService",
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
                m3.invoke(null, IFACE_NAME, INSTANCE_NAME, true)
            } catch (_: NoSuchMethodException) {
                val m2 = cls.getMethod("getService", String::class.java, String::class.java)
                m2.invoke(null, IFACE_NAME, INSTANCE_NAME)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "IRcuVoiceServer HIDL lookup failed: ${t.message}")
            null
        }
    }

    private fun hidlTransactStartOrStop(binder: Any, txCode: Int): Int {
        val hwParcelCls = Class.forName("android.os.HwParcel")
        val req = hwParcelCls.getConstructor().newInstance()
        val reply = hwParcelCls.getConstructor().newInstance()
        try {
            hwParcelCls.getMethod("writeInterfaceToken", String::class.java).invoke(req, IFACE_NAME)
            val iHwBinderCls = Class.forName("android.os.IHwBinder")
            iHwBinderCls.getMethod(
                "transact",
                Int::class.javaPrimitiveType,
                hwParcelCls,
                hwParcelCls,
                Int::class.javaPrimitiveType
            ).invoke(binder, txCode, req, reply, 0)
            hwParcelCls.getMethod("verifySuccess").invoke(reply)
            hwParcelCls.getMethod("releaseTemporaryStorage").invoke(req)
            return hwParcelCls.getMethod("readInt32").invoke(reply) as? Int ?: -1
        } finally {
            try { hwParcelCls.getMethod("release").invoke(req) } catch (_: Throwable) {}
            try { hwParcelCls.getMethod("release").invoke(reply) } catch (_: Throwable) {}
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun hidlTransactReadVoice(binder: Any, maxBytes: Int): Pair<Int, ByteArray?> {
        val hwParcelCls = Class.forName("android.os.HwParcel")
        val req = hwParcelCls.getConstructor().newInstance()
        val reply = hwParcelCls.getConstructor().newInstance()
        try {
            hwParcelCls.getMethod("writeInterfaceToken", String::class.java).invoke(req, IFACE_NAME)
            hwParcelCls.getMethod("writeInt32", Int::class.javaPrimitiveType).invoke(req, maxBytes)
            val iHwBinderCls = Class.forName("android.os.IHwBinder")
            iHwBinderCls.getMethod(
                "transact",
                Int::class.javaPrimitiveType,
                hwParcelCls,
                hwParcelCls,
                Int::class.javaPrimitiveType
            ).invoke(binder, TX_READ_VOICE, req, reply, 0)
            hwParcelCls.getMethod("verifySuccess").invoke(reply)
            hwParcelCls.getMethod("releaseTemporaryStorage").invoke(req)
            val ret = hwParcelCls.getMethod("readInt32").invoke(reply) as? Int ?: -1
            if (ret < 0) return ret to null
            val vec = hwParcelCls.getMethod("readInt8Vector").invoke(reply) as? List<Byte>
            if (vec.isNullOrEmpty()) return ret to null
            val n = minOf(ret, vec.size)
            val out = ByteArray(n)
            for (i in 0 until n) out[i] = vec[i]
            return n to out
        } finally {
            try { hwParcelCls.getMethod("release").invoke(req) } catch (_: Throwable) {}
            try { hwParcelCls.getMethod("release").invoke(reply) } catch (_: Throwable) {}
        }
    }

    private fun tryStreamViaHidl(reason: String): Boolean {
        val binder = getRcuVoiceBinder() ?: return false
        val started = try {
            hidlTransactStartOrStop(binder, TX_START_MIC)
        } catch (t: Throwable) {
            Log.w(TAG, "HIDL startMic failed: ${t.message}")
            return false
        }
        if (started != 0) {
            Log.d(TAG, "HIDL startMic returned $started ($reason)")
            return false
        }
        Log.i(TAG, "BLE Remote Mic started via IRcuVoiceServer HIDL ($reason)")
        var emptyStreak = 0
        try {
            while (wantActive.get() && !Thread.currentThread().isInterrupted) {
                if ((SystemClock.elapsedRealtime() - lastActivateMs.get()) > MAX_SESSION_MS) {
                    break
                }
                val (cnt, pcm) = try {
                    hidlTransactReadVoice(binder, 640)
                } catch (t: Throwable) {
                    Log.w(TAG, "HIDL readVoice failed: ${t.message}")
                    break
                }
                if (cnt < 0) {
                    Log.d(TAG, "HIDL readVoice ended (ret=$cnt)")
                    break
                }
                if (pcm != null && pcm.size >= 2) {
                    emptyStreak = 0
                    sendPcmChunkToDaemon(pcm, pcm.size)
                } else {
                    emptyStreak++
                    if (emptyStreak > 200) {
                        // ~2 seconds with zero bytes from remote
                        break
                    }
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }
        } finally {
            try { hidlTransactStartOrStop(binder, TX_STOP_MIC) } catch (_: Throwable) {}
            Log.i(TAG, "BLE Remote Mic stopped (HIDL)")
        }
        return true
    }

    private fun findConnectedRemoteDevice(): BluetoothDevice? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val targetAddr = bleRemoteManager?.getRemoteStatus()?.address
        try {
            for (d in bm.getConnectedDevices(BluetoothProfile.GATT)) {
                if ((targetAddr != null && d.address == targetAddr) ||
                    bleRemoteManager?.isYandexRemoteName(d.name) == true
                ) {
                    return d
                }
            }
        } catch (_: Throwable) {}
        val adapter: BluetoothAdapter = bm.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return null
        return try {
            adapter.bondedDevices?.firstOrNull {
                (targetAddr != null && it.address == targetAddr) ||
                    bleRemoteManager?.isYandexRemoteName(it.name) == true
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryStreamViaDirectGatt() {
        val dev = findConnectedRemoteDevice() ?: return
        val decoder = ImaAdpcmDecoder()
        val doneLatch = CountDownLatch(1)
        val gattStarted = AtomicBoolean(false)

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && wantActive.get()) {
                    try { gatt.discoverServices() } catch (_: Throwable) { doneLatch.countDown() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    doneLatch.countDown()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS || !wantActive.get()) {
                    doneLatch.countDown()
                    return
                }
                val svc = gatt.getService(VOICE_SERVICE_UUID)
                val ctrl = svc?.getCharacteristic(MIC_CONTROL_UUID)
                val data = svc?.getCharacteristic(VOICE_DATA_UUID)
                if (ctrl == null || data == null) {
                    Log.w(TAG, "OmniRCU voice GATT service missing on ${dev.address}")
                    doneLatch.countDown()
                    return
                }
                synchronized(lock) {
                    activeCtrlChar = ctrl
                }
                if (!wantActive.get()) {
                    doneLatch.countDown()
                    return
                }
                try {
                    gatt.setCharacteristicNotification(data, true)
                    val cccd = data.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccd)
                    } else {
                        ctrl.value = byteArrayOf(0xA2.toByte(), 0x01)
                        gatt.writeCharacteristic(ctrl)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "OmniRCU GATT setup error: ${t.message}")
                    doneLatch.countDown()
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                val ctrl = synchronized(lock) { activeCtrlChar } ?: run { doneLatch.countDown(); return }
                if (status != BluetoothGatt.GATT_SUCCESS || !wantActive.get()) {
                    doneLatch.countDown()
                    return
                }
                try {
                    ctrl.value = byteArrayOf(0xA2.toByte(), 0x01)
                    gatt.writeCharacteristic(ctrl)
                    gattStarted.set(true)
                    decoder.reset()
                    Log.i(TAG, "BLE Remote Mic started via direct OmniRCU GATT (${dev.address})")
                } catch (_: Throwable) {
                    doneLatch.countDown()
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid != VOICE_DATA_UUID || !wantActive.get()) return
                val value = characteristic.value ?: return
                if (value.size > 6 && value[2] == 0xA5.toByte()) {
                    val pcm = decoder.decodeBlockToPcmLeBytes(value, 6)
                    if (pcm.size >= 2) {
                        sendPcmChunkToDaemon(pcm, pcm.size)
                    }
                }
            }
        }

        val gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dev.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            } else {
                dev.connectGatt(context, false, cb)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "connectGatt fallback failed: ${t.message}")
            return
        }
        val aborted = synchronized(lock) {
            if (!wantActive.get()) {
                true
            } else {
                activeGatt = gatt
                false
            }
        }
        if (aborted) {
            try { gatt.disconnect() } catch (_: Throwable) {}
            try { gatt.close() } catch (_: Throwable) {}
            return
        }
        try {
            while (wantActive.get() && !Thread.currentThread().isInterrupted) {
                if ((SystemClock.elapsedRealtime() - lastActivateMs.get()) > MAX_SESSION_MS) break
                if (doneLatch.await(200L, TimeUnit.MILLISECONDS)) break
            }
        } catch (_: InterruptedException) {
        } finally {
            closeGattQuiet()
            if (gattStarted.get()) {
                Log.i(TAG, "BLE Remote Mic stopped (direct GATT)")
            }
        }
    }

    private fun closeGattQuiet() {
        val g: BluetoothGatt?
        val ctrl: BluetoothGattCharacteristic?
        synchronized(lock) {
            g = activeGatt
            ctrl = activeCtrlChar
            activeGatt = null
            activeCtrlChar = null
        }
        if (g != null) {
            try {
                if (ctrl != null) {
                    ctrl.value = byteArrayOf(0xA2.toByte(), 0x00)
                    g.writeCharacteristic(ctrl)
                }
            } catch (_: Throwable) {}
            try { g.disconnect() } catch (_: Throwable) {}
            try { g.close() } catch (_: Throwable) {}
        }
    }
}
