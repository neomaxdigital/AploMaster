package com.aploworks.aplomaster.ui

import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aploworks.aplomaster.R
import com.aploworks.aplomaster.audio.AudioPlayerController
import com.aploworks.aplomaster.audio.BpmAnalysisController
import com.aploworks.aplomaster.audio.AutoGainCalculator
import com.aploworks.aplomaster.audio.LoudnessAnalysisController
import com.aploworks.aplomaster.audio.WaveformAnalysisController
import com.aploworks.aplomaster.domain.AudioPlaybackState
import com.aploworks.aplomaster.domain.PlaybackStatus
import com.aploworks.aplomaster.domain.WaveformData
import com.aploworks.aplomaster.domain.GainState
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var audioPlayerController: AudioPlayerController
    private lateinit var bpmAnalysisController: BpmAnalysisController
    private lateinit var loudnessAnalysisController: LoudnessAnalysisController
    private lateinit var waveformAnalysisController: WaveformAnalysisController
    private var responsiveLayout: ResponsiveBlockLayout? = null
    private var playbackState = AudioPlaybackState()
    private var waveformData: WaveformData? = null
    private var bpmValue: Int? = null
    private var gainState = GainState()
    private var volumeText = formatGain(0f)
    private var lastShownError: String? = null
    private val showMainScreen = Runnable { displayMainScreen() }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSelectedAudio(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeWindow()
        audioPlayerController = AudioPlayerController(this, ::onPlaybackStateChanged)
        bpmAnalysisController = BpmAnalysisController(this)
        loudnessAnalysisController = LoudnessAnalysisController(this)
        waveformAnalysisController = WaveformAnalysisController(this)

        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(WINDOW_BACKGROUND_COLOR)
        }

        setContentView(root)
        displayIntro()
    }

    override fun onDestroy() {
        root.removeCallbacks(showMainScreen)
        responsiveLayout = null
        audioPlayerController.release()
        bpmAnalysisController.release()
        loudnessAnalysisController.release()
        waveformAnalysisController.release()
        super.onDestroy()
    }

    override fun onStop() {
        audioPlayerController.pause()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdgeWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(WINDOW_BACKGROUND_COLOR))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun displayIntro() {
        val intro = createImageView(R.drawable.aplomaster_intro_opcao_2_1080x2400)
        root.addView(intro)
        intro.postDelayed(showMainScreen, INTRO_DURATION_MILLIS)
    }

    private fun displayMainScreen() {
        root.removeAllViews()

        val background = createImageView(
            R.drawable.fundo_masterizacao_1080x2400,
            ImageView.ScaleType.CENTER_CROP,
        )
        val blocks = ResponsiveBlockLayout(this)
        responsiveLayout = blocks
        blocks.onImportClick = {
            audioPicker.launch(arrayOf("audio/*"))
        }
        blocks.onPlayPauseClick = {
            if (!audioPlayerController.togglePlayPause()) {
                Toast.makeText(this, "Importe um arquivo de áudio primeiro.", Toast.LENGTH_SHORT).show()
            }
        }
        blocks.onSeekRequested = audioPlayerController::seekToFraction
        blocks.updatePlaybackTimes(playbackState.positionMs, playbackState.durationMs)
        blocks.updatePlaybackProgress(playbackState.positionMs, playbackState.durationMs)
        blocks.updateWaveform(waveformData)
        blocks.updateBpm(bpmValue)
        blocks.updateVolumeText(volumeText)
        val composition = FrameLayout(this).apply {
            layoutParams = matchParentLayoutParams()
        }
        composition.addView(background)
        composition.addView(
            blocks,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        ViewCompat.setOnApplyWindowInsetsListener(blocks) { _, insets ->
            blocks.updateSafeInsets(
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout(),
                ),
            )
            insets
        }

        root.addView(composition)
        ViewCompat.requestApplyInsets(blocks)
    }

    private fun loadSelectedAudio(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val fileName = queryDisplayName(uri) ?: "Áudio selecionado"
        waveformData = null
        bpmValue = null
        gainState = GainState()
        volumeText = "..."
        responsiveLayout?.updateWaveform(null)
        responsiveLayout?.updateBpm(null)
        responsiveLayout?.updateVolumeText(volumeText)
        audioPlayerController.load(uri, fileName)
        waveformAnalysisController.analyze(
            uri = uri,
            onComplete = { data ->
                waveformData = data
                responsiveLayout?.updateWaveform(data)
            },
            onError = { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            },
        )
        bpmAnalysisController.analyze(
            uri = uri,
            onComplete = { result ->
                bpmValue = result.bpm?.roundToInt()
                responsiveLayout?.updateBpm(bpmValue)
            },
            onError = {
                bpmValue = null
                responsiveLayout?.updateBpm(null)
            },
        )
        loudnessAnalysisController.analyze(uri) { result ->
            gainState = AutoGainCalculator.calculate(result)
            volumeText = if (result.success) formatGain(gainState.finalGainDb) else formatGain(0f)
            responsiveLayout?.updateVolumeText(volumeText)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) cursor.getString(0) else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun onPlaybackStateChanged(state: AudioPlaybackState) {
        playbackState = state
        responsiveLayout?.updatePlaybackTimes(state.positionMs, state.durationMs)
        responsiveLayout?.updatePlaybackProgress(state.positionMs, state.durationMs)
        if (state.status == PlaybackStatus.ERROR && state.errorMessage != lastShownError) {
            lastShownError = state.errorMessage
            Toast.makeText(
                this,
                state.errorMessage ?: "Não foi possível abrir este áudio.",
                Toast.LENGTH_SHORT,
            ).show()
        } else if (state.status != PlaybackStatus.ERROR) {
            lastShownError = null
        }
    }

    private fun createImageView(
        drawableRes: Int,
        imageScaleType: ImageView.ScaleType = ImageView.ScaleType.FIT_CENTER,
    ) = ImageView(this).apply {
        layoutParams = matchParentLayoutParams()
        scaleType = imageScaleType
        setImageResource(drawableRes)
        contentDescription = null
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun matchParentLayoutParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun formatGain(gainDb: Float): String = when {
        gainDb > 0.05f -> String.format(Locale.US, "+%.1f dB", gainDb)
        gainDb < -0.05f -> String.format(Locale.US, "%.1f dB", gainDb)
        else -> "0.0 dB"
    }

    private companion object {
        const val INTRO_DURATION_MILLIS = 2_000L
        const val WINDOW_BACKGROUND_COLOR = 0xFF010205.toInt()
    }
}
