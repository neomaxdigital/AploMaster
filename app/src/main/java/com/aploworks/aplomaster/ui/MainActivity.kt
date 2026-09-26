package com.aploworks.aplomaster.ui

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aploworks.aplomaster.R

class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private val showMainScreen = Runnable { displayMainScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeWindow()

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
        super.onDestroy()
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
            ImageView.ScaleType.MATRIX,
        )
        val overlay = createImageView(
            R.drawable.layout_masterizacao_volume_1080x2400,
            ImageView.ScaleType.MATRIX,
        )
        val composition = FrameLayout(this).apply {
            layoutParams = matchParentLayoutParams()
        }
        composition.addView(background)
        composition.addView(overlay)
        composition.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            applySharedImageMatrix(
                background = background,
                overlay = overlay,
                viewportWidth = right - left,
                viewportHeight = bottom - top,
            )
        }

        root.addView(composition)
    }

    private fun applySharedImageMatrix(
        background: ImageView,
        overlay: ImageView,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        if (viewportWidth == 0 || viewportHeight == 0) return

        val scale = minOf(
            viewportWidth / MASTER_WIDTH,
            viewportHeight / MASTER_HEIGHT,
        )
        val horizontalOffset = (viewportWidth - MASTER_WIDTH * scale) / 2f
        val verticalOffset = (viewportHeight - MASTER_HEIGHT * scale) / 2f
        val sharedMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(horizontalOffset, verticalOffset)
        }

        background.imageMatrix = sharedMatrix
        overlay.imageMatrix = sharedMatrix
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

    private companion object {
        const val INTRO_DURATION_MILLIS = 2_000L
        const val WINDOW_BACKGROUND_COLOR = 0xFF010205.toInt()
        const val MASTER_WIDTH = 1080f
        const val MASTER_HEIGHT = 2400f
    }
}
