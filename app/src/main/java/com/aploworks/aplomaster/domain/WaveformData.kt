package com.aploworks.aplomaster.domain

data class WaveformData(
    val amplitudes: FloatArray,
) {
    init {
        require(amplitudes.all { it in 0f..1f })
    }
}
