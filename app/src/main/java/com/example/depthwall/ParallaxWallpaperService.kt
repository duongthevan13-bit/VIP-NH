package com.example.depthwall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.widget.Toast
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

class ParallaxWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ParallaxEngine()

    inner class ParallaxEngine : Engine(), SensorEventListener {
        private val DIR = 1f
        private val BG_ZOOM = 1.22f
        private val FG_ZOOM = 1.27f
        private val BG_MOVE_X = 0.035f
        private val BG_MOVE_Y = 0.028f
        private val FG_MOVE_X = 0.115f
        private val FG_MOVE_Y = 0.085f
        private val SMOOTHING = 0.16f
        private val FROZEN_ZOOM = 1f
        private val DEPTH_X = 0.09f
        private val DEPTH_Y = 0.06f
        private val MESH_W = 16
        private val MESH_H = 32
        private val meshVerts = FloatArray((MESH_W + 1) * (MESH_H + 1) * 2)
        private val handler = Handler(Looper.getMainLooper())
        private val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { alpha = 70 }
        private val extrusionPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { alpha = 38 }
        private var bg: Bitmap? = null
        private var fg: Bitmap? = null
        private var tx = 0f; private var ty = 0f
        private var cx = 0f; private var cy = 0f
        private var visible = false
        private var wasVisible = false
        private var w = 0; private var h = 0
        private var mediaPlayer: MediaPlayer? = null
        private var videoMode = false
        private var videoCompleted = false
        private var videoFailed = false
        private var renderingStarted = false
        private var capturing = false
        private var currentVideo: File? = null
        private var lastFrame: Bitmap? = null
        private var videoStamp = 0L
        private var bgStamp = Long.MIN_VALUE
        private var fgStamp = Long.MIN_VALUE
        private var bgLoadedSize = -1L
        private var fgLoadedSize = -1L
        private val prefs = getSharedPreferences("depthwall", Context.MODE_PRIVATE)
        private var enable3d = true
        private var playerPrepared = false
        private var gen = 0
        private var lastRestart = 0L

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                restartVideoFromStart()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            try {
                val f = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                registerReceiver(screenReceiver, f)
            } catch (e: Exception) {}
        }

        // Phát lại video từ đầu mỗi khi bật màn hình
        private fun restartVideoFromStart() {
            val video = File(filesDir, "bg.mp4")
            if (!(video.exists() && video.length() > 0L)) return
            val now = SystemClock.uptimeMillis()
            if (now - lastRestart < 800L) return
            lastRestart = now
            gen++
            handler.removeCallbacks(watchdog)
            handler.removeCallbacks(frame)
            stopVideo()
            videoMode = true
            videoCompleted = false
            videoFailed = false
            renderingStarted = false
            capturing = false
            lastFrame?.recycle()
            lastFrame = null
            startOrRefreshContent(surfaceHolder)
        }

        private fun isFrozen(): Boolean = videoMode && videoCompleted && lastFrame != null

        private fun toast(msg: String) {
            try { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() } catch (e: Exception) {}
        }

        private val frame = object : Runnable {
            override fun run() {
                if (!visible) return
                if (videoMode && !isFrozen()) return
                drawFrame()
                handler.postDelayed(this, 33L)
            }
        }

        // Neu sau 4 giay video chua hien hinh -> dung khung hinh cuoi
        private val watchdog = Runnable {
            val f = currentVideo
            if (mediaPlayer != null && !renderingStarted && !videoCompleted && f != null) {
                toast("Video không hiển thị được, dùng khung hình cuối")
                videoCompleted = true
                captureLastFrame(f)
            }
        }

        private fun scheduleWatchdog() {
            handler.removeCallbacks(watchdog)
            if (!renderingStarted) handler.postDelayed(watchdog, 4000L)
        }

        private fun startLoop() {
            handler.removeCallbacks(frame)
            if (visible) handler.post(frame) else drawFrame()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width; h = height
            loadImages()
            startOrRefreshContent(holder)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            startOrRefreshContent(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(watchdog)
            stopVideo()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(v: Boolean) {
            val becameVisible = v && !wasVisible
            wasVisible = v
            visible = v
            if (v) {
                enable3d = prefs.getBoolean("enable3d", true)
                loadImages()
                sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

                // Mỗi lần màn hình quay lại hình nền, phát video lại từ đầu.
                // Khi video kết thúc, ảnh khung cuối vẫn đứng yên cho tới lần mở tiếp theo.
                if (becameVisible) restartVideoFromStart()
                startOrRefreshContent(surfaceHolder)
            } else {
                sm.unregisterListener(this)
                handler.removeCallbacks(frame)
                handler.removeCallbacks(watchdog)
                if (playerPrepared) mediaPlayer?.pauseSafely()
            }
        }

        override fun onDestroy() {
            try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
            sm.unregisterListener(this)
            handler.removeCallbacks(frame)
            handler.removeCallbacks(watchd
