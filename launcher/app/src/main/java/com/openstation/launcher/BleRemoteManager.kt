package com.openstation.launcher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Manages Yandex Station BLE Remote (HID 0x1812) discovery, LE-transport pairing,
 * honest live connection verification, unpairing, and Immediate Alert (0x1802) buzzer wakeup.
 */
class BleRemoteManager(private val context: Context) {

    companion object {
        private const val TAG = "BleRemoteMgr"
        private const val PREF_NAME = "openstation_remote_prefs"
        private const val KEY_REMOTE_MAC = "yndx_remote_mac"
        private const val KEY_REMOTE_NAME = "yndx_remote_name"
        private const val PROFILE_HID_HOST = 4
        private const val TRANSPORT_LE = 2

        private val UUID_IMMEDIATE_ALERT = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
        private val UUID_ALERT_LEVEL = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")
        private val UUID_LINK_LOSS = UUID.fromString("00001803-0000-1000-8000-00805f9b34fb")
        private val UUID_BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val UUID_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }

    data class RemoteStatus(
        val bonded: Boolean,
        val connected: Boolean,
        val name: String,
        val address: String,
        val batteryPct: Int = -1,
        val scanState: String = "idle",
        val scanDetail: String = ""
    )

    data class DiscoveredRemote(
        val name: String,
        val address: String,
        val rssi: Int,
        val yandex: Boolean,
        val bonded: Boolean
    )

