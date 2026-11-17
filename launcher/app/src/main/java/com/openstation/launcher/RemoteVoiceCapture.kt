package com.openstation.launcher

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executors

/** Receives 16 kHz IMA ADPCM from the paired Yandex remote while held. */
class RemoteVoiceCapture(private val context: Context, private val getDevice: () -> BluetoothDevice?) {
    private val worker = HandlerThread("RemoteVoice").apply { start() }
    private val handler = Handler(worker.looper)
    private val serviceId = UUID.fromString("65fa9513-e8ca-4efe-b5ba-67b7c44101dc")
    private val controlId = UUID.fromString("20d695c7-1f7b-4d11-afec-c6c5cfae7f52")
    private val voiceId = UUID.fromString("9c98fa55-3de4-4361-8f26-ba1c62c8f222")
    private val cccdId = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var gatt: BluetoothGatt? = null
    private var held = false
    private var micStarted = false
    private var predictor = 0
    private var index = 0
    private var socket: DatagramSocket? = null
    private val sender = Executors.newSingleThreadExecutor()
    private val address = InetAddress.getByName("127.0.0.1")
    private var timeout: Runnable? = null
    private var maxHold: Runnable? = null

    private val steps = intArrayOf(7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767)
    private val indexDelta = intArrayOf(-1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8)

