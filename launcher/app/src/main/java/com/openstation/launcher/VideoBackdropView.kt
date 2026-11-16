package com.openstation.launcher

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView

/** Muted hardware-decoded screensaver video. */
class VideoBackdropView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var running = false
    private var prepared = false

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setRunning(enabled: Boolean) {
        running = enabled
        visibility = if (enabled) VISIBLE else GONE
        if (!enabled) {
            releasePlayer()
            return
        }
        val p = player
        if (p != null && prepared) {
            try { p.start() } catch (_: IllegalStateException) { releasePlayer() }
        } else if (isAvailable && surfaceTexture != null && p == null) {
            createPlayer(surfaceTexture!!)
        }
    }

    private fun createPlayer(texture: SurfaceTexture) {
        releasePlayer()
        try {
            val output = Surface(texture)
            surface = output
            val p = MediaPlayer()
            player = p
            p.setSurface(output)
            p.setVolume(0f, 0f)
            p.isLooping = true
            p.setDataSource(context, Uri.parse("android.resource://${context.packageName}/${R.raw.saver_earth}"))
            p.setOnPreparedListener {
                if (it !== player) return@setOnPreparedListener
                prepared = true
                if (running) it.start()
            }
            p.setOnErrorListener { _, _, _ -> releasePlayer(); true }
            p.prepareAsync()
        } catch (_: Exception) { releasePlayer() }
    }

    private fun releasePlayer() {
        prepared = false
        val old = player
        player = null
        try { old?.release() } catch (_: Exception) { }
        surface?.release()
        surface = null
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (running) createPlayer(texture)
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { releasePlayer(); return true }
    override fun onDetachedFromWindow() { releasePlayer(); super.onDetachedFromWindow() }
}
