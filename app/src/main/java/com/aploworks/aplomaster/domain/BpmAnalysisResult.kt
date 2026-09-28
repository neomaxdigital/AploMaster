package com.aploworks.aplomaster.domain

data class BpmAnalysisResult(
    val bpm: Float?,
    val confidence: Float,
    val success: Boolean,
) {
    init {
        require(confidence in 0f..1f)
        require(success == (bpm != null))
    }

    companion object {
        fun failure(confidence: Float = 0f) = BpmAnalysisResult(
            bpm = null,
            confidence = confidence.coerceIn(0f, 1f),
            success = false,
        )
    }
}
