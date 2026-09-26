package com.aploworks.aplomaster.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aploworks.aplomaster.R

class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private val showMainScreen = Runnable { displayMainScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
            insets
        }

        setContentView(root)
        displayIntro()
    }

    override fun onDestroy() {
        root.removeCallbacks(showMainScreen)
        super.onDestroy()
    }

    private fun displayIntro() {
        val intro = createImageView(R.drawable.aplomaster_intro_opcao_2_1080x2400)
        root.addView(intro)
        intro.postDelayed(showMainScreen, INTRO_DURATION_MILLIS)
    }

    private fun displayMainScreen() {
        root.removeAllViews()

        val composition = FrameLayout(this).apply {
            layoutParams = matchParentLayoutParams()
        }
        composition.addView(createImageView(R.drawable.fundo_masterizacao_1080x2400))
        composition.addView(createImageView(R.drawable.layout_masterizacao_volume_1080x2400))

        root.addView(composition)
    }

    private fun createImageView(drawableRes: Int) = ImageView(this).apply {
        layoutParams = matchParentLayoutParams()
        scaleType = ImageView.ScaleType.FIT_CENTER
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
    }
}
