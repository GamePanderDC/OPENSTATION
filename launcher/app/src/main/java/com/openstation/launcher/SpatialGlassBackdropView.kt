package com.openstation.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry & math helpers for GPU optical lens coordinates (JVM-testable).
 */
object SpatialGlassMath {
    fun smoothStep(cur: Float, target: Float, factor: Float): Float {
        val next = cur + (target - cur) * factor
        return if (abs(next - target) < 0.001f) target else next
    }

    /**
     * Converts Android top-left view rectangle into GL bottom-left center & half-size in pixels.
     */
    fun toGlLensRect(
        leftPx: Float,
        topPx: Float,
        rightPx: Float,
        bottomPx: Float,
        screenHeightPx: Float
    ): FloatArray {
        val w = max(0f, rightPx - leftPx)
        val h = max(0f, bottomPx - topPx)
        val cx = leftPx + w * 0.5f
        val cyAndroid = topPx + h * 0.5f
        val cyGl = screenHeightPx - cyAndroid
        return floatArrayOf(cx, cyGl, w * 0.5f, h * 0.5f)
    }

    fun computeSpringScale(focused: Boolean, pressed: Boolean): Float = when {
        pressed -> 0.965f
        focused -> 1.055f
        else -> 1.0f
    }
}

/**
 * Configuration for an optical glass lens view tracked in window coordinates by [SpatialGlassBackdropView].
 */
data class SpatialLensSpec(
    val radiusDp: Float = 22f,
    val baseTint: Int = Color.parseColor("#2A0C1220"),
    val focusTint: Int = Color.parseColor("#42162238"),
    val accentTint: Int = Color.parseColor("#7DD3FC"),
    val bevelDp: Float = 14f,
    val refractDp: Float = 10f,
    val chromaDp: Float = 2.2f,
    val shadowDp: Float = 16f,
    val rimAlpha: Float = 0.28f,
    val focusRimAlpha: Float = 0.72f,
    val blurMix: Float = 0.88f,
    val clipParent: View? = null
)

data class GlassLensSnapshot(
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float,
    val clipLeftPx: Float,
    val clipTopPx: Float,
    val clipRightPx: Float,
    val clipBottomPx: Float,
    val radiusPx: Float,
    val bevelPx: Float,
    val refractPx: Float,
    val chromaPx: Float,
    val shadowPx: Float,
    val alpha: Float,
    val focus: Float,
    val press: Float,
    val rimAlpha: Float,
    val focusRimAlpha: Float,
    val blurMix: Float,
    val baseTint: Int,
    val focusTint: Int,
    val accentTint: Int
)

private class BoundGlassLens(
    val view: View,
    var spec: SpatialLensSpec
) {
    var focusAnim = 0f
    var pressAnim = 0f
}

/**
 * OpenGL ES 3.0 Hardware Liquid Glass Backdrop & SDF Optical Lens Compositor.
 *
 * Pipeline:
 * 1. Renders crisp 1920x1080 wallpaper (+ optional blurred music cover wash + reactive orb glow).
 * 2. Generates a 4-stage Dual-Kawase blur pyramid on GPU whenever the background scene changes.
 * 3. Composites full-screen backdrop with animated blur/dim (live transition for Apps/Control Center).
 * 4. Draws per-View SDF rounded-box glass lenses aligned in screen space with real bevel Snell refraction,
 *    micro chromatic edge dispersion, directional Fresnel rim catchlights, and zero wireframe slop borders.
 */
