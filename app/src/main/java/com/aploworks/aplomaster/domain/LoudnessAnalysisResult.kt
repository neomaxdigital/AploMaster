package com.aploworks.aplomaster.domain

data class LoudnessAnalysisResult(
    val integratedLufs: Float?,
    val truePeakDbtp: Float?,
    val samplePeakDbfs: Float?,
    val success: Boolean,
    val errorMessage: String? = null,
) {
    companion object {
        fun failure(message: String? = null) = LoudnessAnalysisResult(
            integratedLufs = null,
            truePeakDbtp = null,
            samplePeakDbfs = null,
            success = false,
            errorMessage = message,
        )
    }
}
