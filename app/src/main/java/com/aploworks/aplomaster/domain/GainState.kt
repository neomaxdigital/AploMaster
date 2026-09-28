package com.aploworks.aplomaster.domain

data class GainState(
    val autoGainDb: Float = 0f,
    val manualOffsetDb: Float = 0f,
) {
    val finalGainDb: Float get() = autoGainDb + manualOffsetDb
}