    var onStateChanged: ((JSONObject) -> Unit)? = null
    var onActionResult: ((kind: String, ok: Boolean, message: String, reqId: String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scanning = AtomicBoolean(false)
    private var activeScanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var genericPickRunnable: Runnable? = null
    private var bondReceiver: BroadcastReceiver? = null
    private var discoveryReceiver: BroadcastReceiver? = null
    private var reconnectActive = false

    @Volatile private var hidHostProxy: BluetoothProfile? = null
    @Volatile private var hidConnectedAddress: String? = null
    @Volatile private var lastBatteryPct: Int = -1
    @Volatile private var currentScanState: String = "idle"
    @Volatile private var currentScanDetail: String = ""
    @Volatile private var lastInputScanMs: Long = 0L
    @Volatile private var cachedInputLines: String = ""

    private val candidatesLock = Any()
    private val discoveredMap = LinkedHashMap<String, DiscoveredRemote>()

    private var hidReceiverRegistered = false
    private val hidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED" -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (!isTargetOrSaved(device)) return
                    when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            hidConnectedAddress = device.address
                            lastInputScanMs = 0L
                            setScanProgress("connected", "Пульт ${safeName(device)} подключён")
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (hidConnectedAddress == device.address) hidConnectedAddress = null
                            lastInputScanMs = 0L
                            if (currentScanState == "connected") {
                                setScanProgress("idle", "Пульт отключён")
                            } else {
                                notifyStateChanged()
                            }
                            scheduleReconnect(5000L)
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                        hidConnectedAddress = null
                        lastInputScanMs = 0L
                        notifyStateChanged()
                    }
                }
            }
        }
    }

    private var reconnectDelay = 5000L
    private val hidConnecting = AtomicBoolean(false)
    private val reconnectTask = object : Runnable {
        override fun run() {
            if (!reconnectActive) return
            val adapter = getBtAdapter()
            if (adapter == null || !adapter.isEnabled) {
                scheduleReconnect(30000L)
                return
            }
            if (scanning.get() || bondReceiver != null) {
                scheduleReconnect(30000L)
                return
            }
            val device = try { findTargetDevice() } catch (_: Throwable) { null }
            if (device == null || device.bondState != BluetoothDevice.BOND_BONDED) {
                // Do not run periodic background LE scans when no remote is bonded; explicit
                // user/WebUI pairing starts scanning on demand. Re-check bonded state gently.
                scheduleReconnect(60000L)
                return
            }
            if (hasLiveHidConnection(device)) {
                hidConnectedAddress = device.address
                notifyStateChanged()
                scheduleReconnect(30000L)
                return
            }
            connectHid(adapter, device) { state, _ ->
                if (state == "connected") reconnectDelay = 5000L
                else reconnectDelay = (reconnectDelay * 2).coerceAtMost(300000L)
                scheduleReconnect(if (state == "connected") 30000L else reconnectDelay)
            }
        }
    }

    private val hidProxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == PROFILE_HID_HOST) {
                hidHostProxy = proxy
                val target = findTargetDevice()
                if (target != null && proxy.getConnectionState(target) == BluetoothProfile.STATE_CONNECTED) {
                    hidConnectedAddress = target.address
                } else {
                    hidConnectedAddress = null
                }
                notifyStateChanged()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == PROFILE_HID_HOST) {
                hidHostProxy = null
            }
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun getBtAdapter(): BluetoothAdapter? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private fun safeName(device: BluetoothDevice?): String {
        if (device == null) return "Yandex Remote"
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: prefs.getString(KEY_REMOTE_NAME, "Yandex Remote") ?: "Yandex Remote"
        } catch (_: SecurityException) {
            prefs.getString(KEY_REMOTE_NAME, "Yandex Remote") ?: "Yandex Remote"
        }
    }

    private fun isTargetOrSaved(device: BluetoothDevice): Boolean {
        val saved = prefs.getString(KEY_REMOTE_MAC, "") ?: ""
        if (saved.isNotEmpty() && device.address.equals(saved, ignoreCase = true)) return true
        val n = try { device.name } catch (_: SecurityException) { null }
        return isYandexRemoteName(n)
    }

    fun isYandexRemoteName(name: String?): Boolean = RemoteIdentity.isYandexRemoteName(name)

    private fun isLocationEnabledForBle(): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            lm?.isLocationEnabled == true
        } catch (_: Throwable) {
            true
        }
    }

    private fun ensureHidProxyBound() {
        if (hidHostProxy != null) return
        try {
            getBtAdapter()?.getProfileProxy(context, hidProxyListener, PROFILE_HID_HOST)
        } catch (_: Throwable) {}
    }

    private fun readInputDevicesText(): String {
        val now = SystemClock.elapsedRealtime()
        if ((now - lastInputScanMs) < 450L && cachedInputLines.isNotEmpty()) {
            return cachedInputLines
        }
        val txt = try {
            File("/proc/bus/input/devices").takeIf { it.canRead() }?.readText() ?: ""
        } catch (_: Throwable) {
            ""
        }
        cachedInputLines = txt
        lastInputScanMs = now
        return txt
    }

    private fun hasLiveHidConnection(device: BluetoothDevice): Boolean {
        ensureHidProxyBound()
        val proxy = hidHostProxy
        if (proxy != null) {
            val stateConnected = try {
                proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
            } catch (_: Throwable) {
                false
            }
            if (!stateConnected) {
                if (hidConnectedAddress == device.address) hidConnectedAddress = null
                return false
            }
            hidConnectedAddress = device.address
            return true
        }
        // Fallback before HidHost proxy binds: require active kernel input device OR explicit broadcast + input entry
        val inputs = readInputDevicesText()
        if (inputs.isNotEmpty()) {
            val macLower = device.address.lowercase()
            val dName = try { device.name ?: "" } catch (_: SecurityException) { "" }
            val hasInput = inputs.contains(macLower, ignoreCase = true) ||
                (dName.isNotBlank() && inputs.contains(dName, ignoreCase = true))
            if (hasInput) {
                hidConnectedAddress = device.address
                return true
            }
        }
        return false
    }

    fun startReconnect() {
        handler.post {
            reconnectActive = true
            ensureHidProxyBound()
            if (!hidReceiverRegistered) {
                try {
                    val filter = IntentFilter().apply {
                        addAction("android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED")
                        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    }
                    context.registerReceiver(hidReceiver, filter)
                    hidReceiverRegistered = true
                } catch (t: Throwable) {
                    Log.w(TAG, "Unable to observe remote connection state", t)
                }
            }
            reconnectDelay = 5000L
            scheduleReconnect(5000L)
            notifyStateChanged()
        }
    }

    fun stopReconnect() {
        handler.post {
            reconnectActive = false
            handler.removeCallbacks(reconnectTask)
            stopScan()
            unregisterBondReceiver()
            unregisterDiscoveryReceiver()
            if (hidReceiverRegistered) {
                try { context.unregisterReceiver(hidReceiver) } catch (_: Throwable) {}
                hidReceiverRegistered = false
            }
            hidHostProxy?.let {
                try { getBtAdapter()?.closeProfileProxy(PROFILE_HID_HOST, it) } catch (_: Throwable) {}
            }
            hidHostProxy = null
            hidConnectedAddress = null
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        if (!reconnectActive) return
        handler.removeCallbacks(reconnectTask)
        handler.postDelayed(reconnectTask, delayMs)
    }

    fun getRemoteStatus(): RemoteStatus {
        return try {
            val device = findTargetDevice()
            if (device == null || device.bondState != BluetoothDevice.BOND_BONDED) {
                RemoteStatus(
                    bonded = false,
                    connected = false,
                    name = "",
                    address = "",
                    batteryPct = -1,
                    scanState = currentScanState,
                    scanDetail = currentScanDetail
                )
            } else {
                val isLive = hasLiveHidConnection(device)
                RemoteStatus(
                    bonded = true,
                    connected = isLive,
                    name = safeName(device),
                    address = device.address,
                    batteryPct = if (isLive) lastBatteryPct else -1,
                    scanState = currentScanState,
                    scanDetail = currentScanDetail
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getRemoteStatus failed: ${t.message}")
            RemoteStatus(false, false, "", "", -1, currentScanState, currentScanDetail)
        }
    }

    fun getDiscoveredCandidates(): List<DiscoveredRemote> {
        synchronized(candidatesLock) {
            return discoveredMap.values.sortedByDescending { (if (it.yandex) 200 else 0) + it.rssi }
        }
    }

    fun toJsonObject(): JSONObject {
        val st = getRemoteStatus()
        val obj = JSONObject()
        obj.put("bonded", st.bonded)
        obj.put("connected", st.connected)
        obj.put("name", st.name)
        obj.put("address", st.address)
        obj.put("batteryPct", st.batteryPct)
        obj.put("scanState", st.scanState)
        obj.put("scanDetail", st.scanDetail)
        obj.put("scanning", scanning.get())
        val arr = JSONArray()
        for (c in getDiscoveredCandidates()) {
            val item = JSONObject()
            item.put("name", c.name)
            item.put("address", c.address)
            item.put("rssi", c.rssi)
            item.put("yandex", c.yandex)
            item.put("bonded", c.bonded)
            arr.put(item)
        }
        obj.put("candidates", arr)
        return obj
    }

    private fun setScanProgress(state: String, detail: String) {
        currentScanState = state
        currentScanDetail = detail
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        try {
            onStateChanged?.invoke(toJsonObject())
        } catch (_: Throwable) {}
    }

    private fun findTargetDevice(): BluetoothDevice? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return null
        val savedMac = prefs.getString(KEY_REMOTE_MAC, "") ?: ""

        if (savedMac.isNotEmpty()) {
            try {
                adapter.getRemoteDevice(savedMac).takeIf { it.bondState == BluetoothDevice.BOND_BONDED }
                    ?.let { return it }
            } catch (_: IllegalArgumentException) {}
        }
        try {
            adapter.bondedDevices?.firstOrNull { isYandexRemoteName(it.name) }?.let { return it }
        } catch (_: Throwable) {}
        return null
    }

    fun startAutoScanAndPair(
        durationMs: Long = 60000L,
        forceRescan: Boolean = false,
        targetAddress: String? = null,
        reqId: String = "",
        onStatus: (state: String, detail: String) -> Unit
    ) {
        startAutoScanAndPairInternal(durationMs, forceRescan, targetAddress, reqId, onStatus, 0)
    }

    private fun startAutoScanAndPairInternal(
        durationMs: Long,
        forceRescan: Boolean,
        targetAddress: String?,
        reqId: String,
        onStatus: (state: String, detail: String) -> Unit,
        enableAttempts: Int
    ) {
        handler.post {
            fun report(st: String, det: String) {
                setScanProgress(st, det)
                onStatus(st, det)
                if (st == "connected" || st == "error" || st == "not_found") {
                    onActionResult?.invoke("pair_remote", st == "connected", det, reqId)
                }
            }

            try {
                val adapter = getBtAdapter()
                if (adapter == null) {
                    report("error", "Bluetooth-адаптер недоступен")
                    return@post
                }
                if (!adapter.isEnabled) {
                    if (enableAttempts >= 10) {
                        report("error", "Bluetooth не включился. Проверьте настройки и повторите поиск.")
                        return@post
                    }
                    if (enableAttempts == 0 && adapter.state != BluetoothAdapter.STATE_TURNING_ON && !adapter.enable()) {
                        report("error", "Не удалось включить Bluetooth для поиска пульта.")
                        return@post
                    }
                    report("searching", "Включаю Bluetooth для поиска пульта...")
                    handler.postDelayed({
                        startAutoScanAndPairInternal(durationMs, forceRescan, targetAddress, reqId, onStatus, enableAttempts + 1)
                    }, 1200L)
                    return@post
                }

                // Explicit target chosen by user from TV/Web UI candidate list
                if (!targetAddress.isNullOrBlank()) {
                    val dev = try { adapter.getRemoteDevice(targetAddress.trim().uppercase()) } catch (_: Throwable) { null }
                    if (dev == null) {
                        report("error", "Некорректный MAC-адрес пульта: $targetAddress")
                        return@post
                    }
                    stopScan()
                    val label = safeName(dev)
                    bondAndConnect(dev, label, forceRebond = forceRescan, onStatus = ::report)
                    return@post
                }

                // Only short-circuit when NOT forcing rescan AND the bonded remote is genuinely live-connected right now
                val existing = findTargetDevice()
                if (!forceRescan && existing != null && existing.bondState == BluetoothDevice.BOND_BONDED && hasLiveHidConnection(existing)) {
                    report("connected", "Пульт ${safeName(existing)} уже подключён.")
                    connectAndProbeGatt(existing, buzz = false) { _, _ -> notifyStateChanged() }
                    return@post
                }

                // If an old bonded remote exists but is disconnected, try waking HID in background
                // while STILL starting active LE scan so a newly reset/replacement remote is never skipped!
                if (!forceRescan && existing != null && existing.bondState == BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "Bonded remote ${existing.address} is currently offline; probing HID while scanning LE...")
                    connectHid(adapter, existing) { st, det ->
                        if (st == "connected") {
                            stopScan()
                            report("connected", det)
                        }
                    }
                }

                startLeScanInternal(adapter, durationMs, ::report, ScanSettings.SCAN_MODE_LOW_LATENCY, background = false)
            } catch (t: Throwable) {
                Log.w(TAG, "startAutoScanAndPair error", t)
                report("error", "Ошибка поиска BLE-пульта: ${t.message}")
            }
        }
    }

    private fun startLeScanInternal(
        adapter: BluetoothAdapter,
        durationMs: Long,
        onStatus: (state: String, detail: String) -> Unit,
        scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
        background: Boolean = false
    ) {
        stopScan(resetSearchingState = false)
        synchronized(candidatesLock) {
            if (!background) discoveredMap.clear()
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            if (!background) {
                startClassicDiscoveryFallback(adapter, durationMs, onStatus)
            } else {
                onStatus("error", "BLE-сканер не готов")
            }
            return
        }

        scanning.set(true)
        if (!background) {
            onStatus("searching", "Ищу пульт по Bluetooth LE. Удерживайте «Домой» и «−» на пульте ~5 сек.")
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                val rawName = try { dev.name } catch (_: SecurityException) { null }
                    ?: result.scanRecord?.deviceName
                    ?: ""
                val svcUuids = result.scanRecord?.serviceUuids?.mapNotNull { it?.uuid } ?: emptyList()
                val savedMac = prefs.getString(KEY_REMOTE_MAC, "") ?: ""
                val matchesSaved = savedMac.isNotEmpty() && dev.address.equals(savedMac, ignoreCase = true)
                if (!RemoteIdentity.isBleRemoteCandidate(rawName, svcUuids) && !matchesSaved) return

                val isYandex = isYandexRemoteName(rawName) || svcUuids.contains(RemoteIdentity.UUID_OMNI_VOICE_SERVICE)
                val label = rawName.ifBlank { if (isYandex) "Yandex Remote" else "BLE HID Remote" }
                val bonded = try { dev.bondState == BluetoothDevice.BOND_BONDED } catch (_: Throwable) { false }

                val isNew = synchronized(candidatesLock) {
                    val prev = discoveredMap[dev.address]
                    discoveredMap[dev.address] = DiscoveredRemote(
                        name = label,
                        address = dev.address,
                        rssi = result.rssi,
                        yandex = isYandex,
                        bonded = bonded
                    )
                    prev == null
                }
                if (isNew) {
                    Log.i(TAG, "Discovered BLE remote candidate: $label [${dev.address}] rssi=${result.rssi} yandex=$isYandex uuids=$svcUuids")
                    notifyStateChanged()
                }

                // Prefer immediate auto-pair for genuine Yandex remote (or remembered MAC).
                // For generic BLE HID 0x1812 remotes, wait briefly (2.5s) in case Yandex appears, then connect.
                if (isYandex || matchesSaved) {
                    if (!scanning.compareAndSet(true, false)) return
                    stopScan()
                    onStatus("found", "Найден пульт $label (${dev.address}), подключаю...")
                    // Since the remote is actively advertising in pairing mode, remove any stale bond first
                    bondAndConnect(dev, label, forceRebond = true, onStatus = onStatus)
                } else if (!background && genericPickRunnable == null) {
                    val pick = Runnable {
                        genericPickRunnable = null
                        if (!scanning.get()) return@Runnable
                        val best = getDiscoveredCandidates().firstOrNull() ?: return@Runnable
                        val targetDev = try { adapter.getRemoteDevice(best.address) } catch (_: Throwable) { null } ?: return@Runnable
                        if (!scanning.compareAndSet(true, false)) return@Runnable
                        stopScan()
                        onStatus("found", "Найден BLE HID пульт ${best.name} (${best.address}), подключаю...")
                        bondAndConnect(targetDev, best.name, forceRebond = true, onStatus = onStatus)
                    }
                    genericPickRunnable = pick
                    handler.postDelayed(pick, 2500L)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                if (scanning.compareAndSet(true, false)) {
                    stopScan()
                    if (!background) {
                        Log.w(TAG, "LE scan failed ($errorCode), trying classic/dual discovery fallback...")
                        startClassicDiscoveryFallback(adapter, durationMs, onStatus)
                    } else {
                        onStatus("error", "Ошибка BLE-сканера (код $errorCode)")
                    }
                }
            }
        }
        activeScanCallback = cb

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()
        try {
            scanner.startScan(null, settings, cb)
        } catch (t: Throwable) {
            scanning.set(false)
            activeScanCallback = null
            onStatus("error", "Нет разрешения на BLE-поиск (${t.message})")
            return
        }

        // Also mirror Android TV discovery on active scans if location might suppress LE results
        if (!background && !isLocationEnabledForBle()) {
            Log.w(TAG, "Location mode is disabled; starting discovery fallback alongside LE scan")
            startClassicDiscoveryFallback(adapter, durationMs, onStatus, keepLeScan = true)
        }

        val timeout = Runnable {
            if (scanning.compareAndSet(true, false)) {
                stopScan()
                val candidates = getDiscoveredCandidates()
                if (candidates.isNotEmpty() && !background) {
                    onStatus("not_found", "Найдены HID-устройства (${candidates.size}) — выберите нужное в списке или повторите поиск.")
                } else {
                    onStatus("not_found", "Пульт не найден. Удерживайте «Домой» и «−» на пульте ~5 сек и повторите поиск.")
                }
            }
        }
        scanTimeoutRunnable = timeout
        handler.postDelayed(timeout, durationMs.coerceIn(5000L, 60000L))
    }

    private fun startClassicDiscoveryFallback(
        adapter: BluetoothAdapter,
        durationMs: Long,
        onStatus: (String, String) -> Unit,
        keepLeScan: Boolean = false
    ) {
        unregisterDiscoveryReceiver()
        val br = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val name = try { dev.name } catch (_: SecurityException) { null } ?: ""
                val isYandex = isYandexRemoteName(name)
                if (!isYandex && dev.type != BluetoothDevice.DEVICE_TYPE_LE && dev.type != BluetoothDevice.DEVICE_TYPE_DUAL) return
                val label = name.ifBlank { dev.address }
                synchronized(candidatesLock) {
                    discoveredMap[dev.address] = DiscoveredRemote(
                        name = label,
                        address = dev.address,
                        rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (-70).toShort()).toInt(),
                        yandex = isYandex,
                        bonded = dev.bondState == BluetoothDevice.BOND_BONDED
                    )
                }
                notifyStateChanged()
                if (isYandex) {
                    unregisterDiscoveryReceiver()
                    try { adapter.cancelDiscovery() } catch (_: Throwable) {}
                    if (keepLeScan) stopScan()
                    onStatus("found", "Найден пульт $label (${dev.address}), подключаю...")
                    bondAndConnect(dev, label, forceRebond = true, onStatus = onStatus)
                }
            }
        }
        discoveryReceiver = br
        try {
            context.registerReceiver(br, IntentFilter(BluetoothDevice.ACTION_FOUND))
            adapter.startDiscovery()
            if (!keepLeScan) {
                handler.postDelayed({
                    unregisterDiscoveryReceiver()
                    try { adapter.cancelDiscovery() } catch (_: Throwable) {}
                }, durationMs.coerceIn(5000L, 20000L))
            }
        } catch (_: Throwable) {
            unregisterDiscoveryReceiver()
        }
    }

    private fun invokeCreateBondLe(device: BluetoothDevice): Boolean {
        return try {
            val m = BluetoothDevice::class.java.getMethod("createBond", Int::class.javaPrimitiveType)
            (m.invoke(device, TRANSPORT_LE) as? Boolean) ?: false
        } catch (_: Throwable) {
            try {
                device.createBond()
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun invokeRemoveBond(device: BluetoothDevice): Boolean {
        return try {
            val m = BluetoothDevice::class.java.getMethod("removeBond")
            (m.invoke(device) as? Boolean) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun bondAndConnect(
        device: BluetoothDevice,
        label: String,
        forceRebond: Boolean = false,
        onStatus: (state: String, detail: String) -> Unit
    ) {
        val adapter = getBtAdapter()
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            if (!forceRebond || hasLiveHidConnection(device)) {
                if (adapter == null) onStatus("error", "Bluetooth-адаптер недоступен")
                else connectHid(adapter, device, onStatus)
                return
            }
            // Remote re-advertised in pairing mode while station kept stale keys -> wipe stale bond first
            onStatus("pairing", "Сбрасываю старую привязку $label для повторного сопряжения...")
            invokeRemoveBond(device)
            handler.postDelayed({
                bondFreshDevice(device, label, onStatus)
            }, 600L)
            return
        }
        bondFreshDevice(device, label, onStatus)
    }

    private fun bondFreshDevice(
        device: BluetoothDevice,
        label: String,
        onStatus: (state: String, detail: String) -> Unit
    ) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            val adapter = getBtAdapter()
            if (adapter == null) onStatus("error", "Bluetooth-адаптер недоступен")
            else connectHid(adapter, device, onStatus)
            return
        }

        unregisterBondReceiver()
        val guard = Runnable {
            unregisterBondReceiver()
            onStatus("error", "Таймаут сопряжения с $label. Убедитесь, что пульт мигает, и повторите.")
        }
        val br = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (dev.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> {
                        handler.removeCallbacks(guard)
                        unregisterBondReceiver()
                        prefs.edit().putString(KEY_REMOTE_MAC, dev.address).putString(KEY_REMOTE_NAME, label).apply()
                        onStatus("pairing", "Пульт $label сопряжён, подключаю кнопки...")
                        val adapter = getBtAdapter()
                        if (adapter == null) onStatus("error", "Bluetooth-адаптер недоступен")
                        else {
                            // Short settle delay helps Fluoride finish GATT HID service discovery after SMP
                            handler.postDelayed({ connectHid(adapter, dev, onStatus) }, 350L)
                        }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        handler.removeCallbacks(guard)
                        unregisterBondReceiver()
                        onStatus("error", "Не удалось завершить сопряжение с $label (ключ отклонен)")
                    }
                }
            }
        }
        bondReceiver = br
        context.registerReceiver(br, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        handler.postDelayed(guard, 22000L)

        try {
            onStatus("pairing", "Сопрягаю BLE-пульт $label...")
            if (!invokeCreateBondLe(device)) {
                handler.removeCallbacks(guard)
                unregisterBondReceiver()
                onStatus("error", "Не удалось начать LE-сопряжение с $label")
            }
        } catch (t: Throwable) {
            handler.removeCallbacks(guard)
            unregisterBondReceiver()
            onStatus("error", "Ошибка createBond: ${t.message}")
        }
    }

    private fun connectHid(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        onStatus: (String, String) -> Unit
    ) {
        if (!hidConnecting.compareAndSet(false, true)) {
            onStatus("pairing", "Подключение пульта уже выполняется.")
            return
        }
        val finished = AtomicBoolean(false)
        var openedLocalProxy: BluetoothProfile? = null
        fun finish(state: String, detail: String) {
            if (!finished.compareAndSet(false, true)) return
            hidConnecting.set(false)
            if (state == "connected") {
                hidConnectedAddress = device.address
                prefs.edit().putString(KEY_REMOTE_MAC, device.address)
                    .putString(KEY_REMOTE_NAME, safeName(device)).apply()
            } else if (hidConnectedAddress == device.address) {
                hidConnectedAddress = null
            }
            openedLocalProxy?.let {
                if (it !== hidHostProxy) {
                    try { adapter.closeProfileProxy(PROFILE_HID_HOST, it) } catch (_: Throwable) {}
                }
            }
            onStatus(state, detail)
        }

        val timeout = Runnable {
            finish("error", "Пульт сопряжён, но не отвечает по HID. Нажмите любую кнопку на пульте.")
        }

        fun runWithProxy(proxy: BluetoothProfile) {
            try {
                if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                    handler.removeCallbacks(timeout)
                    finish("connected", "Пульт ${safeName(device)} подключён. Кнопки активны.")
                    return
                }
                val connectMethod = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                val initiated = (connectMethod.invoke(proxy, device) as? Boolean) ?: false
                if (!initiated) {
                    handler.removeCallbacks(timeout)
                    finish("error", "Android не смог инициировать HID-соединение с пультом.")
                    return
                }
                val poll = object : Runnable {
                    override fun run() {
                        if (finished.get()) return
                        try {
                            if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                                handler.removeCallbacks(timeout)
                                finish("connected", "Пульт ${safeName(device)} подключён. Кнопки активны.")
                            } else {
                                handler.postDelayed(this, 450L)
                            }
                        } catch (t: Throwable) {
                            handler.removeCallbacks(timeout)
                            finish("error", "Не удалось проверить состояние пульта: ${t.message}")
                        }
                    }
                }
                handler.post(poll)
            } catch (t: Throwable) {
                handler.removeCallbacks(timeout)
                finish("error", "Не удалось подключить HID-профиль: ${t.message}")
            }
        }

        handler.postDelayed(timeout, 12000L)
        val cached = hidHostProxy
        if (cached != null) {
            runWithProxy(cached)
            return
        }

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                if (finished.get()) {
                    try { adapter.closeProfileProxy(PROFILE_HID_HOST, proxy) } catch (_: Throwable) {}
                    return
                }
                openedLocalProxy = proxy
                hidHostProxy = proxy
                runWithProxy(proxy)
            }

            override fun onServiceDisconnected(p: Int) {
                handler.removeCallbacks(timeout)
                finish("error", "Служба HID отключилась.")
            }
        }
        try {
            if (!adapter.getProfileProxy(context, listener, PROFILE_HID_HOST)) {
                handler.removeCallbacks(timeout)
                finish("error", "Служба HID-профиля недоступна.")
            }
        } catch (t: Throwable) {
            handler.removeCallbacks(timeout)
            finish("error", "Ошибка открытия HID-профиля: ${t.message}")
        }
    }

    fun forgetBondedRemote(reqId: String = "", onDone: ((Boolean, String) -> Unit)? = null) {
        handler.post {
            stopScan()
            unregisterBondReceiver()
            val adapter = getBtAdapter()
            var removedCount = 0
            val savedMac = prefs.getString(KEY_REMOTE_MAC, "") ?: ""
            try {
                adapter?.bondedDevices?.forEach { dev ->
                    val name = try { dev.name } catch (_: SecurityException) { null }
                    if (isYandexRemoteName(name) || (savedMac.isNotEmpty() && dev.address.equals(savedMac, ignoreCase = true))) {
                        if (invokeRemoveBond(dev)) removedCount++
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "forgetBondedRemote error: ${t.message}")
            }
            prefs.edit().remove(KEY_REMOTE_MAC).remove(KEY_REMOTE_NAME).apply()
            hidConnectedAddress = null
            lastBatteryPct = -1
            lastInputScanMs = 0L
            synchronized(candidatesLock) { discoveredMap.clear() }
            val msg = if (removedCount > 0) "Привязка пульта удалена. Можно подключить новый." else "Сохранённый пульт очищен."
            setScanProgress("idle", msg)
            onActionResult?.invoke("forget_remote", true, msg, reqId)
            onDone?.invoke(true, msg)
        }
    }

    fun triggerBuzzer(reqId: String = "", onResult: (ok: Boolean, message: String) -> Unit) {
        thread(name = "BleRemoteBuzzer", isDaemon = true) {
            val st = getRemoteStatus()
            val dev = findTargetDevice()
            if (dev == null || !st.bonded) {
                val msg = "Пульт не привязан и не подключён. Сначала выполните «Автопоиск пульта»."
                onActionResult?.invoke("find_remote", false, msg, reqId)
                onResult(false, msg)
                return@thread
            }
            connectAndProbeGatt(dev, buzz = true) { ok, msg ->
                notifyStateChanged()
                onActionResult?.invoke("find_remote", ok, msg, reqId)
                onResult(ok, msg)
            }
        }
    }

    private fun connectAndProbeGatt(
        device: BluetoothDevice,
        buzz: Boolean,
        onDone: (Boolean, String) -> Unit
    ) {
        val finished = AtomicBoolean(false)
        handler.post {
            var gattRef: BluetoothGatt? = null
            val guard = Runnable {
                if (finished.compareAndSet(false, true)) {
                    try { gattRef?.disconnect(); gattRef?.close() } catch (_: Throwable) {}
                    val msg = if (buzz) {
                        "Пульт не отвечает по BLE (вне зоны, разряжен или не подключён — нажмите кнопку на пульте)."
                    } else {
                        "Таймаут опроса GATT"
                    }
                    onDone(false, msg)
                }
            }
            handler.postDelayed(guard, 7200L)

            val gattCb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "GATT connected to ${device.address}, discovering services...")
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !finished.get()) {
                        if (finished.compareAndSet(false, true)) {
                            handler.removeCallbacks(guard)
                            try { gatt.close() } catch (_: Throwable) {}
                            onDone(false, "Пульт не вышел на связь по BLE (status=$status)")
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        if (finished.compareAndSet(false, true)) {
                            handler.removeCallbacks(guard)
                            try { gatt.close() } catch (_: Throwable) {}
                            onDone(false, "Ошибка GATT discoverServices ($status)")
                        }
                        return
                    }

                    val batChar = gatt.getService(UUID_BATTERY_SERVICE)?.getCharacteristic(UUID_BATTERY_LEVEL)
                    if (!buzz) {
                        if (batChar != null) gatt.readCharacteristic(batChar)
                        if (finished.compareAndSet(false, true)) {
                            handler.removeCallbacks(guard)
                            handler.postDelayed({ try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {} }, 600L)
                            onDone(true, "Пульт ${safeName(device)} подключён!")
                        }
                        return
                    }

                    val alertSvc = gatt.getService(UUID_IMMEDIATE_ALERT) ?: gatt.getService(UUID_LINK_LOSS)
                    var alertChar: BluetoothGattCharacteristic? = alertSvc?.getCharacteristic(UUID_ALERT_LEVEL)
                    if (alertChar == null) {
                        for (svc in gatt.services) {
                            for (ch in svc.characteristics) {
                                val props = ch.properties
                                val isWrite = (props and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                                if (isWrite && ch.uuid == UUID_ALERT_LEVEL) {
                                    alertChar = ch
                                    break
                                }
                            }
                            if (alertChar != null) break
                        }
                    }

                    if (alertChar != null) {
                        alertChar.value = byteArrayOf(0x02) // High Alert buzzer
                        alertChar.writeType = if ((alertChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        } else {
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        }
                        val ok = gatt.writeCharacteristic(alertChar)
                        if (alertChar.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                            if (finished.compareAndSet(false, true)) {
                                handler.removeCallbacks(guard)
                                handler.postDelayed({ try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {} }, 1800L)
                                onDone(ok, if (ok) "Пульт пищит! (Сигнал отправлен)" else "Не удалось записать команду зуммера")
                            }
                        }
                    } else {
                        // Read battery cleanly since no buzzer char exists, confirming active GATT link
                        if (batChar != null) gatt.readCharacteristic(batChar)
                        if (finished.compareAndSet(false, true)) {
                            handler.removeCallbacks(guard)
                            handler.postDelayed({ try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {} }, 900L)
                            onDone(true, "Пульт на связи (без пищащего зуммера 0x1802 на этой ревизии пульта)")
                        }
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == UUID_BATTERY_LEVEL) {
                        val b = characteristic.value?.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                        if (b in 0..100) {
                            lastBatteryPct = b
                            notifyStateChanged()
                        }
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (buzz && finished.compareAndSet(false, true)) {
                        handler.removeCallbacks(guard)
                        val ok = (status == BluetoothGatt.GATT_SUCCESS)
                        handler.postDelayed({ try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {} }, 1500L)
                        onDone(ok, if (ok) "Пульт пищит! (Сигнал отправлен)" else "Ошибка команды зуммера ($status)")
                    }
                }
            }

            gattRef = try {
                device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            } catch (t: Throwable) {
                if (finished.compareAndSet(false, true)) {
                    handler.removeCallbacks(guard)
                    onDone(false, "connectGatt ошибка: ${t.message}")
                }
                null
            }
        }
    }

    fun stopScan(resetSearchingState: Boolean = true) {
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        genericPickRunnable?.let { handler.removeCallbacks(it) }
        genericPickRunnable = null
        unregisterDiscoveryReceiver()
        try { getBtAdapter()?.cancelDiscovery() } catch (_: Throwable) {}
        val cb = activeScanCallback
        activeScanCallback = null
        scanning.set(false)
        if (cb != null) {
            try {
                getBtAdapter()?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: Throwable) {}
        }
        if (resetSearchingState) {
            resolveStoppedScanState(currentScanState, getRemoteStatus().connected)?.let { (st, det) ->
                setScanProgress(st, det)
            }
        }
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Throwable) {}
        }
        bondReceiver = null
    }

    private fun unregisterDiscoveryReceiver() {
        discoveryReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Throwable) {}
        }
        discoveryReceiver = null
    }

    fun release() {
        handler.post {
            stopScan()
            unregisterBondReceiver()
            unregisterDiscoveryReceiver()
            hidHostProxy?.let {
                try { getBtAdapter()?.closeProfileProxy(PROFILE_HID_HOST, it) } catch (_: Throwable) {}
            }
            hidHostProxy = null
        }
    }
}

internal fun resolveStoppedScanState(previousState: String, connected: Boolean): Pair<String, String>? {
    if (previousState != "searching") return null
    return if (connected) {
        "connected" to "Пульт подключён"
    } else {
        "idle" to ""
    }
}
