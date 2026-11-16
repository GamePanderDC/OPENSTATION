package com.openstation.launcher

import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Full-screen OTA status; the daemon remains responsible for the installation. */
class OtaProgressActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var details: TextView
    private lateinit var percent: TextView
    private lateinit var progress: ProgressBar
    private var lastProgress = -1
    private var seenUpdate = false
    private var closed = false
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(100), dp(28), dp(100), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff080f22.toInt(), 0xff172449.toInt(), 0xff111327.toInt()))
        }
        root.addView(text("OPENSTATION  •  ОБНОВЛЕНИЕ", 14f, 0xff9ebcec.toInt(), true))
        title = text("Готовим обновление", 34f, Color.WHITE, true)
        root.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        details = text("Пожалуйста, не отключайте питание", 17f, 0xffb6c9e9.toInt())
        root.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        percent = text("0%", 28f, Color.WHITE, true)
        root.addView(percent, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(30); bottomMargin = dp(12) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(0xffa6c9ff.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xff344866.toInt())
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(12)))
        root.addView(text("После установки станция перезагрузится", 14f, 0xff91a8ca.toInt()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })
        setContentView(root)
        root.alpha = 0f; root.animate().alpha(1f).setDuration(420).setInterpolator(DecelerateInterpolator()).start()
        poll()
    }
    private fun poll() {
        if (closed || isFinishing) return
        thread(name = "ota-progress", isDaemon = true) {
            val data = try {
                val conn = URL("http://127.0.0.1:8080/api/ota/status").openConnection() as HttpURLConnection
                try { conn.connectTimeout = 2000; conn.readTimeout = 2000; JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) }
                finally { conn.disconnect() }
            } catch (_: Exception) { null }
            handler.post {
                if (closed || isFinishing) return@post
                if (data != null) {
                    val state = data.optString("state")
                    val active = state in setOf("downloading", "verifying", "installing", "restarting")
                    if (active) {
                        seenUpdate = true
                        title.text = if (state == "restarting") "Завершаем обновление" else "Обновляем станцию"
                        details.text = data.optString("progressText").ifBlank { "Пожалуйста, не отключайте питание" }
                        val value = data.optInt("progressPct", 0).coerceIn(0, 100)
                        percent.text = "$value%"
                        if (value != lastProgress) {
                            ObjectAnimator.ofInt(progress, "progress", progress.progress, value * 10).apply {
                                duration = 450; interpolator = DecelerateInterpolator(); start()
                            }
                            lastProgress = value
                        }
                    } else if (state == "error") {
                        title.text = "Не удалось обновиться"
                        details.text = data.optString("error").ifBlank { "Попробуйте ещё раз" } + "\nНажмите Назад, чтобы закрыть"
                        percent.text = ""
                    } else if (seenUpdate && state == "up_to_date") {
                        title.text = "Обновление установлено"
                        details.text = "Перезагружаем станцию…"
                    }
                } else if (seenUpdate) {
                    details.text = "Связь временно недоступна. Проверяем состояние…"
                }
                handler.postDelayed({ poll() }, 700L)
            }
        }
    }
    override fun onDestroy() { closed = true; handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
