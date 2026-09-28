package com.aploworks.aplomaster.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aploworks.aplomaster.domain.AudioPlaybackState
import com.aploworks.aplomaster.domain.PlaybackStatus

class AudioPlayerController(
    context: Context,
    private val onStateChanged: (AudioPlaybackState) -> Unit,
) : Player.Listener {
    private val player = ExoPlayer.Builder(context.applicationContext).build().apply {
        addListener(this@AudioPlayerController)
    }
    private val handler = Handler(Looper.getMainLooper())
    private var state = AudioPlaybackState()
    private var released = false

    private val progressUpdate = object : Runnable {
        override fun run() {
            if (released) return
            publishPosition()
            if (player.isPlaying) handler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
        }
    }

    fun load(uri: Uri, fileName: String) {
        handler.removeCallbacks(progressUpdate)
        player.stop()
        player.clearMediaItems()
        state = AudioPlaybackState(
            status = PlaybackStatus.PREPARING,
            uri = uri,
            fileName = fileName,
        )
        publish(state)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun togglePlayPause(): Boolean {
        if (!state.hasAudio || released) return false
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        if (player.isPlaying) player.pause() else player.play()
        return true
    }

    fun seekToFraction(fraction: Float) {
        val duration = player.duration.takeIf { it > 0L } ?: return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        publishPosition()
    }

    fun pause() {
        if (player.isPlaying) player.pause()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val status = when (playbackState) {
            Player.STATE_BUFFERING -> PlaybackStatus.PREPARING
            Player.STATE_READY -> if (player.isPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PREPARED
            Player.STATE_ENDED -> PlaybackStatus.ENDED
            else -> state.status
        }
        publishPosition(status)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val status = when {
            isPlaying -> PlaybackStatus.PLAYING
            player.playbackState == Player.STATE_ENDED -> PlaybackStatus.ENDED
            state.hasAudio && player.playbackState == Player.STATE_READY -> PlaybackStatus.PAUSED
            else -> state.status
        }
        publishPosition(status)
        handler.removeCallbacks(progressUpdate)
        if (isPlaying) handler.post(progressUpdate)
    }

    override fun onPlayerError(error: PlaybackException) {
        handler.removeCallbacks(progressUpdate)
        publish(
            state.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = error.localizedMessage ?: "Não foi possível reproduzir este áudio.",
            ),
        )
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        player.removeListener(this)
        player.release()
    }

    private fun publishPosition(status: PlaybackStatus = state.status) {
        val duration = player.duration.takeIf { it > 0L } ?: state.durationMs
        val position = player.currentPosition.coerceAtLeast(0L)
        publish(state.copy(status = status, positionMs = position, durationMs = duration))
    }

    private fun publish(newState: AudioPlaybackState) {
        state = newState
        onStateChanged(state)
    }

    private companion object {
        const val POSITION_UPDATE_INTERVAL_MS = 250L
    }
}
