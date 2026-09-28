package com.aploworks.aplomaster.domain

import android.net.Uri

enum class PlaybackStatus {
    EMPTY,
    PREPARING,
    PREPARED,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

data class AudioPlaybackState(
    val status: PlaybackStatus = PlaybackStatus.EMPTY,
    val uri: Uri? = null,
    val fileName: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
) {
    val hasAudio: Boolean get() = uri != null
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
}
