package com.openstation.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** One-time confirmation for a completed OTA, distinct from first-time setup. */
class UpdateCompleteActivity : Activity() {
    private var version = ""
    private var notes = ""
    private lateinit var voiceHint: TextView
    private lateinit var orb: AliceOrbView
    private var closing = false
    private val prefs by lazy { getSharedPreferences("openstation_update_intro", MODE_PRIVATE) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun background(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        version = intent.getStringExtra("version").orEmpty().trim()
        notes = intent.getStringExtra("changelog").orEmpty().trim()
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff080f22.toInt(), 0xff172449.toInt(), 0xff111327.toInt()))
            setPadding(dp(56), dp(32), dp(56), dp(32))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(row, FrameLayout.LayoutParams(-1, -1))
        val hero = FrameLayout(this)
        row.addView(hero, LinearLayout.LayoutParams(0, -1, 0.40f))
        orb = AliceOrbView(this)
        hero.addView(orb, FrameLayout.LayoutParams(dp(270), dp(270), Gravity.CENTER))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(panel, LinearLayout.LayoutParams(0, -1, 0.60f))
        val eyebrow = label("OPENSTATION  •  ОБНОВЛЕНИЕ", 13f, 0xff9ebcec.toInt(), true)
        panel.addView(eyebrow)
        panel.addView(label("Всё готово", 40f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        panel.addView(label(if (version.isBlank()) "Я обновилась" else "Я обновилась до версии $version", 20f, 0xffd5e3ff.toInt()),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(20) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(18))
            background = background(0x88202f50.toInt(), 20, 0x554b75ad)
        }
        card.addView(label("ЧТО НОВОГО", 13f, 0xffa9c7f4.toInt(), true))
        val scroller = ScrollView(this).apply { isFillViewport = false; isVerticalScrollBarEnabled = true }
        val description = if (notes.isBlank()) "Обновление установлено. Подробности доступны в разделе обновлений." else notes
        scroller.addView(label(description, 17f, 0xffeef4ff.toInt()).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        card.addView(scroller, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        panel.addView(card, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(14) })
        voiceHint = label("", 13f, 0xffadc4e6.toInt())
        panel.addView(voiceHint)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val done = label("Продолжить", 17f, 0xff102044.toInt(), true).apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            background = background(0xffb9d7ff.toInt(), 16)
            setOnFocusChangeListener { v, focused -> v.alpha = if (focused) 1f else 0.75f }
            setOnClickListener { closeScreen() }
        }
        buttons.addView(done, LinearLayout.LayoutParams(dp(170), dp(52)))
        val speak = label("Озвучить", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            background = background(0x66314366, 16, 0x6683b4e8)
            setOnFocusChangeListener { v, focused -> v.alpha = if (focused) 1f else 0.7f }
            setOnClickListener { announce() }
        }
        buttons.addView(speak, LinearLayout.LayoutParams(dp(155), dp(52)).apply { leftMargin = dp(12) })
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setContentView(root)
        done.requestFocus()
        panel.alpha = 0f; panel.translationY = dp(18).toFloat()
        panel.animate().alpha(1f).translationY(0f).setDuration(480).setInterpolator(DecelerateInterpolator()).start()
        hero.alpha = 0f; hero.scaleX = 0.92f; hero.scaleY = 0.92f
        hero.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(650).setInterpolator(DecelerateInterpolator()).start()
        if (intent.getBooleanExtra("announce", false) && prefs.getString("announced_version", "") != version) {
            prefs.edit().putString("announced_version", version).apply()
            root.postDelayed({ if (!isFinishing && !closing) announce() }, 1200L)
        }
    }

    private fun post(path: String, body: String): Boolean = try {
        val conn = URL("http://127.0.0.1:8080$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 3000; conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } finally { conn.disconnect() }
    } catch (_: Exception) { false }

    private fun announce() {
        val summary = notes.replace(Regex("[\\r\\n]+"), " ").take(350)
        val message = "Я обновилась." + if (summary.isNotBlank()) " Что нового: $summary" else " Подробности на экране."
        voiceHint.text = "Готовлю голосовое сообщение…"
        thread(isDaemon = true) {
            val ok = post("/api/assistant/update_announcement", JSONObject().put("text", message).toString())
            runOnUiThread { if (!isFinishing) voiceHint.text = if (ok) "" else "Голос сейчас недоступен — изменения показаны выше." }
        }
    }

    private fun closeScreen() {
        if (closing) return
        closing = true
        thread(isDaemon = true) {
            post("/api/setup/dismiss_ota_wizard", "{}")
            runOnUiThread { if (!isFinishing) finish() }
        }
    }
    @Deprecated("Used by TV remotes on Android 9")
    override fun onBackPressed() = closeScreen()
    override fun onPause() { orb.setAnimationEnabled(false); super.onPause() }
    override fun onResume() { super.onResume(); if (::orb.isInitialized) orb.setAnimationEnabled(true) }
}
