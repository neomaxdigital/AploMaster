package com.aploworks.aplomaster.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.graphics.Insets
import com.aploworks.aplomaster.R

class ResponsiveBlockLayout(context: Context) : ViewGroup(context) {
    private val blockSpecs = listOf(
        BlockSpec(R.drawable.bloco_01_barra_navegacao_masterizacao, RectF(128f, 250f, 2044f, 503f)),
        BlockSpec(R.drawable.bloco_02_player_waveform_masterizacao, RectF(223f, 78f, 1949f, 657f)),
        BlockSpec(R.drawable.bloco_03_titulo_presets_controle_volume, RectF(108f, 262f, 2079f, 487f)),
        BlockSpec(R.drawable.bloco_04_grade_presets, RectF(49f, 205f, 1390f, 843f)),
        BlockSpec(R.drawable.bloco_05_painel_ajuste_tons, RectF(344f, 50f, 1828f, 671f)),
        BlockSpec(R.drawable.bloco_06_cartao_intensidade, RectF(101f, 110f, 2072f, 614f)),
        BlockSpec(R.drawable.bloco_07_botoes_inferiores, RectF(159f, 194f, 2015f, 519f)),
    )
    private val blockViews = blockSpecs.map { spec ->
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setImageResource(spec.drawableRes)
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(this)
        }
    }

    private var safeInsets = Insets.NONE

    fun updateSafeInsets(insets: Insets) {
        if (safeInsets == insets) return
        safeInsets = insets
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)

        val childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        blockViews.forEach { it.measure(childWidthSpec, childHeightSpec) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val viewportWidth = right - left
        val viewportHeight = bottom - top
        blockViews.forEach { it.layout(0, 0, viewportWidth, viewportHeight) }

        val safeLeft = safeInsets.left.toFloat()
        val safeTop = safeInsets.top.toFloat()
        val safeWidth = (viewportWidth - safeInsets.left - safeInsets.right).toFloat()
        val safeHeight = (viewportHeight - safeInsets.top - safeInsets.bottom).toFloat()
        if (safeWidth <= 0f || safeHeight <= 0f) return

        val density = resources.displayMetrics.density
        val minimumGap = MINIMUM_GAP_DP * density
        val maximumGap = MAXIMUM_GAP_DP * density
        val minimumTopSpace = MINIMUM_TOP_SPACE_DP * density
        val minimumBottomSpace = MINIMUM_BOTTOM_SPACE_DP * density
        val targetContentWidth = safeWidth * HORIZONTAL_FILL

        val baseScales = blockSpecs.map { targetContentWidth / it.contentBounds.width() }
        val baseContentHeight = blockSpecs.indices.sumOf { index ->
            (blockSpecs[index].contentBounds.height() * baseScales[index]).toDouble()
        }.toFloat()
        val minimumSpacingHeight =
            minimumTopSpace + minimumBottomSpace + minimumGap * (blockSpecs.size - 1)
        val fitScale = minOf(
            1f,
            ((safeHeight - minimumSpacingHeight) / baseContentHeight).coerceAtLeast(0f),
        )
        val scales = baseScales.map { it * fitScale }
        val contentHeight = blockSpecs.indices.sumOf { index ->
            (blockSpecs[index].contentBounds.height() * scales[index]).toDouble()
        }.toFloat()
        val adaptiveGap = (
            (safeHeight - contentHeight - minimumTopSpace - minimumBottomSpace) /
                (blockSpecs.size - 1)
            ).coerceIn(minimumGap, maximumGap)
        val topSpace = (
            safeHeight - contentHeight - adaptiveGap * (blockSpecs.size - 1) -
                minimumBottomSpace
            ).coerceAtLeast(minimumTopSpace)

        var contentTop = safeTop + topSpace
        blockSpecs.forEachIndexed { index, spec ->
            val scale = scales[index]
            val contentWidth = spec.contentBounds.width() * scale
            val contentLeft = safeLeft + (safeWidth - contentWidth) / 2f
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    contentLeft - spec.contentBounds.left * scale,
                    contentTop - spec.contentBounds.top * scale,
                )
            }
            blockViews[index].imageMatrix = matrix
            contentTop += spec.contentBounds.height() * scale + adaptiveGap
        }
    }

    private data class BlockSpec(
        @DrawableRes val drawableRes: Int,
        val contentBounds: RectF,
    )

    private companion object {
        const val HORIZONTAL_FILL = 0.92f
        const val MINIMUM_GAP_DP = 2f
        const val MAXIMUM_GAP_DP = 28f
        const val MINIMUM_TOP_SPACE_DP = 6f
        const val MINIMUM_BOTTOM_SPACE_DP = 6f
    }
}
