package com.aploworks.aplomaster.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
            ImageView.ScaleType.CENTER_CROP,
        )
        val blocks = ResponsiveBlockLayout(this)
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
    }
}
