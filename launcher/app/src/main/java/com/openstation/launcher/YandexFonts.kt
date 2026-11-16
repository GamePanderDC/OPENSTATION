package com.openstation.launcher

import android.content.Context
import android.graphics.Typeface

/**
 * Cached Yandex Sans typefaces bundled in res/font (sourced from Nebula donor UI).
 * Falls back safely to system Typeface if a font resource cannot be decoded.
 */
object YandexFonts {
    @Volatile private var dispRegular: Typeface? = null
    @Volatile private var dispMedium: Typeface? = null
    @Volatile private var dispBold: Typeface? = null
    @Volatile private var txtMedium: Typeface? = null

    private fun loadFont(ctx: Context, resId: Int, fallback: Typeface): Typeface {
        return try {
            ctx.applicationContext.resources.getFont(resId) ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    fun displayRegular(ctx: Context): Typeface =
        dispRegular ?: loadFont(ctx, R.font.ys_display_regular, Typeface.SANS_SERIF).also { dispRegular = it }

    fun displayMedium(ctx: Context): Typeface =
        dispMedium ?: loadFont(
            ctx,
            R.font.ys_display_medium,
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ).also { dispMedium = it }

    fun displayBold(ctx: Context): Typeface =
        dispBold ?: loadFont(ctx, R.font.ys_display_bold, Typeface.DEFAULT_BOLD).also { dispBold = it }

    fun textMedium(ctx: Context): Typeface =
        txtMedium ?: loadFont(ctx, R.font.ys_text_medium, Typeface.SANS_SERIF).also { txtMedium = it }
}