    fun start() = handler.post {
        if (held) return@post
        send("STS:start".toByteArray())
        held = true
        predictor = 0
        index = 0
        micStarted = false
        val device = try { getDevice() } catch (_: Throwable) { null }
        if (device == null) { fail("Пульт не подключён"); return@post }
        val guard = Runnable { if (held && !micStarted) fail("Микрофон пульта не ответил") }
        timeout = guard
        handler.postDelayed(guard, 6000)
        val maxGuard = Runnable { if (held) fail("Превышено время записи с пульта") }
        maxHold = maxGuard
        handler.postDelayed(maxGuard, 120000)
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail("Не удалось подключиться к микрофону пульта")
        } catch (t: Throwable) { fail("Не удалось подключиться к микрофону пульта: ${t.message}") }
    }

    fun stop() = handler.post {
        send("STS:stop".toByteArray())
        if (!held) return@post
        held = false
        timeout?.let { handler.removeCallbacks(it) }
        maxHold?.let { handler.removeCallbacks(it) }
        val conn = gatt
        if (micStarted) stopMic(conn)
        val started = micStarted
        micStarted = false
        send((if (started) "END" else "ERR").toByteArray())
        handler.postDelayed({ closeGatt(conn) }, 500)
    }

    fun close() { handler.post {
        if (held) { held = false; send("ERR".toByteArray()) }
        timeout?.let { handler.removeCallbacks(it) }; maxHold?.let { handler.removeCallbacks(it) }
        val conn = gatt
        if (micStarted) stopMic(conn)
        micStarted = false
        handler.postDelayed({ closeGatt(conn); worker.quitSafely() }, 500)
        sender.execute { socket?.close(); socket = null }; sender.shutdown()
    } }

    private fun stopMic(conn: BluetoothGatt?) {
        val control = conn?.getService(serviceId)?.getCharacteristic(controlId) ?: return
        control.value = byteArrayOf(0xA2.toByte(), 0x00)
        control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try { conn.writeCharacteristic(control) } catch (_: Throwable) {}
    }

    private fun closeGatt(conn: BluetoothGatt?) {
        if (conn == null) return
        if (gatt === conn) gatt = null
        try { conn.disconnect(); conn.close() } catch (_: Throwable) {}
    }
    private fun fail(message: String) {
        Log.w("RemoteVoice", message)
        send("STS:failure".toByteArray())
        if (held) { held = false; send("ERR".toByteArray()) }
        timeout?.let { handler.removeCallbacks(it) }
        maxHold?.let { handler.removeCallbacks(it) }
        val conn = gatt
        if (micStarted) stopMic(conn)
        micStarted = false
        handler.postDelayed({ closeGatt(conn) }, 500)
    }
    private fun send(bytes: ByteArray) {
        try {
            sender.execute {
                try {
                    val s = socket ?: DatagramSocket().also { socket = it }
                    s.send(DatagramPacket(bytes, bytes.size, address, 8092))
                } catch (t: Throwable) { Log.w("RemoteVoice", "Audio transport failed: ${t.message}") }
            }
        } catch (t: Throwable) { Log.w("RemoteVoice", "Audio transport failed: ${t.message}") }
    }
    private fun writeStart(conn: BluetoothGatt) {
        if (!held || conn !== gatt) return
        val control = conn.getService(serviceId)?.getCharacteristic(controlId)
        if (control == null) { fail("Пульт не поддерживает управление микрофоном"); return }
        control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        control.value = byteArrayOf(0xA2.toByte(), 0x01)
        if (!conn.writeCharacteristic(control)) fail("Не удалось включить микрофон пульта")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(conn: BluetoothGatt, status: Int, newState: Int) { handler.post {
            if (conn !== gatt) { closeGatt(conn); return@post }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS && held) {
                send("STS:connected".toByteArray())
                if (!conn.discoverServices()) fail("Не удалось прочитать службы пульта")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && held) fail("Пульт отключился")
        } }
        override fun onServicesDiscovered(conn: BluetoothGatt, status: Int) { handler.post {
            if (conn !== gatt || !held) return@post
            val voice = conn.getService(serviceId)?.getCharacteristic(voiceId)
            send((if (voice == null) "STS:no_voice_service" else "STS:voice_service").toByteArray())
            val desc = voice?.getDescriptor(cccdId)
            if (status != BluetoothGatt.GATT_SUCCESS || voice == null || desc == null || !conn.setCharacteristicNotification(voice, true)) {
                fail("На пульте не найдена служба микрофона"); return@post
            }
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!conn.writeDescriptor(desc)) fail("Не удалось включить поток микрофона")
        } }
        override fun onDescriptorWrite(conn: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { handler.post {
            if (conn !== gatt || !held || descriptor.uuid != cccdId) return@post
            if (status == BluetoothGatt.GATT_SUCCESS) { send("STS:subscribed".toByteArray()); writeStart(conn) } else fail("Пульт не подтвердил подписку на микрофон")
        } }
        override fun onCharacteristicWrite(conn: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { handler.post {
            if (conn !== gatt || !held || characteristic.uuid != controlId) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Пульт не включил микрофон"); return@post }
            micStarted = true
            send("STS:mic_started".toByteArray())
            timeout?.let { handler.removeCallbacks(it) }
        } }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(conn: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val packet = characteristic.value?.clone() ?: return
            handler.post {
                if (conn !== gatt || !held || !micStarted || characteristic.uuid != voiceId || packet.size <= 6 || packet[2] != 0xA5.toByte()) return@post
                val pcm = ByteArray(4 + (packet.size - 6) * 4)
                pcm[0] = 'P'.code.toByte(); pcm[1] = 'C'.code.toByte(); pcm[2] = 'M'.code.toByte(); pcm[3] = ':'.code.toByte()
                var pos = 4
                for (i in 6 until packet.size) {
                    val b = packet[i].toInt() and 0xff
                    for (half in 0..1) {
                        val nibble = if (half == 0) b shr 4 else b and 15
                        val step = steps[index]
                        var diff = step shr 3
                        if (nibble and 4 != 0) diff += step
                        if (nibble and 2 != 0) diff += step shr 1
                        if (nibble and 1 != 0) diff += step shr 2
                        predictor = (predictor + if (nibble and 8 != 0) -diff else diff).coerceIn(-32768, 32767)
                        index = (index + indexDelta[nibble]).coerceIn(0, 88)
                        pcm[pos++] = predictor.toByte(); pcm[pos++] = (predictor shr 8).toByte()
                    }
                }
                send(pcm)
            }
        }
    }
}
