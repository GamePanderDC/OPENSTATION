package com.openstation.launcher

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 60fps GPU-friendly kinetic Aurora, constellation stardust, and celebratory
 * particle backdrop for SetupWizardActivity.
 * Pre-renders a soft gaussian glow texture once so per-frame drawing does zero heap allocations.
 */
class WizardAuroraCanvasView(context: Context) : View(context) {

    private data class Palette(val bg: Int, val blobA: Int, val blobB: Int, val blobC: Int)

    private val palettes = arrayOf(
        Palette(Color.parseColor("#040711"), Color.parseColor("#0EA5E9"), Color.parseColor("#2563EB"), Color.parseColor("#E8C686")),
        Palette(Color.parseColor("#040914"), Color.parseColor("#22D3EE"), Color.parseColor("#3B82F6"), Color.parseColor("#10B981")),
        Palette(Color.parseColor("#070614"), Color.parseColor("#6366F1"), Color.parseColor("#38BDF8"), Color.parseColor("#F59E0B")),
        Palette(Color.parseColor("#060412"), Color.parseColor("#8B5CF6"), Color.parseColor("#38BDF8"), Color.parseColor("#E8C686")),
        Palette(Color.parseColor("#09040E"), Color.parseColor("#EC4899"), Color.parseColor("#6366F1"), Color.parseColor("#F59E0B")),
        Palette(Color.parseColor("#030A11"), Color.parseColor("#00D4FF"), Color.parseColor("#10B981"), Color.parseColor("#8B5CF6")),
        Palette(Color.parseColor("#040A09"), Color.parseColor("#10B981"), Color.parseColor("#FBBF24"), Color.parseColor("#38BDF8"))
    )

    private val argbEval = ArgbEvaluator()
    private var targetStepIdx = 0
    private var curBg = palettes[0].bg
    private var curBlobA = palettes[0].blobA
    private var curBlobB = palettes[0].blobB
    private var curBlobC = palettes[0].blobC

