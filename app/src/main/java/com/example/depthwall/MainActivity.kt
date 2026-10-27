package com.example.depthwall

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private val pickBg = registerForActivityResult(ActivityResultContracts.GetContent()) { save(it, "bg.img") }
    private val pickFg = registerForActivityResult(ActivityResultContracts.GetContent()) { save(it, "fg.img") }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) saveVideo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("depthwall", MODE_PRIVATE)
        val sw = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch3d)
        sw.isChecked = prefs.getBoolean("enable3d", true)
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("enable3d", on).apply()
        }

        findViewById<Button>(R.id.btnBg).setOnClickListener { pickBg.launch("image/*") }
        findViewById<Button>(R.id.btnFg).setOnClickListener { pickFg.launch("image/*") }
        findViewById<Button>(R.id.btnVideo).setOnClickListener { pickVideo.launch("video/*") }
        findViewById<Button>(R.id.btnSet).setOnClickListener {
            val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            i.putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, ParallaxWallpaperService::class.java)
            )
            startActivity(i)
        }
    }

    private fun save(uri: Uri?, name: String) {
        if (uri == null) return
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                File(filesDir, name).outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("Không đọc được tệp đã chọn")
            if (name == "bg.img") File(filesDir, "bg.mp4").delete()
            Toast.makeText(this, "Đã lưu ảnh", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Loi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveVideo(uri: Uri) {
        try {
            val out = File(filesDir, "bg.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Không đọc được video")
            File(filesDir, "bg.img").delete()
            Toast.makeText(this, "Đã lưu video. Hãy đặt hình nền để phát.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi lưu video: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
