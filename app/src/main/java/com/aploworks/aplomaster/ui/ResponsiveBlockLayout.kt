package com.aploworks.aplomaster.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.Insets
import com.aploworks.aplomaster.R

class ResponsiveBlockLayout(context: Context) : ViewGroup(context) {
    private val blockSpecs = listOf(
        BlockSpec(R.drawable.bloco_01_barra_navegacao_masterizacao_sem_textos, RectF(128f, 250f, 2044f, 503f)),
        BlockSpec(R.drawable.bloco_02_player_waveform_masterizacao_sem_tempos, RectF(223f, 78f, 1949f, 657f)),
        BlockSpec(R.drawable.bloco_03_titulo_presets_controle_volume_sem_textos, RectF(108f, 262f, 2079f, 487f)),
        BlockSpec(R.drawable.bloco_04_grade_presets, RectF(49f, 205f, 1390f, 843f)),
        BlockSpec(R.drawable.bloco_05_painel_ajuste_tons, RectF(344f, 50f, 1828f, 671f)),
        BlockSpec(R.drawable.bloco_06_cartao_intensidade, RectF(101f, 110f, 2072f, 614f)),
        BlockSpec(R.drawable.bloco_07_botoes_inferiores, RectF(159f, 194f, 2015f, 519f)),
    )
    private val nativeTextSpecs = listOf(
        NativeTextSpec(0, "Masterização", 405f, 270f, 760f, 120f, 82f, Color.WHITE, true),
        NativeTextSpec(0, "Minha Música - Master", 410f, 372f, 760f, 92f, 56f, Color.rgb(194, 232, 250), false),
        NativeTextSpec(2, "Presets", 112f, 250f, 500f, 135f, 106f, Color.WHITE, true),
        NativeTextSpec(2, "Escolha o resultado sonoro que deseja", 115f, 378f, 1250f, 96f, 58f, Color.rgb(194, 232, 250), false),
        NativeTextSpec(1, "00:00", 270f, 318f, 170f, 78f, 55f, Color.WHITE, false),
        NativeTextSpec(1, "00:00", 1645f, 318f, 190f, 78f, 55f, Color.WHITE, false),
    )
    private val interactionSpecs = listOf(
        InteractionSpec(1, InteractionType.PLAY_PAUSE, RectF(245f, 380f, 475f, 615f)),
        InteractionSpec(1, InteractionType.SEEK, RectF(410f, 325f, 1640f, 390f)),
        InteractionSpec(6, InteractionType.IMPORT, RectF(145f, 175f, 520f, 545f)),
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
    private val nativeTextViews = nativeTextSpecs.map { spec ->
        TextView(context).apply {
            text = spec.text
            setTextColor(spec.color)
            typeface = Typeface.create("sans-serif", if (spec.bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
            setSingleLine(true)
            setShadowLayer(1.5f, 1.5f, 2f, 0x66000000)
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(this)
        }
    }
    private val interactionViews = interactionSpecs.map { spec ->
        View(context).apply {
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = when (spec.type) {
                InteractionType.IMPORT -> "Importar áudio"
                InteractionType.PLAY_PAUSE -> "Reproduzir ou pausar"
                InteractionType.SEEK -> "Posição do áudio"
            }
            when (spec.type) {
                InteractionType.IMPORT -> setOnClickListener { onImportClick?.invoke() }
                InteractionType.PLAY_PAUSE -> setOnClickListener { onPlayPauseClick?.invoke() }
                InteractionType.SEEK -> setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE ||
                        event.action == MotionEvent.ACTION_UP
                    ) {
                        onSeekRequested?.invoke((event.x / view.width).coerceIn(0f, 1f))
                        true
                    } else {
                        false
                    }
                }
            }
            addView(this)
        }
    }

    private var safeInsets = Insets.NONE
    var onImportClick: (() -> Unit)? = null
    var onPlayPauseClick: (() -> Unit)? = null
    var onSeekRequested: ((Float) -> Unit)? = null

    fun updatePlaybackTimes(positionMs: Long, durationMs: Long) {
        nativeTextViews[POSITION_TEXT_INDEX].text = formatTime(positionMs)
        nativeTextViews[DURATION_TEXT_INDEX].text = formatTime(durationMs)
    }

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
        nativeTextViews.forEach { it.measure(childWidthSpec, childHeightSpec) }
        interactionViews.forEach { it.measure(childWidthSpec, childHeightSpec) }
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
            nativeTextSpecs.forEachIndexed { textIndex, textSpec ->
                if (textSpec.blockIndex != index) return@forEachIndexed
                val textView = nativeTextViews[textIndex]
                val textLeft = contentLeft + (textSpec.x - spec.contentBounds.left) * scale
                val textTop = contentTop + (textSpec.y - spec.contentBounds.top) * scale
                val textWidth = (textSpec.width * scale).toInt().coerceAtLeast(1)
                val textHeight = (textSpec.height * scale).toInt().coerceAtLeast(1)
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSpec.textSize * scale)
                textView.measure(
                    MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(textHeight, MeasureSpec.EXACTLY),
                )
                textView.layout(
                    textLeft.toInt(),
                    textTop.toInt(),
                    textLeft.toInt() + textWidth,
                    textTop.toInt() + textHeight,
                )
            }
            interactionSpecs.forEachIndexed { interactionIndex, interactionSpec ->
                if (interactionSpec.blockIndex != index) return@forEachIndexed
                val interactionView = interactionViews[interactionIndex]
                val bounds = interactionSpec.bounds
                val overlayLeft = contentLeft + (bounds.left - spec.contentBounds.left) * scale
                val overlayTop = contentTop + (bounds.top - spec.contentBounds.top) * scale
                val overlayWidth = (bounds.width() * scale).toInt().coerceAtLeast(1)
                val overlayHeight = (bounds.height() * scale).toInt().coerceAtLeast(1)
                interactionView.measure(
                    MeasureSpec.makeMeasureSpec(overlayWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(overlayHeight, MeasureSpec.EXACTLY),
                )
                interactionView.layout(
                    overlayLeft.toInt(),
                    overlayTop.toInt(),
                    overlayLeft.toInt() + overlayWidth,
                    overlayTop.toInt() + overlayHeight,
                )
            }
            contentTop += spec.contentBounds.height() * scale + adaptiveGap
        }
    }

    private data class BlockSpec(
        @DrawableRes val drawableRes: Int,
        val contentBounds: RectF,
    )

    private data class NativeTextSpec(
        val blockIndex: Int,
        val text: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val textSize: Float,
        val color: Int,
        val bold: Boolean,
    )

    private data class InteractionSpec(
        val blockIndex: Int,
        val type: InteractionType,
        val bounds: RectF,
    )

    private enum class InteractionType {
        IMPORT,
        PLAY_PAUSE,
        SEEK,
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    private companion object {
        const val HORIZONTAL_FILL = 0.92f
        const val MINIMUM_GAP_DP = 2f
        const val MAXIMUM_GAP_DP = 28f
        const val MINIMUM_TOP_SPACE_DP = 6f
        const val MINIMUM_BOTTOM_SPACE_DP = 6f
        const val POSITION_TEXT_INDEX = 4
        const val DURATION_TEXT_INDEX = 5
    }
}
