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
                v
