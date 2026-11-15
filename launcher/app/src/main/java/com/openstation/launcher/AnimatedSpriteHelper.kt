package com.openstation.launcher

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView

/**
 * Loads raster sprites including looping animated GIF/WebP drawables on API 28+ (Android 9+).
 */
object AnimatedSpriteHelper {
    fun loadDrawable(context: Context, resId: Int): Drawable? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                val src = ImageDecoder.createSource(context.resources, resId)
                ImageDecoder.decodeDrawable(src)
            }.getOrNull()
            if (decoded != null) {
                if (decoded is AnimatedImageDrawable) {
                    decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    decoded.start()
                } else if (decoded is Animatable) {
                    decoded.start()
                }
                return decoded
            }
        }
        return runCatching { context.getDrawable(resId) }.getOrNull()
    }
}

fun ImageView.setSpriteDrawable(resId: Int) {
    val d = AnimatedSpriteHelper.loadDrawable(context, resId)
    if (d != null) {
        setImageDrawable(d)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) {
            d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            d.start()
        } else if (d is Animatable) {
            d.start()
        }
    } else {
        setImageResource(resId)
    }
}
