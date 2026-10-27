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
            handler.removeCallbacks(watchdog)
            stopVideo()
            bg?.recycle(); bg = null
            fg?.recycle(); fg = null
            lastFrame?.recycle(); lastFrame = null
            super.onDestroy()
        }

        override fun onSensorChanged(e: SensorEvent) {
            tx = (-e.values[0] / 7.0f * DIR).coerceIn(-1f, 1f)
            ty = ((e.values[1] - 6.0f) / 7.0f * DIR).coerceIn(-1f, 1f)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}

        private fun startOrRefreshContent(holder: SurfaceHolder) {
            if (w <= 0 || h <= 0 || !holder.surface.isValid) return
            val video = File(filesDir, "bg.mp4")
            if (video.exists() && video.length() > 0L) {
                if (video.lastModified() != videoStamp) {
                    videoStamp = video.lastModified()
                    videoCompleted = false
                    videoFailed = false
                    capturing = false
                    handler.removeCallbacks(watchdog)
                    lastFrame?.recycle(); lastFrame = null
                    stopVideo()
                }
                if (!videoFailed) {
                    videoMode = true
                    handler.removeCallbacks(frame)
                    if (videoCompleted) {
                        if (isFrozen()) startLoop()
                        return
                    }
                    startVideo(holder, video)
                    return
                }
            }
            videoMode = false
            videoCompleted = false
            lastFrame?.recycle(); lastFrame = null
            stopVideo()
            loadImages()
            startLoop()
        }

        private fun startVideo(holder: SurfaceHolder, file: File) {
            currentVideo = file
            if (mediaPlayer != null) {
                if (visible && !videoCompleted) {
                    if (playerPrepared) mediaPlayer?.startSafely()
                    scheduleWatchdog()
                }
                return
            }
            try {
                val player = MediaPlayer()
                mediaPlayer = player
                playerPrepared = false
                renderingStarted = false
                videoCompleted = false
                player.setDataSource(file.absolutePath)
                player.setSurface(holder.surface)
                player.isLooping = false
                player.setVolume(0f, 0f)
                player.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        renderingStarted = true
                        handler.removeCallbacks(watchdog)
                    }
                    false
                }
                player.setOnPreparedListener { mp ->
                    playerPrepared = true
                    mp.isLooping = false
                    try {
                        mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    } catch (e: Exception) {}
                    if (visible && !videoCompleted) {
                        mp.startSafely()
                        scheduleWatchdog()
                    }
                }
                player.setOnCompletionListener {
                    handler.removeCallbacks(watchdog)
                    videoCompleted = true
                    captureLastFrame(file)
                }
                player.setOnErrorListener { _, what, extra ->
                    handler.removeCallbacks(watchdog)
                    toast("Lỗi phát video ($what/$extra), dùng khung hình cuối")
                    stopVideo()
                    videoCompleted = true
                    captureLastFrame(file)
                    true
                }
                player.prepareAsync()
            } catch (e: Exception) {
                toast("Không mở được video: ${e.message}")
                stopVideo()
                videoCompleted = true
                captureLastFrame(file)
            }
        }
                // Lay khung hinh cuoi thanh anh tinh, tat trinh phat video, bat hieu ung 3D
        private fun captureLastFrame(file: File) {
            if (capturing) return
            capturing = true
            val myGen = gen
            Thread {
                var bmp: Bitmap? = null
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.absolutePath)
                    val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val times = longArrayOf(
                        (dur - 80L).coerceAtLeast(0L) * 1000L,
                        (dur - 250L).coerceAtLeast(0L) * 1000L,
                        (dur - 500L).coerceAtLeast(0L) * 1000L,
                        0L
                    )
                    for (t in times) {
                        bmp = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (bmp != null) break
                    }
                } catch (e: Exception) {
                } finally {
                    try { r.release() } catch (e: Exception) {}
                }
                val result = bmp
                handler.post {
                    if (myGen != gen) {
                        result?.recycle()
                        return@post
                    }
                    capturing = false
                    if (videoMode) {
                        if (result != null) {
                            lastFrame?.recycle()
                            lastFrame = result
                            stopVideo()
                            videoCompleted = true
                            startLoop()
                        } else {
                            toast("Không đọc được video. Hãy thử video MP4 H.264")
                            stopVideo()
                            videoFailed = true
                            videoMode = false
                            videoCompleted = false
                            startLoop()
                        }
                    }
                }
            }.start()
        }

        private fun stopVideo() {
            val p = mediaPlayer
            mediaPlayer = null
            playerPrepared = false
            try {
                p?.setOnPreparedListener(null)
                p?.setOnErrorListener(null)
                p?.setOnCompletionListener(null)
                p?.setOnInfoListener(null)
                p?.stop()
            } catch (e: Exception) {}
            try { p?.reset(); p?.release() } catch (e: Exception) {}
        }

        private fun MediaPlayer.startSafely() { try { if (!isPlaying) start() } catch (e: Exception) {} }
        private fun MediaPlayer.pauseSafely() { try { if (isPlaying) pause() } catch (e: Exception) {} }

        private fun loadImages() {
            if (w <= 0 || h <= 0) return
            val bgFile = File(filesDir, "bg.img")
            val fgFile = File(filesDir, "fg.img")
            if (bgFile.lastModified() != bgStamp || bgFile.length() != bgLoadedSize) {
                val next = decode(bgFile)
                bg?.takeIf { it !== next && !it.isRecycled }?.recycle()
                bg = next
                bgStamp = bgFile.lastModified()
                bgLoadedSize = bgFile.length()
            }
            if (fgFile.lastModified() != fgStamp || fgFile.length() != fgLoadedSize) {
                val next = decode(fgFile)
                fg?.takeIf { it !== next && !it.isRecycled }?.recycle()
                fg = next
                fgStamp = fgFile.lastModified()
                fgLoadedSize = fgFile.length()
            }
        }

        private fun decode(f: File): Bitmap? {
            if (!f.exists() || f.length() == 0L) return null
            return try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
                var sample = 1
                val targetW = (w * 1.35f).toInt().coerceAtLeast(1)
                val targetH = (h * 1.35f).toInt().coerceAtLeast(1)
                while (opts.outWidth / (sample * 2) >= targetW && opts.outHeight / (sample * 2) >= targetH) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            } catch (e: Exception) { null }
        }

        private fun matrixFor(b: Bitmap, dx: Float, dy: Float, zoom: Float): Matrix {
            val scale = max(w.toFloat() / b.width, h.toFloat() / b.height) * zoom
            return Matrix().apply {
                postScale(scale, scale)
                postTranslate((w - b.width * scale) / 2f + dx, (h - b.height * scale) / 2f + dy)
            }
        }
                private fun drawLayer(c: Canvas, b: Bitmap, dx: Float, dy: Float, zoom: Float) {
            c.drawBitmap(b, matrixFor(b, dx, dy, zoom), paint)
        }

        private fun drawForegroundMax(c: Canvas, b: Bitmap, dx: Float, dy: Float) {
            val matrix = matrixFor(b, dx, dy, FG_ZOOM)
            c.drawBitmap(b, Matrix(matrix).apply { postTranslate(w * 0.012f, h * 0.010f) }, shadowPaint)
            c.drawBitmap(b, Matrix(matrix).apply { postTranslate(-w * 0.0032f, -h * 0.0024f) }, extrusionPaint)
            c.drawBitmap(b, Matrix(matrix).apply { postTranslate(w * 0.0032f, h * 0.0024f) }, extrusionPaint)
            c.drawBitmap(b, matrix, paint)
        }

        private fun drawDepthImage(c: Canvas, b: Bitmap) {
            val s = max(w.toFloat() / b.width, h.toFloat() / b.height) * FROZEN_ZOOM
            val dw = b.width * s
            val dh = b.height * s
            val left = (w - dw) / 2f
            val top = (h - dh) / 2f
            var k = 0
            for (i in 0..MESH_H) {
                val v = i.toFloat() / MESH_H
                for (j in 0..MESH_W) {
                    val u = j.toFloat() / MESH_W
                    val px = left + u * dw
                    val py = top + v * dh
                    val edge = minOf(minOf(u, 1f - u), minOf(v, 1f - v))
                    val fall = (edge / 0.15f).coerceIn(0f, 1f)
                    val nx = px / w - 0.5f
                    val ny = py / h - 0.5f
                    val depth = (1f - sqrt(nx * nx + ny * ny) * 1.6f).coerceIn(0f, 1f)
                    val k2 = depth - 0.45f
                    meshVerts[k++] = px + cx * w * DEPTH_X * k2 * fall
                    meshVerts[k++] = py + cy * h * DEPTH_Y * k2 * fall
                }
            }
            c.drawBitmapMesh(b, MESH_W, MESH_H, meshVerts, 0, null, 0, paint)
        }

        private fun drawFrame() {
            if (w <= 0 || h <= 0) return
            if (videoMode && !isFrozen()) return
            enable3d = prefs.getBoolean("enable3d", true)
            val ttx = if (enable3d) tx else 0f
            val tty = if (enable3d) ty else 0f
            cx += (ttx - cx) * SMOOTHING; cy += (tty - cy) * SMOOTHING
            val holder = surfaceHolder
            val c = try { holder.lockCanvas() } catch (e: Exception) { null } ?: return
            try {
                c.drawColor(Color.BLACK)
                val frozen = lastFrame
                if (videoMode && frozen != null) {
                    if (enable3d) drawDepthImage(c, frozen)
                    else c.drawBitmap(frozen, matrixFor(frozen, 0f, 0f, 1f), paint)
                } else {
                    bg?.let {
                        if (enable3d) drawLayer(c, it, -cx * w * BG_MOVE_X, -cy * h * BG_MOVE_Y, BG_ZOOM)
                        else drawLayer(c, it, 0f, 0f, 1f)
                    }
                }
                fg?.let {
                    if (enable3d) drawForegroundMax(c, it, cx * w * FG_MOVE_X, cy * h * FG_MOVE_Y)
                    else drawLayer(c, it, 0f, 0f, 1f)
                }
            } catch (e: Exception) {
            } finally {
                try { holder.unlockCanvasAndPost(c) } catch (e: Exception) {}
            }
        }
    }
}
