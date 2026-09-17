package com.wallpaper.app.services

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.wallpaper.app.audio.AmoledMathVisualizer
import java.io.File

class WallpaperEngineService : WallpaperService() {
    override fun onCreateEngine(): Engine = WallpaperEngine()

    inner class WallpaperEngine : Engine(), SensorEventListener {
        private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
        private val gyroscope by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }

        private var mediaPlayer: MediaPlayer? = null
        private var wallpaperBitmap: Bitmap? = null
        private var amoledVisualizer: AmoledMathVisualizer? = null

        private var currentMode: String = "4D"
        private var shiftX = 0f
        private var shiftY = 0f

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            loadConfiguration(holder)
            initVisualizer()
        }

        private fun loadConfiguration(holder: SurfaceHolder) {
            val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            currentMode = prefs.getString("mode", "4D") ?: "4D"

            if (currentMode == "VIDEO") {
                val videoUri = prefs.getString("video_path", null)
                videoUri?.let { startVideoPlayback(holder, it) }
            } else {
                val imagePath = prefs.getString("image_path", null)
                if (imagePath != null && File(imagePath).exists()) {
                    wallpaperBitmap = BitmapFactory.decodeFile(imagePath)
                }
                gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            }
        }

        private fun initVisualizer() {
            val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            amoledVisualizer = AmoledMathVisualizer { drawFrame() }.apply {
                isEnabled = prefs.getBoolean("music_reactive_enabled", true)
                activeColor = prefs.getInt("visualizer_color", Color.parseColor("#00E5FF"))
                mathDesign = prefs.getString("math_design", "SACRED_POLAR") ?: "SACRED_POLAR"
                start()
            }
        }

        private fun startVideoPlayback(holder: SurfaceHolder, uri: String) {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setSurface(holder.surface)
                    setDataSource(applicationContext, Uri.parse(uri))
                    isLooping = true
                    setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || currentMode == "VIDEO" || wallpaperBitmap == null) return

            val multiplier = when (currentMode) {
                "3D" -> 16f
                "4D" -> 34f
                "8D" -> 70f
                else -> 24f
            }

            shiftX = (shiftX - event.values[1] * multiplier).coerceIn(-130f, 130f)
            shiftY = (shiftY - event.values[0] * multiplier).coerceIn(-130f, 130f)
            drawFrame()
        }

        private fun drawFrame() {
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    val width = surfaceHolder.surfaceFrame.width().toFloat()
                    val height = surfaceHolder.surfaceFrame.height().toFloat()

                    canvas.drawColor(Color.BLACK)

                    wallpaperBitmap?.let { bmp ->
                        val matrix = Matrix().apply { postTranslate(shiftX, shiftY) }
                        canvas.drawBitmap(bmp, matrix, null)
                    }

                    amoledVisualizer?.render(canvas, width, height)
                }
            } finally {
                canvas?.let { surfaceHolder.unlockCanvasAndPost(it) }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onDestroy() {
            super.onDestroy()
            sensorManager.unregisterListener(this)
            amoledVisualizer?.release()
            mediaPlayer?.release()
            wallpaperBitmap?.recycle()
        }
    }
}