    private val glowBitmap: Bitmap by lazy {
        val sz = 160
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            sz * 0.5f, sz * 0.5f, sz * 0.5f,
            intArrayOf(
                Color.argb(235, 255, 255, 255),
                Color.argb(115, 255, 255, 255),
                Color.argb(28, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.36f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(sz * 0.5f, sz * 0.5f, sz * 0.5f, p)
        bmp
    }

    private val blobPaintA = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blobPaintB = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blobPaintC = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blobMatrix = Matrix()
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val confettiPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private class Star(var xFrac: Float, var yFrac: Float, var radius: Float, var speed: Float, var phase: Float, var alphaBase: Int)
    private class Confetti(var x: Float = 0f, var y: Float = 0f, var vx: Float = 0f, var vy: Float = 0f, var size: Float = 4f, var life: Float = 0f, var color: Int = Color.WHITE)

    private val stars = Array(34) { idx ->
        val r = Random(1337 + idx * 71)
        Star(
            xFrac = r.nextFloat(),
            yFrac = r.nextFloat(),
            radius = 1.0f + r.nextFloat() * 2.1f,
            speed = 0.012f + r.nextFloat() * 0.025f,
            phase = r.nextFloat() * 6.283f,
            alphaBase = 35 + r.nextInt(110)
        )
    }

    private val confetti = Array(42) { Confetti() }
    private var confettiActive = false
    private var lastFrameNs = 0L
    private var animTimeSec = 0f
    private var running = false
    private var cachedBeamW = -1

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNs == 0L) 0.016f else ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNs = frameTimeNanos
            animTimeSec += dt
            stepSimulation(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setStep(step: Int, totalSteps: Int = 7) {
        val idx = (step - 1).coerceIn(0, palettes.lastIndex)
        if (idx != targetStepIdx && step >= totalSteps) {
            triggerCelebration()
        }
        targetStepIdx = idx
    }

    fun triggerCelebration() {
        val w = if (width > 0) width.toFloat() else 1280f
        val h = if (height > 0) height.toFloat() else 720f
        val colors = intArrayOf(
            Color.parseColor("#E8C686"),
            Color.parseColor("#10B981"),
            Color.parseColor("#38BDF8"),
            Color.parseColor("#FBBF24"),
            Color.parseColor("#A78BFA"),
            Color.WHITE
        )
        val r = Random(System.nanoTime())
        for (i in confetti.indices) {
            val c = confetti[i]
            val fromRight = (i % 2 == 0)
            c.x = if (fromRight) w * (0.42f + r.nextFloat() * 0.52f) else w * (0.06f + r.nextFloat() * 0.36f)
            c.y = h * (0.05f + r.nextFloat() * 0.28f)
            c.vx = (r.nextFloat() - 0.5f) * (w * 0.28f)
            c.vy = -h * (0.12f + r.nextFloat() * 0.22f)
            c.size = 2.5f + r.nextFloat() * 4.5f
            c.life = 1.0f + r.nextFloat() * 0.9f
            c.color = colors[i % colors.size]
        }
        confettiActive = true
    }

    fun startAnimations() {
        if (running) return
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    fun stopAnimations() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimations()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    private fun stepSimulation(dt: Float) {
        val tgt = palettes[targetStepIdx]
        val k = (dt * 3.2f).coerceIn(0.02f, 0.25f)
        curBg = argbEval.evaluate(k, curBg, tgt.bg) as Int
        curBlobA = argbEval.evaluate(k, curBlobA, tgt.blobA) as Int
        curBlobB = argbEval.evaluate(k, curBlobB, tgt.blobB) as Int
        curBlobC = argbEval.evaluate(k, curBlobC, tgt.blobC) as Int

        for (s in stars) {
            s.yFrac -= s.speed * dt
            if (s.yFrac < -0.02f) {
                s.yFrac = 1.02f
            }
        }
        if (confettiActive) {
            val h = if (height > 0) height.toFloat() else 720f
            var anyAlive = false
            for (c in confetti) {
                if (c.life > 0f) {
                    c.x += c.vx * dt
                    c.y += c.vy * dt
                    c.vy += h * 0.36f * dt
                    c.vx *= 0.985f
                    c.life -= dt * 0.42f
                    if (c.life > 0f) anyAlive = true
                }
            }
            if (!anyAlive) confettiActive = false
        }
    }

    private fun drawGlowBlob(canvas: Canvas, paint: Paint, colorRgb: Int, alpha: Int, cx: Float, cy: Float, radius: Float) {
        paint.colorFilter = PorterDuffColorFilter(colorRgb, PorterDuff.Mode.SRC_IN)
        paint.alpha = alpha
        val bmp = glowBitmap
        val sc = (radius * 2f) / bmp.width.toFloat()
        blobMatrix.reset()
        blobMatrix.setScale(sc, sc)
        blobMatrix.postTranslate(cx - radius, cy - radius)
        canvas.drawBitmap(bmp, blobMatrix, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Color.argb(214, Color.red(curBg), Color.green(curBg), Color.blue(curBg)))

        val t = animTimeSec
        drawGlowBlob(
            canvas, blobPaintA, curBlobA, 118,
            w * (0.20f + 0.08f * sin(t * 0.37f)),
            h * (0.24f + 0.07f * cos(t * 0.29f)),
            w * 0.46f
        )
        drawGlowBlob(
            canvas, blobPaintB, curBlobB, 102,
            w * (0.78f + 0.09f * cos(t * 0.31f)),
            h * (0.72f + 0.08f * sin(t * 0.23f)),
            w * 0.50f
        )
        drawGlowBlob(
            canvas, blobPaintC, curBlobC, 78,
            w * (0.54f + 0.14f * sin(t * 0.19f + 1.4f)),
            h * (0.18f + 0.09f * cos(t * 0.27f + 0.7f)),
            w * 0.36f
        )

        // Subtle sweeping specular horizon beam
        if (cachedBeamW != width) {
            cachedBeamW = width
            beamPaint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(26, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val sweepOffset = ((t * 0.07f) % 1.4f - 0.2f) * w
        canvas.save()
        canvas.translate(sweepOffset - w * 0.5f, 0f)
        canvas.rotate(-12f, w * 0.5f, h * 0.5f)
        canvas.drawRect(0f, -h * 0.3f, w * 0.45f, h * 1.3f, beamPaint)
        canvas.restore()

        // Drifting stardust particles
        for (s in stars) {
            val shimmer = 0.5f + 0.5f * sin(t * 1.8f + s.phase)
            starPaint.color = Color.argb((s.alphaBase * (0.45f + 0.55f * shimmer)).toInt().coerceIn(8, 220), 226, 240, 255)
            canvas.drawCircle(s.xFrac * w, s.yFrac * h, s.radius, starPaint)
        }

        if (confettiActive) {
            for (c in confetti) {
                if (c.life > 0f) {
                    val a = (min(1f, c.life) * 235f).toInt().coerceIn(0, 255)
                    confettiPaint.color = Color.argb(a, Color.red(c.color), Color.green(c.color), Color.blue(c.color))
                    canvas.drawCircle(c.x, c.y, c.size, confettiPaint)
                }
            }
        }
    }
}

/**
 * Interactive animated step rail with sliding golden glass pill indicator and spring transitions.
 */
class WizardStepRailView(
    context: Context,
    labels: Array<String>,
    private val onStepSelected: (Int) -> Unit
) : LinearLayout(context) {

    private val buttons = ArrayList<TextView>(labels.size)
    private var activeIndex = 0
    private var animPos = 0f
    private var running = false
    private val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capsuleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()
    private var lastFrameNs = 0L

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNs == 0L) 0.016f else ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNs = frameTimeNanos
            val diff = activeIndex.toFloat() - animPos
            if (kotlin.math.abs(diff) > 0.002f) {
                animPos += diff * (dt * 12f).coerceIn(0.08f, 0.45f)
                invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                animPos = activeIndex.toFloat()
                running = false
                invalidate()
            }
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        val padH = dp(6f).toInt()
        val padV = dp(4f).toInt()
        setPadding(padH, padV, padH, padV)

        labels.forEachIndexed { idx, lbl ->
            val tv = TextView(context).apply {
                text = lbl
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(11f).toInt(), dp(5f).toInt(), dp(11f).toInt(), dp(5f).toInt())
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.07f else 1f).scaleY(if (hasFocus) 1.07f else 1f).setDuration(110).start()
                    updateButtonColors()
                    invalidate()
                }
                setOnClickListener { onStepSelected(idx + 1) }
                layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (idx < labels.lastIndex) marginEnd = dp(3f).toInt()
                }
            }
            buttons.add(tv)
            addView(tv)
        }
        updateButtonColors()
    }

    fun setActiveStep(step: Int) {
        val next = (step - 1).coerceIn(0, buttons.lastIndex)
        activeIndex = next
        updateButtonColors()
        if (!running) {
            running = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(frameCb)
        }
    }

    private fun updateButtonColors() {
        buttons.forEachIndexed { idx, tv ->
            val color = when {
                idx == activeIndex -> Color.parseColor("#0B0F17")
                tv.isFocused -> Color.parseColor("#FFE8C686")
                idx < activeIndex -> Color.parseColor("#6EE7B7")
                else -> Color.parseColor("#94A3B8")
            }
            tv.setTextColor(color)
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 4f && h > 4f) {
            // Track capsule background
            progressTrackPaint.color = Color.parseColor("#8C0B1322")
            rectF.set(0f, 0f, w, h)
            canvas.drawRoundRect(rectF, h * 0.5f, h * 0.5f, progressTrackPaint)

            capsuleStrokePaint.strokeWidth = dp(1f)
            capsuleStrokePaint.color = Color.parseColor("#33FFFFFF")
            rectF.inset(dp(0.5f), dp(0.5f))
            canvas.drawRoundRect(rectF, h * 0.5f, h * 0.5f, capsuleStrokePaint)

            // Interpolate sliding golden highlight capsule between adjacent buttons
            val i0 = animPos.toInt().coerceIn(0, buttons.lastIndex)
            val i1 = min(buttons.lastIndex, i0 + 1)
            val frac = (animPos - i0).coerceIn(0f, 1f)
            val b0 = buttons[i0]
            val b1 = buttons[i1]
            val left = b0.left + (b1.left - b0.left) * frac
            val right = b0.right + (b1.right - b0.right) * frac
            val top = b0.top.toFloat()
            val bot = b0.bottom.toFloat()

            // Subtle progress fill trace behind completed steps
            if (left > dp(6f)) {
                progressGlowPaint.color = Color.parseColor("#2410B981")
                rectF.set(dp(4f), top + dp(2f), left + dp(8f), bot - dp(2f))
                canvas.drawRoundRect(rectF, (bot - top) * 0.5f, (bot - top) * 0.5f, progressGlowPaint)
            }

            if (right > left + 2f) {
                capsulePaint.shader = LinearGradient(
                    left, top, right, bot,
                    intArrayOf(Color.parseColor("#F5DFB0"), Color.parseColor("#D8AC5E")),
                    null,
                    Shader.TileMode.CLAMP
                )
                rectF.set(left, top, right, bot)
                canvas.drawRoundRect(rectF, (bot - top) * 0.5f, (bot - top) * 0.5f, capsulePaint)
            }

            for (tv in buttons) {
                if (tv.isFocused) {
                    focusRingPaint.strokeWidth = dp(1.8f)
                    focusRingPaint.color = Color.parseColor("#E638BDF8")
                    rectF.set(tv.left.toFloat(), tv.top.toFloat(), tv.right.toFloat(), tv.bottom.toFloat())
                    canvas.drawRoundRect(rectF, (tv.bottom - tv.top) * 0.5f, (tv.bottom - tv.top) * 0.5f, focusRingPaint)
                }
            }
        }
        super.dispatchDraw(canvas)
    }
}