class SpatialGlassBackdropView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private val boundLenses = ArrayList<BoundGlassLens>(32)
    private val locTmp = IntArray(2)
    private val clipTmp = IntArray(2)
    private val backdropLoc = IntArray(2)
    var onGlStatusListener: ((ready: Boolean, failed: Boolean) -> Unit)? = null
    @Volatile var motionRateScale: Float = MotionPrefs.glSmoothFactor(context)

    // UI-thread targets vs GL-rendered smoothed states
    @Volatile private var targetBackdropBlur = 0f
    @Volatile private var targetBackdropDim = 0f
    @Volatile private var targetOrbEnergy = 0f
    @Volatile private var targetOrbHueTint = Color.parseColor("#38BDF8")
    @Volatile private var targetMusicCoverAlpha = 0f

    @Volatile private var pendingWallpaperBmp: Bitmap? = null
    @Volatile private var wallpaperDirty = false
    @Volatile private var pendingCoverBmp: Bitmap? = null
    @Volatile private var coverDirty = false

    private val renderLock = ReentrantLock()
    private val stateCond = renderLock.newCondition()
    private val frameDoneCond = renderLock.newCondition()

    private var surfaceAvailable = false
    private var viewW = 1920
    private var viewH = 1080
    private var latestSurfaceTex: SurfaceTexture? = null

    private var desiredSeq = 1L
    private var completedSeq = 0L
    private var latestLenses: List<GlassLensSnapshot> = emptyList()
    private var renderThread: GlRenderThread? = null

    private val density: Float
        get() = resources.displayMetrics.density

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    fun setWallpaperBitmap(bmp: Bitmap?) {
        if (bmp == null || bmp.isRecycled) return
        renderLock.withLock {
            pendingWallpaperBmp = bmp
            wallpaperDirty = true
            desiredSeq++
            stateCond.signalAll()
        }
    }

    fun setMusicCoverBitmap(bmp: Bitmap?, showAmbient: Boolean) {
        renderLock.withLock {
            pendingCoverBmp = if (bmp != null && !bmp.isRecycled) bmp else null
            coverDirty = true
            targetMusicCoverAlpha = if (showAmbient && pendingCoverBmp != null) 0.92f else 0f
            desiredSeq++
            stateCond.signalAll()
        }
    }

    fun setSceneState(
        backdropBlur: Float = targetBackdropBlur,
        backdropDim: Float = targetBackdropDim,
        orbEnergy: Float = targetOrbEnergy,
        orbColor: Int = targetOrbHueTint,
        musicAmbientAlpha: Float = targetMusicCoverAlpha
    ) {
        val clampedBlur = backdropBlur.coerceIn(0f, 1f)
        val clampedDim = backdropDim.coerceIn(0f, 0.85f)
        val clampedOrb = orbEnergy.coerceIn(0f, 1.5f)
        val clampedMus = if (pendingCoverBmp != null) musicAmbientAlpha.coerceIn(0f, 1f) else 0f
        if (abs(clampedBlur - targetBackdropBlur) > 0.002f ||
            abs(clampedDim - targetBackdropDim) > 0.002f ||
            abs(clampedOrb - targetOrbEnergy) > 0.015f ||
            orbColor != targetOrbHueTint ||
            abs(clampedMus - targetMusicCoverAlpha) > 0.002f
        ) {
            targetBackdropBlur = clampedBlur
            targetBackdropDim = clampedDim
            targetOrbEnergy = clampedOrb
            targetOrbHueTint = orbColor
            targetMusicCoverAlpha = clampedMus
            requestRenderFrame()
        }
    }

    fun registerLens(view: View, spec: SpatialLensSpec = SpatialLensSpec()): () -> Unit {
        view.background = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
        val existing = boundLenses.firstOrNull { it.view === view }
        if (existing != null) {
            existing.spec = spec
        } else {
            boundLenses.add(BoundGlassLens(view, spec))
        }
        requestRenderFrame()
        return {
            boundLenses.removeAll { it.view === view }
            requestRenderFrame()
        }
    }

    fun updateLensSpec(view: View, updater: (SpatialLensSpec) -> SpatialLensSpec) {
        val entry = boundLenses.firstOrNull { it.view === view } ?: return
        val next = updater(entry.spec)
        if (next != entry.spec) {
            entry.spec = next
            requestRenderFrame()
        }
    }

    fun requestRenderFrame() {
        renderLock.withLock {
            desiredSeq++
            stateCond.signalAll()
        }
    }

    /**
     * Snapshots all visible glass lenses synchronously during UI PreDraw and notifies the GL thread.
     * Bounds waiting to <=3.5ms only when lens geometry changed, keeping TextView glyphs locked to
     * optical refraction quads across animations and ScrollView movement.
     */
    fun syncLensesBeforeDraw(waitForFrame: Boolean = true) {
        if (!surfaceAvailable) return
        getLocationInWindow(backdropLoc)
        val bx = backdropLoc[0].toFloat()
        val by = backdropLoc[1].toFloat()
        val d = density
        var anyFocusAnimating = false
        val out = ArrayList<GlassLensSnapshot>(boundLenses.size)

        for (i in 0 until boundLenses.size) {
            val item = boundLenses[i]
            val v = item.view
            if (!v.isAttachedToWindow || v.width <= 1 || v.height <= 1) continue

            val visAlpha = computeEffectiveAlphaAndBounds(v, bx, by, out, item, d)
            if (visAlpha > 0.015f) {
                val focTarget = if (v.isFocused || v.isSelected) 1f else 0f
                val prsTarget = if (v.isPressed) 1f else 0f
                val rate = motionRateScale
                item.focusAnim = SpatialGlassMath.smoothStep(item.focusAnim, focTarget, (0.20f * rate).coerceIn(0.08f, 0.72f))
                item.pressAnim = SpatialGlassMath.smoothStep(item.pressAnim, prsTarget, (0.30f * rate).coerceIn(0.12f, 0.85f))
                if (item.focusAnim != focTarget || item.pressAnim != prsTarget) {
                    anyFocusAnimating = true
                }
            }
        }

        val changed = !snapshotsEqual(out, latestLenses)
        if (!changed && !anyFocusAnimating) return

        val submittedSeq: Long
        renderLock.withLock {
            latestLenses = out
            desiredSeq++
            submittedSeq = desiredSeq
            stateCond.signalAll()
            if (waitForFrame && surfaceAvailable) {
                val deadlineNs = TimeUnit.MILLISECONDS.toNanos(11L)
                var remaining = deadlineNs
                while (completedSeq < submittedSeq && remaining > 0L && surfaceAvailable) {
                    try {
                        remaining = frameDoneCond.awaitNanos(remaining)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        if (anyFocusAnimating) {
            postInvalidateOnAnimation()
        }
    }

    private fun computeEffectiveAlphaAndBounds(
        v: View,
        backdropX: Float,
        backdropY: Float,
        out: ArrayList<GlassLensSnapshot>,
        item: BoundGlassLens,
        d: Float
    ): Float {
        if (v.visibility != View.VISIBLE || v.alpha <= 0.01f) return 0f
        var alphaAcc = v.alpha
        var scaleXAcc = v.scaleX
        var scaleYAcc = v.scaleY
        var p: ViewParent? = v.parent
        while (p is View) {
            if (p.visibility != View.VISIBLE || p.alpha <= 0.01f) return 0f
            alphaAcc *= p.alpha
            scaleXAcc *= p.scaleX
            scaleYAcc *= p.scaleY
            if (p === parent) break
            p = (p as View).parent
        }
        if (alphaAcc <= 0.015f) return 0f

        v.getLocationInWindow(locTmp)
        val left = locTmp[0].toFloat() - backdropX
        val top = locTmp[1].toFloat() - backdropY
        val w = v.width.toFloat() * scaleXAcc
        val h = v.height.toFloat() * scaleYAcc
        val right = left + w
        val bottom = top + h

        // Frustum cull completely offscreen lenses
        if (right < -40f || left > viewW + 40f || bottom < -40f || top > viewH + 40f) return 0f

        var cl = 0f
        var ct = 0f
        var cr = viewW.toFloat()
        var cb = viewH.toFloat()
        val cp = item.spec.clipParent
        if (cp != null && cp.isAttachedToWindow) {
            cp.getLocationInWindow(clipTmp)
            cl = max(cl, clipTmp[0].toFloat() - backdropX)
            ct = max(ct, clipTmp[1].toFloat() - backdropY)
            cr = min(cr, clipTmp[0].toFloat() - backdropX + cp.width.toFloat())
            cb = min(cb, clipTmp[1].toFloat() - backdropY + cp.height.toFloat())
            if (right <= cl || left >= cr || bottom <= ct || top >= cb) return 0f
        }

        val focTarget = if (v.isFocused || v.isSelected) 1f else 0f
        val prsTarget = if (v.isPressed) 1f else 0f
        val rate = motionRateScale
        val foc = SpatialGlassMath.smoothStep(item.focusAnim, focTarget, (0.20f * rate).coerceIn(0.08f, 0.72f))
        val prs = SpatialGlassMath.smoothStep(item.pressAnim, prsTarget, (0.30f * rate).coerceIn(0.12f, 0.85f))

        val spec = item.spec
        val radPx = min(spec.radiusDp * d, min(w, h) * 0.5f)
        out.add(
            GlassLensSnapshot(
                leftPx = left,
                topPx = top,
                rightPx = right,
                bottomPx = bottom,
                clipLeftPx = cl,
                clipTopPx = ct,
                clipRightPx = cr,
                clipBottomPx = cb,
                radiusPx = radPx,
                bevelPx = min(spec.bevelDp * d, min(w, h) * 0.34f),
                refractPx = spec.refractDp * d * (1f + 0.32f * foc),
                chromaPx = spec.chromaDp * d * (1f + 0.45f * foc),
                shadowPx = spec.shadowDp * d * (1f + 0.25f * foc),
                alpha = alphaAcc.coerceIn(0f, 1f),
                focus = foc,
                press = prs,
                rimAlpha = spec.rimAlpha,
                focusRimAlpha = spec.focusRimAlpha,
                blurMix = spec.blurMix,
                baseTint = spec.baseTint,
                focusTint = spec.focusTint,
                accentTint = spec.accentTint
            )
        )
        return alphaAcc
    }

    private fun snapshotsEqual(a: List<GlassLensSnapshot>, b: List<GlassLensSnapshot>): Boolean {
        if (a.size != b.size) return false
        for (i in 0 until a.size) {
            val x = a[i]
            val y = b[i]
            if (abs(x.leftPx - y.leftPx) > 0.35f ||
                abs(x.topPx - y.topPx) > 0.35f ||
                abs(x.rightPx - y.rightPx) > 0.35f ||
                abs(x.bottomPx - y.bottomPx) > 0.35f ||
                abs(x.clipTopPx - y.clipTopPx) > 0.5f ||
                abs(x.clipBottomPx - y.clipBottomPx) > 0.5f ||
                abs(x.alpha - y.alpha) > 0.01f ||
                abs(x.focus - y.focus) > 0.01f ||
                abs(x.press - y.press) > 0.01f ||
                x.baseTint != y.baseTint ||
                x.focusTint != y.focusTint
            ) return false
        }
        return true
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderLock.withLock {
            latestSurfaceTex = surface
            viewW = max(1, width)
            viewH = max(1, height)
            surfaceAvailable = true
            if (pendingWallpaperBmp != null) wallpaperDirty = true
            if (pendingCoverBmp != null) coverDirty = true
            if (renderThread == null) {
                renderThread = GlRenderThread().also { it.start() }
            }
            desiredSeq++
            stateCond.signalAll()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderLock.withLock {
            viewW = max(1, width)
            viewH = max(1, height)
            desiredSeq++
            stateCond.signalAll()
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        shutdownRenderThread()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun onHostPause() {
        renderLock.withLock {
            renderThread?.paused = true
            stateCond.signalAll()
        }
    }

    fun onHostResume() {
        renderLock.withLock {
            renderThread?.paused = false
            desiredSeq++
            stateCond.signalAll()
        }
    }

    fun releaseGl() {
        shutdownRenderThread()
    }

    private fun shutdownRenderThread() {
        val threadToJoin: GlRenderThread?
        renderLock.withLock {
            surfaceAvailable = false
            threadToJoin = renderThread
            renderThread = null
            threadToJoin?.shouldExit = true
            stateCond.signalAll()
            frameDoneCond.signalAll()
        }
        try {
            threadToJoin?.join(600L)
        } catch (_: InterruptedException) {}
    }

    // -----------------------------------------------------------------------------------------
    // OPENGL ES 3.0 RENDER THREAD (Dual-Kawase Blur + Optical SDF Refraction)
    // -----------------------------------------------------------------------------------------
    private inner class GlRenderThread : Thread("SpatialGlassGL") {
        @Volatile var shouldExit = false
        @Volatile var paused = false

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var quadBuf: FloatBuffer? = null
        private var progScene = 0
        private var progKawaseDown = 0
        private var progKawaseUp = 0
        private var progBackdrop = 0
        private var progLens = 0

        private var wallpaperTex = 0
        private var coverTex = 0
        private var hasCoverTex = false

        // FBO pyramid
        private var sceneFbo = 0
        private var sceneTex = 0
        private var blurHalfFbo = 0
        private var blurHalfTex = 0
        private var blurQ1Fbo = 0
        private var blurQ1Tex = 0
        private var blurQ2Fbo = 0
        private var blurQ2Tex = 0
        private var blurUp1Fbo = 0
        private var blurUp1Tex = 0
        private var fboBaseW = 0
        private var fboBaseH = 0

        // Smoothed scene values (on GL thread)
        private var curBlur = 0f
        private var curDim = 0f
        private var curOrbEnergy = 0f
        private var curCoverAlpha = 0f
        private var sceneTexturesNeedBlur = true

        override fun run() {
            if (!initEgl()) {
                post { onGlStatusListener?.invoke(false, true) }
                return
            }
            var firstSwapDone = false
            try {
                initProgramsAndGeometry()
                var lastSeqRendered = 0L

                while (!shouldExit) {
                    var seqToRender = 0L
                    var lensesSnapshot: List<GlassLensSnapshot> = emptyList()
                    var wpBmp: Bitmap? = null
                    var covBmp: Bitmap? = null
                    var doWpUpload = false
                    var doCovUpload = false
                    var w = 1920
                    var h = 1080
                    var dstBlur = 0f
                    var dstDim = 0f
                    var dstOrb = 0f
                    var dstOrbColor = 0
                    var dstCoverAlpha = 0f

                    renderLock.lock()
                    try {
                        while (!shouldExit && (paused || !surfaceAvailable || (desiredSeq == lastSeqRendered && !isSceneAnimating()))) {
                            stateCond.await()
                        }
                        if (shouldExit) break
                        seqToRender = desiredSeq
                        lensesSnapshot = latestLenses
                        w = viewW
                        h = viewH
                        if (wallpaperDirty) {
                            wpBmp = pendingWallpaperBmp
                            wallpaperDirty = false
                            doWpUpload = true
                        }
                        if (coverDirty) {
                            covBmp = pendingCoverBmp
                            coverDirty = false
                            doCovUpload = true
                        }
                        dstBlur = targetBackdropBlur
                        dstDim = targetBackdropDim
                        dstOrb = targetOrbEnergy
                        dstOrbColor = targetOrbHueTint
                        dstCoverAlpha = targetMusicCoverAlpha
                    } finally {
                        renderLock.unlock()
                    }

                    if (doWpUpload && wpBmp != null) {
                        wallpaperTex = uploadBitmapTexture(wallpaperTex, wpBmp!!)
                        sceneTexturesNeedBlur = true
                    }
                    if (doCovUpload) {
                        if (covBmp != null && !covBmp!!.isRecycled) {
                            coverTex = uploadBitmapTexture(coverTex, covBmp!!)
                            hasCoverTex = true
                        } else {
                            hasCoverTex = false
                        }
                        sceneTexturesNeedBlur = true
                    }

                    val rate = motionRateScale
                    val nextBlur = SpatialGlassMath.smoothStep(curBlur, dstBlur, (0.18f * rate).coerceIn(0.07f, 0.72f))
                    val nextDim = SpatialGlassMath.smoothStep(curDim, dstDim, (0.18f * rate).coerceIn(0.07f, 0.72f))
                    val nextOrb = SpatialGlassMath.smoothStep(curOrbEnergy, dstOrb, (0.16f * rate).coerceIn(0.06f, 0.65f))
                    val nextCover = SpatialGlassMath.smoothStep(curCoverAlpha, dstCoverAlpha, (0.12f * rate).coerceIn(0.05f, 0.55f))

                    if (abs(nextCover - curCoverAlpha) > 0.002f || abs(nextOrb - curOrbEnergy) > 0.01f) {
                        sceneTexturesNeedBlur = true
                    }
                    curBlur = nextBlur
                    curDim = nextDim
                    curOrbEnergy = nextOrb
                    curCoverAlpha = nextCover

                    ensureFbos(w, h)
                    if (wallpaperTex != 0) {
                        if (sceneTexturesNeedBlur) {
                            renderSceneAndKawasePyramid(dstOrbColor)
                            sceneTexturesNeedBlur = false
                        }
                        renderFinalFrame(w, h, lensesSnapshot)
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                        if (!firstSwapDone) {
                            firstSwapDone = true
                            post { onGlStatusListener?.invoke(true, false) }
                        }
                    }

                    lastSeqRendered = seqToRender
                    renderLock.withLock {
                        if (completedSeq < seqToRender) completedSeq = seqToRender
                        frameDoneCond.signalAll()
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("SpatialGlassGL", "GL render failure", t)
                post { onGlStatusListener?.invoke(false, true) }
            } finally {
                destroyGlResources()
                destroyEgl()
            }
        }

        private fun isSceneAnimating(): Boolean =
            abs(curBlur - targetBackdropBlur) > 0.001f ||
                abs(curDim - targetBackdropDim) > 0.001f ||
                abs(curOrbEnergy - targetOrbEnergy) > 0.005f ||
                abs(curCoverAlpha - targetMusicCoverAlpha) > 0.001f

        private fun initEgl(): Boolean {
            val st = latestSurfaceTex ?: return false
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay === EGL14.EGL_NO_DISPLAY) return false
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false

            val cfgAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numCfg = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, configs, 0, 1, numCfg, 0) || numCfg[0] <= 0) {
                return false
            }
            val config = configs[0] ?: return false
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext === EGL14.EGL_NO_CONTEXT) return false

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, st, surfAttribs, 0)
            if (eglSurface === EGL14.EGL_NO_SURFACE) return false

            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        private fun initProgramsAndGeometry() {
            val fsQuad = floatArrayOf(
                -1f, -1f,  1f, -1f, -1f,  1f,
                 1f, -1f,  1f,  1f, -1f,  1f
            )
            quadBuf = ByteBuffer.allocateDirect(fsQuad.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(fsQuad)
                .apply { position(0) }

            val fsVert = """
                #version 300 es
                layout(location = 0) in vec2 aPos;
                out vec2 vUv;
                out vec2 vBmpUv;
                void main() {
                    gl_Position = vec4(aPos, 0.0, 1.0);
                    vUv = aPos * 0.5 + 0.5;
                    vBmpUv = vec2(vUv.x, 1.0 - vUv.y);
                }
            """.trimIndent()

            val lensVert = """
                #version 300 es
                layout(location = 0) in vec2 aPos;
                uniform vec4 uQuadNdc;
                void main() {
                    vec2 t = aPos * 0.5 + 0.5;
                    vec2 ndc = mix(uQuadNdc.xy, uQuadNdc.zw, t);
                    gl_Position = vec4(ndc, 0.0, 1.0);
                }
            """.trimIndent()

            // Pass 1: composit wallpaper + soft music cover + reactive Alice aura into FBO
            val sceneFrag = """
                #version 300 es
                precision highp float;
                in vec2 vUv;
                in vec2 vBmpUv;
                uniform sampler2D uWallTex;
                uniform sampler2D uCoverTex;
                uniform float uCoverAlpha;
                uniform float uOrbEnergy;
                uniform vec3 uOrbColor;
                out vec4 fragColor;
                void main() {
                    vec3 col = texture(uWallTex, vBmpUv).rgb;
                    if (uCoverAlpha > 0.005) {
                        vec3 cov = texture(uCoverTex, vBmpUv).rgb;
                        col = mix(col, cov * 0.72, uCoverAlpha);
                    }
                    if (uOrbEnergy > 0.005) {
                        vec2 d = (vUv - vec2(0.5, 0.52)) * vec2(1.777, 1.0);
                        float g = exp(-dot(d, d) * 4.2);
                        col += uOrbColor * g * 0.22 * uOrbEnergy;
                    }
                    fragColor = vec4(col, 1.0);
                }
            """.trimIndent()

            val kawaseDownFrag = """
                #version 300 es
                precision highp float;
                in vec2 vUv;
                uniform sampler2D uTex;
                uniform vec2 uHalfPixel;
                out vec4 fragColor;
                void main() {
                    vec4 sum = texture(uTex, vUv) * 4.0;
                    sum += texture(uTex, vUv - uHalfPixel.xy * 2.0);
                    sum += texture(uTex, vUv + uHalfPixel.xy * 2.0);
                    sum += texture(uTex, vUv + vec2(uHalfPixel.x * 2.0, -uHalfPixel.y * 2.0));
                    sum += texture(uTex, vUv - vec2(uHalfPixel.x * 2.0, -uHalfPixel.y * 2.0));
                    fragColor = sum * 0.125;
                }
            """.trimIndent()

            val kawaseUpFrag = """
                #version 300 es
                precision highp float;
                in vec2 vUv;
                uniform sampler2D uTex;
                uniform vec2 uHalfPixel;
                out vec4 fragColor;
                void main() {
                    vec4 sum = texture(uTex, vUv + vec2(-uHalfPixel.x * 2.0, 0.0));
                    sum += texture(uTex, vUv + vec2(-uHalfPixel.x, uHalfPixel.y)) * 2.0;
                    sum += texture(uTex, vUv + vec2(0.0, uHalfPixel.y * 2.0));
                    sum += texture(uTex, vUv + vec2(uHalfPixel.x, uHalfPixel.y)) * 2.0;
                    sum += texture(uTex, vUv + vec2(uHalfPixel.x * 2.0, 0.0));
                    sum += texture(uTex, vUv + vec2(uHalfPixel.x, -uHalfPixel.y)) * 2.0;
                    sum += texture(uTex, vUv + vec2(0.0, -uHalfPixel.y * 2.0));
                    sum += texture(uTex, vUv + vec2(-uHalfPixel.x, -uHalfPixel.y)) * 2.0;
                    fragColor = sum / 12.0;
                }
            """.trimIndent()

            val backdropFrag = """
                #version 300 es
                precision highp float;
                in vec2 vUv;
                in vec2 vBmpUv;
                uniform sampler2D uSharpWall;
                uniform sampler2D uCoverTex;
                uniform sampler2D uBlurTex;
                uniform float uBlurAmt;
                uniform float uDimAmt;
                uniform float uCoverAlpha;
                out vec4 fragColor;
                float ditherNoise(vec2 p) {
                    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
                }
                void main() {
                    vec3 sharp = texture(uSharpWall, vBmpUv).rgb;
                    if (uCoverAlpha > 0.005) {
                        vec3 blurred = texture(uBlurTex, vUv).rgb;
                        sharp = mix(sharp, blurred * 0.86, uCoverAlpha);
                    }
                    vec3 blurred = texture(uBlurTex, vUv).rgb;
                    vec3 col = mix(sharp, blurred, uBlurAmt);
                    col *= (1.0 - uDimAmt);
                    col += ditherNoise(gl_FragCoord.xy) / 255.0;
                    fragColor = vec4(col, 1.0);
                }
            """.trimIndent()

            // SDF Optical Liquid Glass Fragment Shader
            val lensFrag = """
                #version 300 es
                precision highp float;
                uniform vec2 uRes;
                uniform sampler2D uHalfTex;
                uniform sampler2D uBlurTex;
                uniform vec4 uRect;      // cx_gl, cy_gl, halfW, halfH
                uniform vec4 uParams;    // radius, bevelPx, refractPx, chromaPx
                uniform vec4 uState;     // alpha, focus, press, blurMix
                uniform vec4 uBaseTint;
                uniform vec4 uFocusTint;
                uniform vec4 uRimSpec;   // rimAlpha, focusRimAlpha, shadowPx, blurBackdropAmt
                out vec4 fragColor;

                float sdRoundBox(vec2 p, vec2 b, float r) {
                    vec2 q = abs(p) - b + vec2(r);
                    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
                }

                vec2 sdRoundBoxGrad(vec2 p, vec2 b, float r) {
                    vec2 s = sign(p);
                    vec2 q = abs(p) - b + vec2(r);
                    vec2 g = (q.x > 0.0 && q.y > 0.0)
                        ? normalize(q)
                        : ((q.x > q.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
                    return s * g;
                }

                void main() {
                    vec2 rel = gl_FragCoord.xy - uRect.xy;
                    float rad = uParams.x;
                    float sd = sdRoundBox(rel, uRect.zw, rad);

                    float alpha = uState.x;
                    float foc = uState.y;
                    float prs = uState.z;
                    float blurMix = uState.w;

                    // Soft ambient elevation shadow + crisp premultiplied antialiased glass edge
                    float shadowPx = max(1.0, uRimSpec.z);
                    float insideMask = 1.0 - smoothstep(-0.75, 0.75, sd);
                    float dOut = max(sd, 0.0);
                    float shadowFade = 1.0 - smoothstep(shadowPx * 1.35, shadowPx * 2.35, dOut);
                    float shadowAlpha = exp(-dOut / max(1.0, shadowPx * 0.42)) * shadowFade * (0.24 + 0.14 * foc) * (1.0 - insideMask);
                    if (insideMask <= 0.002) {
                        float a = shadowAlpha * alpha;
                        if (a <= 0.002) discard;
                        vec3 shRgb = vec3(0.0, 0.01, 0.03);
                        fragColor = vec4(shRgb * a, a);
                        return;
                    }

                    vec2 screenUv = gl_FragCoord.xy / uRes;
                    vec2 invRes = 1.0 / uRes;
                    vec2 n = sdRoundBoxGrad(rel, uRect.zw, rad);

                    // True physical Snell's Law (n1 * sin(theta_i) = n2 * sin(theta_t), lateral shift = T * tan(theta_i - theta_t))
                    float minHalf = max(8.0, min(uRect.z, uRect.w));
                    float bevel = clamp(max(uParams.y * 1.75, minHalf * 0.44), 14.0, minHalf);
                    float u = clamp(-sd / bevel, 0.0, 1.0); // 0 = outer lip, 1 = flat plateau
                    vec2 halfSafe = max(uRect.zw, vec2(1.0));
                    vec2 domeRel = rel / halfSafe;
                    float bevelSin = clamp((1.0 - u) * 0.90, 0.0, 0.90);
                    float domeSin = clamp(length(domeRel) * 0.24, 0.0, 0.36);
                    float sinThetaI = clamp(max(bevelSin, domeSin * (0.45 + 0.55 * (1.0 - u))), 0.0, 0.94);
                    float thetaI = asin(sinThetaI);

                    // Dispersion indices of crown Liquid Glass (Cauchy R/G/B) + effective slab thickness T (px)
                    float thicknessPx = max(24.0, uParams.z * (2.9 + 0.65 * foc + 0.25 * prs));
                    float dispSpan = max(0.03, uParams.w * 0.025) * (1.0 - 0.55 * u);
                    float iorR = 1.48 - dispSpan;
                    float iorG = 1.52;
                    float iorB = 1.56 + dispSpan;

                    float deltaR = thetaI - asin(clamp(sinThetaI / iorR, 0.0, 0.99));
                    float deltaG = thetaI - asin(clamp(sinThetaI / iorG, 0.0, 0.99));
                    float deltaB = thetaI - asin(clamp(sinThetaI / iorB, 0.0, 0.99));

                    vec2 domeDir = normalize(domeRel + vec2(1e-4, 1e-4));
                    float bevelWeight = smoothstep(0.05, 0.40, bevelSin);
                    vec2 surfNormalXY = normalize(mix(domeDir, n, bevelWeight) + vec2(1e-4));

                    // Tangent displacement tan(theta_i - theta_t) bends rays physically inward through thick curved lens
                    vec2 refrUvR = clamp(screenUv - surfNormalXY * (thicknessPx * tan(deltaR)) * invRes, vec2(0.002), vec2(0.998));
                    vec2 refrUvG = clamp(screenUv - surfNormalXY * (thicknessPx * tan(deltaG)) * invRes, vec2(0.002), vec2(0.998));
                    vec2 refrUvB = clamp(screenUv - surfNormalXY * (thicknessPx * tan(deltaB)) * invRes, vec2(0.002), vec2(0.998));

                    vec3 refrSharp = vec3(
                        texture(uHalfTex, refrUvR).r,
                        texture(uHalfTex, refrUvG).g,
                        texture(uHalfTex, refrUvB).b
                    );
                    vec3 refrBlur = texture(uBlurTex, refrUvG).rgb;

                    // Optical meniscus stays crystal clear so refracted geometry/stars visibly bend; interior frosts gently
                    float coreFrost = clamp((blurMix - 0.16) * (0.68 + 0.32 * u), 0.14, 0.78);
                    float finalFrost = mix(0.06, coreFrost, smoothstep(0.0, 0.62, u));
                    vec3 glassBg = mix(refrSharp, refrBlur, finalFrost);

                    // Subtle crystal vibrancy + highlight compression for text readability
                    float bgLuma = dot(glassBg, vec3(0.299, 0.587, 0.114));
                    glassBg = mix(vec3(bgLuma), glassBg, 1.18);
                    glassBg /= (1.0 + 0.36 * smoothstep(0.36, 0.88, bgLuma));

                    // Translucent sapphire/obsidian crystal tint
                    vec4 tint = mix(uBaseTint, uFocusTint, foc);
                    vec3 bodyCol = mix(glassBg, tint.rgb, tint.a * (0.80 + 0.20 * u));

                    // Internal TIR caustics & Schlick Fresnel specular catchlight from true cos(theta_i)
                    vec2 diag = domeRel;
                    float caustic = clamp(0.55 - 0.55 * (diag.x * 0.65 - diag.y * 0.75), 0.0, 1.0);
                    bodyCol += vec3(1.0) * pow(caustic, 2.2) * (0.025 + 0.070 * foc + 0.05 * prs);

                    float cosThetaI = sqrt(max(0.0, 1.0 - sinThetaI * sinThetaI));
                    float schlick = 0.04 + 0.96 * pow(clamp(1.0 - cosThetaI, 0.0, 1.0), 4.0);
                    float tirBand = exp(-pow((-sd - bevel * 0.34) / max(3.5, bevel * 0.24), 2.0));
                    float tlDir = clamp(dot(n, normalize(vec2(-0.62, 0.78))), 0.0, 1.0);
                    float brDir = clamp(dot(n, normalize(vec2(0.62, -0.78))), 0.0, 1.0);
                    bodyCol += vec3(0.76, 0.90, 1.0) * tirBand * (0.04 + 0.10 * brDir + 0.08 * foc) * (0.55 + 0.45 * schlick);

                    float rimBand = exp(-abs(sd + 0.9) * 1.15);
                    float specAmt = pow(tlDir, 1.45) * 0.96 + pow(brDir, 2.0) * 0.34 + 0.07;
                    float rimStrength = mix(uRimSpec.x, uRimSpec.y, foc);
                    bodyCol += vec3(0.95, 0.98, 1.0) * rimBand * specAmt * rimStrength * (0.72 + 0.45 * schlick);

                    vec3 shCol = vec3(0.0, 0.01, 0.03);
                    vec3 premulRgb = bodyCol * insideMask + shCol * shadowAlpha * (1.0 - insideMask);
                    float outAlpha = clamp(insideMask + shadowAlpha * (1.0 - insideMask), 0.0, 1.0);
                    fragColor = vec4(premulRgb * alpha, outAlpha * alpha);
                }
            """.trimIndent()

            progScene = linkProgram(fsVert, sceneFrag)
            progKawaseDown = linkProgram(fsVert, kawaseDownFrag)
            progKawaseUp = linkProgram(fsVert, kawaseUpFrag)
            progBackdrop = linkProgram(fsVert, backdropFrag)
            progLens = linkProgram(lensVert, lensFrag)
        }

        private fun ensureFbos(w: Int, h: Int) {
            val bw = (w / 2).coerceIn(320, 960)
            val bh = (h / 2).coerceIn(180, 540)
            if (bw == fboBaseW && bh == fboBaseH && sceneFbo != 0) return

            destroyFbos()
            fboBaseW = bw
            fboBaseH = bh
            createFbo(bw, bh).also { sceneFbo = it.first; sceneTex = it.second }
            createFbo(bw, bh).also { blurHalfFbo = it.first; blurHalfTex = it.second }
            createFbo(max(64, bw / 2), max(36, bh / 2)).also { blurQ1Fbo = it.first; blurQ1Tex = it.second }
            createFbo(max(32, bw / 4), max(18, bh / 4)).also { blurQ2Fbo = it.first; blurQ2Tex = it.second }
            createFbo(max(64, bw / 2), max(36, bh / 2)).also { blurUp1Fbo = it.first; blurUp1Tex = it.second }
            sceneTexturesNeedBlur = true
        }

        private fun renderSceneAndKawasePyramid(orbColor: Int) {
            val w = fboBaseW
            val h = fboBaseH
            val qw1 = max(64, w / 2)
            val qh1 = max(36, h / 2)
            val qw2 = max(32, w / 4)
            val qh2 = max(18, h / 4)

            // 1. Scene Pass -> sceneFbo
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glUseProgram(progScene)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, wallpaperTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progScene, "uWallTex"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasCoverTex) coverTex else wallpaperTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progScene, "uCoverTex"), 1)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(progScene, "uCoverAlpha"), if (hasCoverTex) curCoverAlpha else 0f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(progScene, "uOrbEnergy"), curOrbEnergy)
            GLES30.glUniform3f(
                GLES30.glGetUniformLocation(progScene, "uOrbColor"),
                Color.red(orbColor) / 255f,
                Color.green(orbColor) / 255f,
                Color.blue(orbColor) / 255f
            )
            drawQuad()

            // 2. Dual-Kawase Downsample: scene -> q1 -> q2
            runKawasePass(progKawaseDown, sceneTex, blurQ1Fbo, qw1, qh1, 1.5f / w, 1.5f / h)
            runKawasePass(progKawaseDown, blurQ1Tex, blurQ2Fbo, qw2, qh2, 1.8f / qw1, 1.8f / qh1)

            // 3. Dual-Kawase Upsample: q2 -> up1 -> halfBlur
            runKawasePass(progKawaseUp, blurQ2Tex, blurUp1Fbo, qw1, qh1, 1.8f / qw2, 1.8f / qh2)
            runKawasePass(progKawaseUp, blurUp1Tex, blurHalfFbo, w, h, 1.6f / qw1, 1.6f / qh1)
        }

        private fun runKawasePass(program: Int, srcTex: Int, dstFbo: Int, dstW: Int, dstH: Int, hx: Float, hy: Float) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo)
            GLES30.glViewport(0, 0, dstW, dstH)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uHalfPixel"), hx, hy)
            drawQuad()
        }

        private fun renderFinalFrame(screenW: Int, screenH: Int, lenses: List<GlassLensSnapshot>) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, screenW, screenH)
            GLES30.glDisable(GLES30.GL_BLEND)

            // 1. Fullscreen sharp/blurred backdrop
            GLES30.glUseProgram(progBackdrop)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, wallpaperTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progBackdrop, "uSharpWall"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasCoverTex) coverTex else wallpaperTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progBackdrop, "uCoverTex"), 1)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurHalfTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progBackdrop, "uBlurTex"), 2)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(progBackdrop, "uBlurAmt"), curBlur)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(progBackdrop, "uDimAmt"), curDim)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(progBackdrop, "uCoverAlpha"), if (hasCoverTex) curCoverAlpha else 0f)
            drawQuad()

            if (lenses.isEmpty()) return

            // 2. Per-lens optical SDF refraction passes (premultiplied blend for seam-free corners)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUseProgram(progLens)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(progLens, "uRes"), screenW.toFloat(), screenH.toFloat())
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progLens, "uHalfTex"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurHalfTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progLens, "uBlurTex"), 1)

            val uQuadNdc = GLES30.glGetUniformLocation(progLens, "uQuadNdc")
            val uRect = GLES30.glGetUniformLocation(progLens, "uRect")
            val uParams = GLES30.glGetUniformLocation(progLens, "uParams")
            val uState = GLES30.glGetUniformLocation(progLens, "uState")
            val uBaseTint = GLES30.glGetUniformLocation(progLens, "uBaseTint")
            val uFocusTint = GLES30.glGetUniformLocation(progLens, "uFocusTint")
            val uRimSpec = GLES30.glGetUniformLocation(progLens, "uRimSpec")

            val fw = screenW.toFloat()
            val fh = screenH.toFloat()

            for (i in 0 until lenses.size) {
                val s = lenses[i]
                val pad = s.shadowPx * 3.0f + 8f
                val ndcL = ((s.leftPx - pad) / fw) * 2f - 1f
                val ndcR = ((s.rightPx + pad) / fw) * 2f - 1f
                val ndcT = 1f - ((s.topPx - pad) / fh) * 2f
                val ndcB = 1f - ((s.bottomPx + pad) / fh) * 2f

                val hasClip = s.clipLeftPx > 0f || s.clipTopPx > 0f || s.clipRightPx < fw || s.clipBottomPx < fh
                if (hasClip) {
                    val sx = s.clipLeftPx.toInt().coerceIn(0, screenW)
                    val syBot = (screenH - s.clipBottomPx.toInt()).coerceIn(0, screenH)
                    val sw = (s.clipRightPx - s.clipLeftPx).toInt().coerceAtLeast(0)
                    val sh = (s.clipBottomPx - s.clipTopPx).toInt().coerceAtLeast(0)
                    GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                    GLES30.glScissor(sx, syBot, sw, sh)
                }

                val glRect = SpatialGlassMath.toGlLensRect(s.leftPx, s.topPx, s.rightPx, s.bottomPx, fh)
                GLES30.glUniform4f(uQuadNdc, ndcL, ndcB, ndcR, ndcT)
                GLES30.glUniform4f(uRect, glRect[0], glRect[1], glRect[2], glRect[3])
                GLES30.glUniform4f(uParams, s.radiusPx, s.bevelPx, s.refractPx, s.chromaPx)
                GLES30.glUniform4f(uState, s.alpha, s.focus, s.press, s.blurMix)
                GLES30.glUniform4f(
                    uBaseTint,
                    Color.red(s.baseTint) / 255f,
                    Color.green(s.baseTint) / 255f,
                    Color.blue(s.baseTint) / 255f,
                    Color.alpha(s.baseTint) / 255f
                )
                GLES30.glUniform4f(
                    uFocusTint,
                    Color.red(s.focusTint) / 255f,
                    Color.green(s.focusTint) / 255f,
                    Color.blue(s.focusTint) / 255f,
                    Color.alpha(s.focusTint) / 255f
                )
                GLES30.glUniform4f(uRimSpec, s.rimAlpha, s.focusRimAlpha, s.shadowPx, curBlur)
                drawQuad()

                if (hasClip) {
                    GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                }
            }
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        private fun drawQuad() {
            val buf = quadBuf ?: return
            buf.position(0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, buf)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
            GLES30.glDisableVertexAttribArray(0)
        }

        private fun createFbo(w: Int, h: Int): Pair<Int, Int> {
            val fbos = IntArray(1)
            val texs = IntArray(1)
            GLES30.glGenFramebuffers(1, fbos, 0)
            GLES30.glGenTextures(1, texs, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texs[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texs[0], 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return fbos[0] to texs[0]
        }

        private fun uploadBitmapTexture(existingTex: Int, bmp: Bitmap): Int {
            val ids = intArrayOf(existingTex)
            if (ids[0] == 0) GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            return ids[0]
        }

        private fun linkProgram(vsSrc: String, fsSrc: String): Int {
            val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc)
            val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, vs)
            GLES30.glAttachShader(p, fs)
            GLES30.glLinkProgram(p)
            val linked = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val info = GLES30.glGetProgramInfoLog(p)
                GLES30.glDeleteProgram(p)
                throw IllegalStateException("GL program link error: $info")
            }
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return p
        }

        private fun compileShader(type: Int, src: String): Int {
            val sh = GLES30.glCreateShader(type)
            GLES30.glShaderSource(sh, src)
            GLES30.glCompileShader(sh)
            val compiled = IntArray(1)
            GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val info = GLES30.glGetShaderInfoLog(sh)
                GLES30.glDeleteShader(sh)
                throw IllegalStateException("GL shader compile error ($type): $info")
            }
            return sh
        }

        private fun destroyFbos() {
            val fbos = intArrayOf(sceneFbo, blurHalfFbo, blurQ1Fbo, blurQ2Fbo, blurUp1Fbo).filter { it != 0 }.toIntArray()
            val texs = intArrayOf(sceneTex, blurHalfTex, blurQ1Tex, blurQ2Tex, blurUp1Tex).filter { it != 0 }.toIntArray()
            if (fbos.isNotEmpty()) GLES30.glDeleteFramebuffers(fbos.size, fbos, 0)
            if (texs.isNotEmpty()) GLES30.glDeleteTextures(texs.size, texs, 0)
            sceneFbo = 0; sceneTex = 0; blurHalfFbo = 0; blurHalfTex = 0
            blurQ1Fbo = 0; blurQ1Tex = 0; blurQ2Fbo = 0; blurQ2Tex = 0
            blurUp1Fbo = 0; blurUp1Tex = 0
            fboBaseW = 0; fboBaseH = 0
        }

        private fun destroyGlResources() {
            destroyFbos()
            val texs = intArrayOf(wallpaperTex, coverTex).filter { it != 0 }.toIntArray()
            if (texs.isNotEmpty()) GLES30.glDeleteTextures(texs.size, texs, 0)
            listOf(progScene, progKawaseDown, progKawaseUp, progBackdrop, progLens)
                .filter { it != 0 }
                .forEach { GLES30.glDeleteProgram(it) }
        }

        private fun destroyEgl() {
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}
