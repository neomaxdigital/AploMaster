package com.aploworks.aplomaster.audio

import com.aploworks.aplomaster.domain.GainState
import com.aploworks.aplomaster.domain.LoudnessAnalysisResult
import kotlin.math.min

object AutoGainCalculator {
    const val TARGET_LUFS = -13f
    const val TRUE_PEAK_CEILING_DBTP = -1f

    fun calculate(result: LoudnessAnalysisResult, manualOffsetDb: Float = 0f): GainState {
        val loudness = result.integratedLufs
        val truePeak = result.truePeakDbtp
        if (!result.success || loudness == null || truePeak == null) {
            return GainState(manualOffsetDb = manualOffsetDb)
        }
        val desiredGain = TARGET_LUFS - loudness
        val peakLimitedGain = TRUE_PEAK_CEILING_DBTP - truePeak
        return GainState(
            autoGainDb = min(desiredGain, peakLimitedGain),
            manualOffsetDb = manualOffsetDb,
        )
    }
}
