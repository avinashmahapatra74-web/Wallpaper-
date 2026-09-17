package com.wallpaper.app.audio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.audiofx.Visualizer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class AmoledMathVisualizer(private val onRedrawRequired: () -> Unit) {
    private var visualizer: Visualizer? = null
    var isEnabled: Boolean = true
    var activeColor: Int = Color.parseColor("#00E5FF")
    var mathDesign: String = "SACRED_POLAR"

    private var audioFftData = ByteArray(128)
    private var energyLevel = 0f

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun start() {
        try {
            visualizer = Visualizer(0).apply {
                captureSize = 128
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, data: ByteArray?, rate: Int) {
                        if (data == null || !isEnabled) return
                        var sum = 0f
                        for (b in data) sum += kotlin.math.abs(b.toInt())
                        energyLevel = (sum / data.size) / 128f
                        onRedrawRequired()
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        fft?.let { System.arraycopy(it, 0, audioFftData, 0, it.size.coerceAtMost(128)) }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, true, true)
                enabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun render(canvas: Canvas, width: Float, height: Float) {
        if (!isEnabled || energyLevel <= 0.02f) return

        strokePaint.color = activeColor
        strokePaint.alpha = (energyLevel * 255).toInt().coerceIn(60, 255)
        strokePaint.strokeWidth = 4f + (energyLevel * 10f)

        val centerX = width / 2f
        val centerY = height / 2f
        val path = Path()

        when (mathDesign) {
            "SACRED_POLAR" -> {
                val baseRadius = width.coerceAtMost(height) * 0.28f
                val petals = 8
                val points = 360
                for (i in 0..points) {
                    val theta = (i * (2 * PI / points)).toFloat()
                    val fftMagnitude = kotlin.math.abs(audioFftData[i % audioFftData.size].toInt())
                    val r = baseRadius + (fftMagnitude * energyLevel * 2.2f) * sin(petals * theta)
                    val x = centerX + r * cos(theta)
                    val y = centerY + r * sin(theta)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, strokePaint)
            }
            "LISSAJOUS" -> {
                val a = 5
                val b = 4
                val delta = energyLevel * PI.toFloat()
                for (t in 0..240) {
                    val angle = t * 0.05f
                    val x = centerX + (width * 0.38f * sin(a * angle + delta))
                    val y = centerY + (height * 0.26f * sin(b * angle))
                    if (t == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, strokePaint)
            }
            else -> {
                strokePaint.strokeWidth = 14f * energyLevel
                canvas.drawRect(10f, 10f, width - 10f, height - 10f, strokePaint)
            }
        }
    }

    fun release() {
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
    }
}
