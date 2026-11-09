package me.kavishdevar.librepods.presentation.overlays

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Hardware-composited looping video that follows the parent window's transforms. */
class LoopingVideoTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var playerSurface: Surface? = null
    private var videoResource: Int = 0
    private var playWhenReady = false
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    init {
        surfaceTextureListener = this
    }

    fun play(videoResource: Int) {
        if (videoResource == 0) return
        this.videoResource = videoResource
        playWhenReady = true
        if (isAvailable) surfaceTexture?.let(::openPlayer)
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (videoWidth == width && videoHeight == height) return

        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val measuredWidth = View.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val contentWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = (
            (contentWidth.toLong() * videoHeight + videoWidth / 2) / videoWidth
        ).toInt()
        val desiredHeight = contentHeight + paddingTop + paddingBottom
        val measuredHeight = View.resolveSize(desiredHeight, heightMeasureSpec)

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    fun pause() {
        playWhenReady = false
        runCatching {
            player?.takeIf { it.isPlaying }?.pause()
        }
    }

    fun releasePlayer() {
        playWhenReady = false
        releaseInternal()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (playWhenReady) openPlayer(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releaseInternal()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }

    private fun openPlayer(surfaceTexture: SurfaceTexture) {
        releaseInternal()
        val resource = videoResource.takeIf { it != 0 } ?: return
        val nextPlayer = MediaPlayer()
        player = nextPlayer
        try {
            val surface = Surface(surfaceTexture)
            playerSurface = surface
            resources.openRawResourceFd(resource).use { descriptor ->
                nextPlayer.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length,
                )
            }
            nextPlayer.setSurface(surface)
            nextPlayer.setVolume(0f, 0f)
            nextPlayer.isLooping = true
            nextPlayer.setOnPreparedListener { prepared ->
                if (player === prepared && playWhenReady) prepared.start()
            }
            nextPlayer.setOnErrorListener { failed, what, extra ->
                Log.e("LoopingVideoTextureView", "Playback error what=$what extra=$extra")
                if (player === failed) releaseInternal()
                true
            }
            nextPlayer.prepareAsync()
        } catch (e: Exception) {
            Log.e("LoopingVideoTextureView", "Unable to open video resource $resource", e)
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        val activePlayer = player
        player = null
        val surface = playerSurface
        playerSurface = null
        if (activePlayer == null) {
            surface?.release()
            return
        }
        runCatching { activePlayer.pause() }
        releaseExecutor.execute {
            runCatching { activePlayer.setSurface(null) }
            runCatching { activePlayer.release() }
            surface?.release()
        }
    }

    private companion object {
        val releaseExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "LoopingVideoTextureView-release").apply { isDaemon = true }
        }
    }
}
