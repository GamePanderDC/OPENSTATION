package com.openstation.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Dedicated pairing screen for BLE remotes (Yandex Remote & BLE HID 0x1812).
 * Shows real live connection state (never faking bonded-but-offline as connected),
 * discovers advertising BLE remotes so the user can press OK to pair,
 * and allows forgetting a stale bonded remote.
 */
class RemoteSetupActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scanBadgeTv: TextView
    private lateinit var scanDot: View
    private lateinit var scanDotBg: GradientDrawable
    private lateinit var statusTv: TextView
    private lateinit var candidatesBox: LinearLayout
    private lateinit var btnRetry: TextView
    private lateinit var btnForget: TextView
    private lateinit var btnClose: TextView
    private var dotAnimator: ObjectAnimator? = null
    private var active = true
    private var lastCandidatesKey = ""

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!active) return
            refreshCandidatesUi()
            handler.postDelayed(this, 650L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null)
        LiquidGlassKit.ensureInitialized(this)
        StationAudioService.ensureStarted(this)

        // Ensure Android 9 location_mode allows BLE scan results before scanning
        thread(isDaemon = true) {
            try {
                java.net.URL("http://127.0.0.1:8080/api/remote/pair").openConnection().apply {
                    connectTimeout = 800
                    readTimeout = 800
                }
            } catch (_: Throwable) {}
        }

        val root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            val wall = LiquidGlassKit.sharpWallpaper
            background = if (wall != null) {
                BitmapDrawable(resources, wall)
            } else {
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor("#030509"), Color.parseColor("#0A1526"))
                )
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(34), dp(26), dp(34), dp(24))
        }
        LiquidGlassKit.applyGlass(
            card,
            radiusDp = 26f,
            baseTint = Color.parseColor("#6A09111E"),
            focusTint = Color.parseColor("#6A09111E"),
            normalStroke = Color.parseColor("#2AFFFFFF"),
            focusStroke = Color.parseColor("#2AFFFFFF")
        )

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(14), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#220EA5E9"))
                setStroke(dp(1), Color.parseColor("#5538BDF8"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        scanDotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#38BDF8"))
        }
        scanDot = View(this).apply {
            background = scanDotBg
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                marginEnd = dp(8)
            }
        }
        scanBadgeTv = TextView(this).apply {
            text = "ПОИСК BLUETOOTH LE ПУЛЬТА"
            setTextColor(Color.parseColor("#7DD3FC"))
            textSize = 11f
            letterSpacing = 0.09f
            typeface = Typeface.DEFAULT_BOLD
        }
        badgeRow.addView(scanDot)
        badgeRow.addView(scanBadgeTv)
        card.addView(badgeRow)

        card.addView(TextView(this).apply {
            text = "Подключение пульта"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })

        card.addView(TextView(this).apply {
            text = "Удерживайте кнопки «Домой» и «Громкость −» на пульте ~5 сек, пока индикатор не начнёт мигать.\nНайденные пульты появятся ниже — выберите пульт и нажмите OK."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13.5f
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1.0f)
            maxWidth = dp(540)
            setPadding(0, dp(8), 0, dp(14))
        })

        statusTv = TextView(this).apply {
            text = "Проверяю текущее состояние пульта…"
            setTextColor(Color.parseColor("#E0F2FE"))
            textSize = 13.5f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#330D1B2E"))
                setStroke(dp(1), Color.parseColor("#4438BDF8"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        card.addView(statusTv)

        candidatesBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        card.addView(candidatesBox)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }

        btnRetry = makeGlassPill("↻ Искать заново", primary = true) {
            StationAudioService.instance?.bleRemoteManager?.stopScan()
            setScanVisual("searching", "Сканирую BLE-эфир в поисках пульта…")
            startSearch(0, forceRescan = true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(10)
            }
        }

        btnForget = makeGlassPill("🗑 Забыть пульт", primary = false) {
            val mgr = StationAudioService.instance?.bleRemoteManager ?: return@makeGlassPill
            mgr.forgetBondedRemote { _, msg ->
                handler.post {
                    setScanVisual("searching", "$msg Ищу пульт заново…")
                    refreshForgetButton()
                    startSearch(0, forceRescan = true)
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(10)
            }
        }

        btnClose = makeGlassPill("Закрыть", primary = false) {
            finish()
        }

        btnRow.addView(btnRetry)
        btnRow.addView(btnForget)
        btnRow.addView(btnClose)
        card.addView(btnRow)

        root.addView(
            card,
            FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        setContentView(root)

        startDotPulse()
        refreshInitialStatusAndStart()
        handler.postDelayed(pollRunnable, 650L)
        btnClose.requestFocus()
    }

    private fun makeGlassPill(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13.5f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(if (primary) "#F0F9FF" else "#FFE8C686"))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isFocusable = true
            isClickable = true
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 999f,
                baseTint = Color.parseColor(if (primary) "#550EA5E9" else "#44172033"),
                focusTint = Color.parseColor(if (primary) "#8838BDF8" else "#66263452"),
                normalStroke = Color.parseColor(if (primary) "#5538BDF8" else "#3AE8C686"),
                focusStroke = Color.parseColor(if (primary) "#88BAE6FD" else "#88E8C686")
            )
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(100).start()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun refreshForgetButton() {
        val mgr = StationAudioService.instance?.bleRemoteManager
        val st = mgr?.getRemoteStatus()
        btnForget.visibility = if (st?.bonded == true) View.VISIBLE else View.GONE
    }

    private fun refreshInitialStatusAndStart() {
        val mgr = StationAudioService.instance?.bleRemoteManager
        if (mgr == null) {
            startSearch(0, forceRescan = false)
            return
        }
        refreshForgetButton()
        val st = mgr.getRemoteStatus()
        when {
            st.connected -> {
                val bat = if (st.batteryPct >= 0) " · Заряд ${st.batteryPct}%" else ""
                setScanVisual("connected", "Сейчас активен: ${st.name} (${st.address})$bat")
            }
            st.bonded -> {
                setScanVisual(
                    "searching",
                    "Сохранённый пульт ${st.name} сейчас не подключён. Ищу пульт в эфире (удерживайте «Домой» + «−»)…"
                )
                startSearch(0, forceRescan = false)
            }
            else -> {
                setScanVisual("searching", "Ни один пульт не подключён. Ищу BLE-пульты рядом…")
                startSearch(0, forceRescan = true)
            }
        }
    }

    private fun refreshCandidatesUi() {
        val mgr = StationAudioService.instance?.bleRemoteManager ?: return
        refreshForgetButton()
        val list = mgr.getDiscoveredCandidates()
        val key = list.joinToString("|") { "${it.address}:${it.name}:${it.bonded}" }
        if (key == lastCandidatesKey) return
        lastCandidatesKey = key
        candidatesBox.removeAllViews()
        if (list.isEmpty()) return

        for (cand in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(dp(14), dp(9), dp(14), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
                LiquidGlassKit.applyGlass(
                    this,
                    radiusDp = 14f,
                    baseTint = Color.parseColor("#44142238"),
                    focusTint = Color.parseColor("#771D4ED8"),
                    normalStroke = Color.parseColor("#4438BDF8"),
                    focusStroke = Color.parseColor("#8893C5FD")
                )
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.02f else 1f).scaleY(if (hasFocus) 1.02f else 1f).setDuration(90).start()
                }
                setOnClickListener {
                    setScanVisual("pairing", "Подключаю ${cand.name} (${cand.address})…")
                    mgr.startAutoScanAndPair(
                        durationMs = 30000L,
                        forceRescan = true,
                        targetAddress = cand.address
                    ) { state, detail ->
                        handler.post {
                            if (active) setScanVisual(state, detail)
                        }
                    }
                }
            }
            val title = TextView(this).apply {
                text = "🎮 ${cand.name} (${cand.address})"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val action = TextView(this).apply {
                text = if (cand.bonded) "OK · Переподключить" else "OK · Подключить"
                setTextColor(Color.parseColor("#7DD3FC"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(title)
            row.addView(action)
            candidatesBox.addView(row)
        }
    }

    private fun startDotPulse() {
        dotAnimator?.cancel()
        dotAnimator = ObjectAnimator.ofFloat(scanDot, View.ALPHA, 1f, 0.25f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopDotPulse() {
        dotAnimator?.cancel()
        dotAnimator = null
        scanDot.alpha = 1f
    }

    private fun setScanVisual(state: String, detail: String) {
        statusTv.text = detail
        refreshForgetButton()
        when (state) {
            "connected" -> {
                val st = StationAudioService.instance?.bleRemoteManager?.getRemoteStatus()
                if (st?.connected == true) {
                    stopDotPulse()
                    scanDotBg.setColor(Color.parseColor("#10B981"))
                    scanBadgeTv.text = "ПУЛЬТ ПОДКЛЮЧЁН"
                    scanBadgeTv.setTextColor(Color.parseColor("#6EE7B7"))
                    statusTv.setTextColor(Color.parseColor("#6EE7B7"))
                    if (st.batteryPct >= 0 && !detail.contains("%")) {
                        statusTv.text = "$detail · Заряд ${st.batteryPct}%"
                    }
                } else {
                    scanDotBg.setColor(Color.parseColor("#F59E0B"))
                    scanBadgeTv.text = "ПУЛЬТ НЕ ПОДКЛЮЧЁН"
                    scanBadgeTv.setTextColor(Color.parseColor("#FDE68A"))
                    statusTv.setTextColor(Color.parseColor("#FDE68A"))
                }
            }
            "not_found", "error" -> {
                stopDotPulse()
                scanDotBg.setColor(Color.parseColor("#F59E0B"))
                scanBadgeTv.text = "НУЖЕН ПОВТОР ПОИСКА"
                scanBadgeTv.setTextColor(Color.parseColor("#FDE68A"))
                statusTv.setTextColor(Color.parseColor("#FDE68A"))
                btnRetry.requestFocus()
            }
            else -> {
                scanDotBg.setColor(Color.parseColor("#38BDF8"))
                scanBadgeTv.text = "ПОИСК BLUETOOTH LE ПУЛЬТА"
                scanBadgeTv.setTextColor(Color.parseColor("#7DD3FC"))
                statusTv.setTextColor(Color.parseColor("#E0F2FE"))
                if (dotAnimator == null) startDotPulse()
            }
        }
    }

    private fun startSearch(attempt: Int, forceRescan: Boolean) {
        if (!active) return
        val manager = StationAudioService.instance?.bleRemoteManager
        if (manager == null) {
            if (attempt >= 10) {
                setScanVisual("error", "Служба пульта не запустилась. Нажмите «Искать заново».")
            } else {
                handler.postDelayed({ startSearch(attempt + 1, forceRescan) }, 500)
            }
            return
        }
        manager.startAutoScanAndPair(60000L, forceRescan = forceRescan) { state, detail ->
            handler.post {
                if (active) setScanVisual(state, detail)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.repeatCount == 0) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        active = false
        stopDotPulse()
        handler.removeCallbacksAndMessages(null)
        StationAudioService.instance?.bleRemoteManager?.stopScan()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
