package com.openstation.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * Global flagship motion engine shared across the launcher, Control Center,
 * Spatial Music Player, and Scenic Screensaver.
 */
object MotionPrefs {
    private const val PREFS_NAME = "openstation_ui"
    private const val KEY_SPEED = "anim_speed_mode"

    enum class Speed(
        val key: String,
        val label: String,
        val shortLabel: String,
        val multiplier: Float
    ) {
        VELVET("velvet", "Бархат · 1.45×", "Бархат", 1.45f),
        SMOOTH("smooth", "Плавная · 1.0×", "Плавная", 1.00f),
        VIVID("vivid", "Живая · 0.7×", "Живая", 0.70f),
        FAST("fast", "Быстрая · 0.4×", "Быстрая", 0.40f)
    }

    @Volatile
    private var cachedSpeed: Speed? = null

    /** Ultra-smooth visionOS / iOS spring-like cubic-out curve with a long, velvety landing tail. */
    val easeOutCurve: Interpolator = PathInterpolator(0.16f, 1.0f, 0.30f, 1.0f)

    /** Balanced ease-in-out curve for panel resizing, accordions, and gliding displacements. */
    val easeInOutCurve: Interpolator = PathInterpolator(0.32f, 0.04f, 0.20f, 1.0f)

    /** Gentle tactile focus curve with subtle weighted settle (zero harsh overshoot bounce). */
    val focusCurve: Interpolator = PathInterpolator(0.20f, 1.18f, 0.30f, 1.0f)

    fun current(context: Context): Speed {
        cachedSpeed?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SPEED, Speed.SMOOTH.key)
        val resolved = Speed.values().firstOrNull { it.key == raw } ?: Speed.SMOOTH
        cachedSpeed = resolved
        return resolved
    }

    fun setSpeed(context: Context, speed: Speed) {
        cachedSpeed = speed
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SPEED, speed.key)
            .apply()
    }

    fun cycle(context: Context): Speed {
        val values = Speed.values()
        val cur = current(context)
        val next = values[(cur.ordinal + 1) % values.size]
        setSpeed(context, next)
        return next
    }

    fun dur(context: Context, baseMs: Long, minMs: Long = 60L): Long {
        val scaled = (baseMs * current(context).multiplier).toLong()
        return scaled.coerceAtLeast(minMs)
    }

    /** Velocity multiplier for GL uniform smoothing lerp rates (higher multiplier = slower lerp). */
    fun glSmoothFactor(context: Context): Float {
        val mul = current(context).multiplier
        return (1f / mul.coerceAtLeast(0.35f)).coerceIn(0.65f, 2.6f)
    }
}

/**
 * Shared monolithic Spatial Liquid Glass surface builders & UI helpers so every sheet,
 * card, pill, and row stays lock-step with HWUI scroll/translation (zero detached child quads).
 */
object SpatialUiKit {
    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    fun disableSystemHighlight(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
    }

    fun makeGlassSurface(
        context: Context,
        radiusDp: Float = 18f,
        normalTop: Int = Color.parseColor("#24121D34"),
        normalBottom: Int = Color.parseColor("#160B1324"),
        focusedTop: Int = Color.parseColor("#54223B68"),
        focusedBottom: Int = Color.parseColor("#3415284A"),
        selectedTop: Int = Color.parseColor("#3E1A3552"),
        selectedBottom: Int = Color.parseColor("#24102238"),
        normalStroke: Int = Color.parseColor("#28FFFFFF"),
        focusedStroke: Int = Color.parseColor("#A0BAE6FD"),
        selectedStroke: Int = Color.parseColor("#76E8C686")
    ): Drawable {
        val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics)
        val strokeNorm = dp(context, 1).coerceAtLeast(1)
        val strokeFocus = dp(context, 2).coerceAtLeast(2)

        fun layer(top: Int, bottom: Int, stroke: Int, sw: Int): GradientDrawable =
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
                cornerRadius = r
                setStroke(sw, stroke)
            }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), layer(focusedTop, focusedBottom, focusedStroke, strokeFocus))
            addState(intArrayOf(android.R.attr.state_pressed), layer(focusedTop, focusedBottom, focusedStroke, strokeFocus))
            addState(intArrayOf(android.R.attr.state_selected), layer(selectedTop, selectedBottom, selectedStroke, strokeNorm))
            addState(intArrayOf(), layer(normalTop, normalBottom, normalStroke, strokeNorm))
        }
    }

    fun makeGlassAccentSurface(
        context: Context,
        radiusDp: Float = 999f
    ): Drawable = makeGlassSurface(
        context = context,
        radiusDp = radiusDp,
        normalTop = Color.parseColor("#52543E1A"),
        normalBottom = Color.parseColor("#363A2910"),
        focusedTop = Color.parseColor("#88755622"),
        focusedBottom = Color.parseColor("#5A4D3612"),
        selectedTop = Color.parseColor("#6A634A1E"),
        selectedBottom = Color.parseColor("#44423012"),
        normalStroke = Color.parseColor("#6EE8C686"),
        focusedStroke = Color.parseColor("#D8FDE68A"),
        selectedStroke = Color.parseColor("#99E8C686")
    )

    fun makeFrostedProgressDrawable(context: Context): Drawable {
        val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, context.resources.displayMetrics)
        val track = GradientDrawable().apply {
            setColor(Color.parseColor("#24FFFFFF"))
            cornerRadius = r
        }
        val fillShape = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#7DD3FC"), Color.parseColor("#FDE68A"))
        ).apply {
            cornerRadius = r
        }
        val progress = ClipDrawable(fillShape, Gravity.START, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf(track, progress)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }
}
