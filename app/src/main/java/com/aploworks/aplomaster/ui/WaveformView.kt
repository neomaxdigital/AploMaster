package com.aploworks.aplomaster.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.aploworks.aplomaster.domain.WaveformData

class WaveformView(context: Context) : View(context) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 48, 70)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private var waveformData: WaveformData? = null
    private var progress = 0f
    var onSeekRequested: ((Float) -> Unit)? = null

    fun setWaveform(data: WaveformData?) {
        waveformData = data
        invalidate()
    }

    fun setProgress(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backgroundPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(Color.rgb(7, 25, 44), Color.rgb(5, 20, 36)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val baselineY = height * 0.82f
        canvas.drawLine(width * 0.08f, baselineY, width * 0.92f, baselineY, baselinePaint)

        val amplitudes = waveformData?.amplitudes ?: return drawPlayhead(canvas)
        if (amplitudes.isEmpty()) return drawPlayhead(canvas)
        waveformPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            intArrayOf(Color.rgb(137, 35, 255), Color.rgb(55, 40, 255), Color.rgb(121, 20, 210)),
            null,
            Shader.TileMode.CLAMP,
        )
        val centerY = height * 0.39f
        val maximumHalfHeight = height * 0.29f
        val step = width.toFloat() / amplitudes.size
        waveformPaint.strokeWidth = (step * 0.72f).coerceAtLeast(resources.displayMetrics.density)
        amplitudes.forEachIndexed { index, amplitude ->
            val x = (index + 0.5f) * step
            val halfHeight = (amplitude * maximumHalfHeight).coerceAtLeast(1f)
            canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, waveformPaint)
        }
        drawPlayhead(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE ||
            event.action == MotionEvent.ACTION_UP
        ) {
            onSeekRequested?.invoke((event.x / width).coerceIn(0f, 1f))
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun drawPlayhead(canvas: Canvas) {
        if (waveformData == null) return
        val x = width * progress
        val baselineY = height * 0.82f
        canvas.drawLine(x, height * 0.05f, x, baselineY, playheadPaint)
        canvas.drawCircle(x, baselineY, resources.displayMetrics.density * 5f, playheadPaint)
    }
}
