package com.openstation.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/** Independent drifting and pulsing lights over the selected backdrop. */
class LivingWallpaperView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var running = false

    fun setRunning(enabled: Boolean) {
        if (running == enabled) return
        running = enabled
        if (enabled) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running || width <= 0 || height <= 0) return
        val t = SystemClock.elapsedRealtime() / 1000.0
        for (i in 0 until 42) {
            val seed = i * 73 + 19
            val x = (((seed * 37 % 997) / 997f) + 0.025f * sin(t * 0.12 + i).toFloat()) * width
            val y = (((seed * 83 % 991) / 991f) - ((t * (0.0015 + (i % 4) * 0.0005)) % 1.0).toFloat() + 1f) % 1f * height
            val pulse = (0.5 + 0.5 * sin(t * 0.7 + i * 1.73)).toFloat()
            paint.color = Color.argb((14 + pulse * 42).toInt(), 190, 220, 255)
            canvas.drawCircle(x, y, resources.displayMetrics.density * (0.7f + (i % 3) * 0.45f), paint)
        }
        postInvalidateDelayed(50L)
    }
}