/**
 * 22-bar silky spring-animated spectrum/VU visualizer driven by real daemon microphone RMS.
 */
class WizardWaveformView(context: Context) : View(context) {

    private val barCount = 22
    private val barValues = FloatArray(barCount)
    private var targetLevel = 0.08f
    private var speechActive = false
    private var running = false
    private var animPhase = 0f
    private var lastFrameNs = 0L

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()
    private var cachedH = -1f
    private var normalShader: LinearGradient? = null
    private var speechShader: LinearGradient? = null

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNs == 0L) 0.016f else ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNs = frameTimeNanos
            animPhase += dt * (if (speechActive) 7.5f else 3.2f)
            for (i in 0 until barCount) {
                val normCenter = 1f - kotlin.math.abs(i - (barCount - 1) * 0.5f) / (barCount * 0.5f)
                val wave = 0.55f + 0.45f * sin(animPhase + i * 0.58f)
                val idleWave = 0.10f + 0.08f * sin(animPhase * 0.7f + i * 0.45f)
                val desired = (idleWave + targetLevel * (0.35f + 0.65f * normCenter) * wave).coerceIn(0.08f, 1.0f)
                val rate = if (desired > barValues[i]) 18f else 7.5f
                barValues[i] += (desired - barValues[i]) * (dt * rate).coerceIn(0.05f, 0.65f)
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun updateTelemetry(rms: Int, noiseFloor: Int, vadSpeech: Boolean) {
        speechActive = vadSpeech
        val snrBoost = max(0f, (rms - noiseFloor).toFloat()) / max(180f, noiseFloor * 1.5f + 220f)
        val logRms = if (rms > 1) (kotlin.math.log10(rms.toFloat()) / 3.7f).coerceIn(0f, 1f) else 0.04f
        targetLevel = max(logRms * 0.75f, snrBoost.coerceIn(0f, 1f)).coerceIn(0.06f, 1f)
    }

    fun startAnimations() {
        if (running) return
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    fun stopAnimations() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimations()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        if (cachedH != h || normalShader == null) {
            cachedH = h
            normalShader = LinearGradient(
                0f, h, 0f, 0f,
                intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#38BDF8"), Color.parseColor("#818CF8")),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            speechShader = LinearGradient(
                0f, h, 0f, 0f,
                intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#E8C686"), Color.parseColor("#FBBF24")),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        val gap = dp(3.5f)
        val totalGaps = gap * (barCount - 1)
        val barW = max(dp(3f), (w - totalGaps) / barCount)
        val totalW = barW * barCount + totalGaps
        var x = (w - totalW) * 0.5f
        val rad = barW * 0.5f

        barPaint.shader = if (speechActive) speechShader else normalShader
        glowPaint.color = if (speechActive) Color.argb(54, 232, 198, 134) else Color.argb(38, 56, 189, 248)

        for (i in 0 until barCount) {
            val bh = max(barW, h * barValues[i])
            val top = (h - bh) * 0.5f
            barRect.set(x - dp(1f), top - dp(1f), x + barW + dp(1f), top + bh + dp(1f))
            canvas.drawRoundRect(barRect, rad + dp(1f), rad + dp(1f), glowPaint)
            barRect.set(x, top, x + barW, top + bh)
            canvas.drawRoundRect(barRect, rad, rad, barPaint)
            x += barW + gap
        }
    }
}

/**
 * Interactive 24-LED Top Halo Ring + Mini Front Dot-Matrix preview of Station Max.
 */
class WizardLedRingPreviewView(context: Context) : View(context) {

    var ringMode: String = "music"
    var ringColor: Int = Color.parseColor("#00D4FF")
    var audioLevel: Float = 0.35f

    private var phase = 0f
    private var running = false
    private var lastFrameNs = 0L

    private val chassisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ledGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ledCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrixDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = floatArrayOf(0f, 0.9f, 1f)

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNs == 0L) 0.016f else ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNs = frameTimeNanos
            phase += dt * 2.8f
            val liveEnergy = max(LiveOrbState.musicOverall, max(LiveOrbState.ttsRms, LiveOrbState.micRms))
            audioLevel += ((liveEnergy.coerceIn(0.08f, 1f)) - audioLevel) * 0.25f
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun startAnimations() {
        if (running) return
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    fun stopAnimations() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimations()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sz = min(w, h)
        if (sz <= 8f) return
        val cx = w * 0.5f
        val cy = h * 0.5f
        val outerR = sz * 0.44f
        val ledRingR = sz * 0.37f

        // Station dark anodized top lid silhouette
        chassisPaint.color = Color.parseColor("#D80A101D")
        canvas.drawCircle(cx, cy, outerR, chassisPaint)
        rimPaint.strokeWidth = max(1.2f, sz * 0.018f)
        rimPaint.color = Color.parseColor("#38FFFFFF")
        canvas.drawCircle(cx, cy, outerR, rimPaint)

        // Subtle miniature 5x3 central LED matrix icon inside speaker top
        val dotR = max(1.0f, sz * 0.017f)
        val step = sz * 0.048f
        val matrixPhase = phase * 1.4f
        for (row in -1..1) {
            for (col in -2..2) {
                val active = ringMode != "off" && ((col + 2 + row + 3) % 2 == 0 || sin(matrixPhase + col * 0.9f) > 0.1f)
                val colr = if (ringMode == "off") Color.argb(40, 148, 163, 184)
                else Color.argb(if (active) 210 else 65, Color.red(ringColor), Color.green(ringColor), Color.blue(ringColor))
                matrixDotPaint.color = colr
                canvas.drawCircle(cx + col * step, cy + row * step, dotR, matrixDotPaint)
            }
        }

        // 24 individual perimeter LEDs matching the real Station Max hardware ring
        val ledCount = 24
        val nodeR = max(1.6f, sz * 0.029f)
        for (i in 0 until ledCount) {
            val angle = (i.toFloat() / ledCount) * (2f * PI.toFloat()) - PI.toFloat() * 0.5f
            val lx = cx + cos(angle) * ledRingR
            val ly = cy + sin(angle) * ledRingR

            var alpha = 1.0f
            var col = ringColor
            when (ringMode) {
                "off" -> {
                    alpha = 0.12f
                    col = Color.parseColor("#475569")
                }
                "rainbow" -> {
                    hsv[0] = ((i.toFloat() / ledCount) * 360f + phase * 65f) % 360f
                    hsv[1] = 0.92f
                    hsv[2] = 1.0f
                    col = Color.HSVToColor(hsv)
                }
                "breathe" -> {
                    alpha = 0.25f + 0.75f * (0.5f + 0.5f * sin(phase * 1.3f))
                }
                "spinner" -> {
                    val head = (phase * 4.5f) % ledCount
                    val d = min(kotlin.math.abs(i - head), ledCount - kotlin.math.abs(i - head))
                    alpha = (1f - d / 5.5f).coerceIn(0.14f, 1.0f)
                }
                "music", "spectrum" -> {
                    hsv[0] = (185f + (i.toFloat() / ledCount) * 140f + phase * 30f) % 360f
                    hsv[1] = 0.85f
                    hsv[2] = 1.0f
                    val dynamicColor = Color.HSVToColor(hsv)
                    val pulse = 0.45f + 0.55f * audioLevel * (0.5f + 0.5f * sin(phase * 2.2f + i * 0.7f))
                    col = dynamicColor
                    alpha = pulse.coerceIn(0.25f, 1.0f)
                }
            }

            val glowA = (alpha * 95f).toInt().coerceIn(0, 255)
            val coreA = (alpha * 255f).toInt().coerceIn(0, 255)
            ledGlowPaint.color = Color.argb(glowA, Color.red(col), Color.green(col), Color.blue(col))
            ledCorePaint.color = Color.argb(coreA, Color.red(col), Color.green(col), Color.blue(col))
            canvas.drawCircle(lx, ly, nodeR * 2.2f, ledGlowPaint)
            canvas.drawCircle(lx, ly, nodeR, ledCorePaint)
        }
    }
}

/**
 * Animated flagship hero canvas for dedicated wizard screens (Remote, Wi-Fi, OTA, Music).
 * Composites crisp raster sprites with 60fps Choreographer sonar/halo/orbital animations.
 */
class WizardHeroGlyphView(
    context: Context,
    private val glyphMode: Mode
) : View(context) {

    enum class Mode { REMOTE, WIFI, OTA, MUSIC }

    var connected: Boolean = false
    var highlightGold: Boolean = false
    var otaProgress: Int = 0 // 0..100 during OTA download

    private var phase = 0f
    private var running = false
    private var lastFrameNs = 0L

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val arcRect = RectF()
    private val dstRect = RectF()
    private val srcRect = Rect()
    private val spriteCache = HashMap<Int, Bitmap?>()

    private fun sprite(resId: Int): Bitmap? {
        if (spriteCache.containsKey(resId)) return spriteCache[resId]
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = try {
            BitmapFactory.decodeResource(context.resources, resId, opts)
        } catch (_: Throwable) {
            null
        }
        spriteCache[resId] = bmp
        return bmp
    }

    private fun drawCenteredSprite(
        canvas: Canvas,
        bmp: Bitmap?,
        cx: Float,
        cy: Float,
        sizePx: Float,
        rotDeg: Float = 0f,
        alpha: Int = 255
    ) {
        if (bmp == null || bmp.isRecycled || sizePx <= 2f) return
        val half = sizePx * 0.5f
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(-half, -half, half, half)
        spritePaint.alpha = alpha.coerceIn(0, 255)
        canvas.save()
        canvas.translate(cx, cy)
        if (rotDeg != 0f) canvas.rotate(rotDeg)
        canvas.drawBitmap(bmp, srcRect, dstRect, spritePaint)
        canvas.restore()
        spritePaint.alpha = 255
    }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNs == 0L) 0.016f else ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNs = frameTimeNanos
            phase += dt * 1.9f
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun startAnimations() {
        if (running) return
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    fun stopAnimations() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimations()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sz = min(w, h)
        if (sz <= 16f) return
        val cx = w * 0.5f
        val cy = h * 0.5f

        val accent = when {
            connected -> Color.parseColor("#10B981")
            highlightGold -> Color.parseColor("#E8C686")
            glyphMode == Mode.MUSIC -> Color.parseColor("#A855F7")
            else -> Color.parseColor("#38BDF8")
        }
        val r = Color.red(accent)
        val g = Color.green(accent)
        val b = Color.blue(accent)

        val breath = 0.5f + 0.5f * sin(phase)
        haloPaint.color = Color.argb((42 + (breath * 34).toInt()), r, g, b)
        canvas.drawCircle(cx, cy, sz * (0.42f + 0.04f * breath), haloPaint)

        when (glyphMode) {
            Mode.REMOTE -> drawRemoteHero(canvas, cx, cy, sz, r, g, b)
            Mode.WIFI -> drawWifiHero(canvas, cx, cy, sz, r, g, b)
            Mode.OTA -> drawOtaHero(canvas, cx, cy, sz, accent)
            Mode.MUSIC -> drawMusicHero(canvas, cx, cy, sz)
        }
    }

    private fun drawRemoteHero(canvas: Canvas, cx: Float, cy: Float, sz: Float, r: Int, g: Int, b: Int) {
        ringPaint.strokeWidth = max(2f, sz * 0.017f)
        for (k in 0..2) {
            val t = ((phase * 0.52f + k * 0.333f) % 1f)
            val rad = sz * (0.24f + t * 0.24f)
            val a = ((1f - t) * (1f - t) * 190f).toInt().coerceIn(0, 255)
            ringPaint.color = Color.argb(a, r, g, b)
            arcRect.set(cx - rad, cy - rad, cx + rad, cy + rad)
            canvas.drawArc(arcRect, 0f, 360f, false, ringPaint)
        }

        val floatY = sin(phase * 1.15f) * sz * 0.03f
        val tilt = sin(phase * 0.65f) * 3.8f
        drawCenteredSprite(canvas, sprite(R.drawable.sprite_remote_hero), cx - sz * 0.02f, cy + floatY, sz * 0.80f, rotDeg = tilt)

        val btBob = sin(phase * 1.4f + 1.2f) * sz * 0.022f
        drawCenteredSprite(canvas, sprite(R.drawable.sprite_sys_bt), cx + sz * 0.27f, cy - sz * 0.27f + btBob, sz * 0.26f)
    }

    private fun drawWifiHero(canvas: Canvas, cx: Float, cy: Float, sz: Float, r: Int, g: Int, b: Int) {
        drawCenteredSprite(canvas, sprite(R.drawable.sprite_bork_dial_ring), cx, cy, sz * 0.88f, alpha = 205)

        ringPaint.strokeWidth = max(2.5f, sz * 0.020f)
        for (k in 0..1) {
            val t = ((phase * 0.45f + k * 0.5f) % 1f)
            val rad = sz * (0.28f + t * 0.18f)
            val a = ((1f - t) * 155f).toInt().coerceIn(0, 255)
            ringPaint.color = Color.argb(a, r, g, b)
            canvas.drawCircle(cx, cy, rad, ringPaint)
        }

        val pulseScale = 1f + 0.035f * sin(phase * 1.5f)
        val floatY = sin(phase * 1.0f) * sz * 0.018f
        drawCenteredSprite(canvas, sprite(R.drawable.sprite_sys_wifi), cx, cy + floatY, sz * 0.68f * pulseScale)
    }

    private fun drawOtaHero(canvas: Canvas, cx: Float, cy: Float, sz: Float, accent: Int) {
        val outerR = sz * 0.43f
        arcRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)

        ringPaint.strokeWidth = max(3f, sz * 0.030f)
        ringPaint.color = Color.argb(45, 255, 255, 255)
        canvas.drawCircle(cx, cy, outerR, ringPaint)

        ringPaint.color = accent
        if (otaProgress in 1..99) {
            canvas.drawArc(arcRect, -90f, (otaProgress / 100f) * 360f, false, ringPaint)
        } else {
            val sweepStart = (phase * 65f) % 360f
            canvas.drawArc(arcRect, sweepStart, 118f, false, ringPaint)
            ringPaint.color = Color.argb(145, 232, 198, 134)
            canvas.drawArc(arcRect, (sweepStart + 180f) % 360f, 82f, false, ringPaint)
        }

        val bob = sin(phase * 1.25f) * sz * 0.02f
        val scale = 1f + 0.025f * sin(phase * 1.5f)
        drawCenteredSprite(canvas, sprite(R.drawable.sprite_ota_cloud), cx, cy + bob, sz * 0.72f * scale)
    }

    private fun drawMusicHero(canvas: Canvas, cx: Float, cy: Float, sz: Float) {
        val spin = (phase * 28f) % 360f
        drawCenteredSprite(canvas, sprite(R.drawable.sprite_vinyl_disc), cx - sz * 0.04f, cy, sz * 0.78f, rotDeg = spin)

        val eqBob = sin(phase * 1.5f) * sz * 0.02f
        drawCenteredSprite(canvas, sprite(R.drawable.sprite_sys_eq), cx - sz * 0.30f, cy - sz * 0.27f + eqBob, sz * 0.28f)

        val ymBob = sin(phase * 1.25f + 1.4f) * sz * 0.022f
        drawCenteredSprite(canvas, sprite(R.drawable.sprite_ym_emblem), cx + sz * 0.27f, cy + sz * 0.24f + ymBob, sz * 0.36f, rotDeg = -5f)
    }
}
