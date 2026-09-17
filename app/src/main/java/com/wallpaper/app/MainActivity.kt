package com.wallpaper.app

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wallpaper.app.services.WallpaperEngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val isAuto = prefs.getBoolean("auto_lang", true)
        val lang = if (isAuto) {
            val tm = newBase.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm?.networkCountryIso?.lowercase() == "in") "hi" else Locale.getDefault().language
        } else {
            prefs.getString("selected_lang", "en") ?: "en"
        }
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        audioPerm.launch(android.Manifest.permission.RECORD_AUDIO)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WallpaperAppDashboard(
                        onSetWallpaper = { mode, uri, musicOn, color, design ->
                            commitConfig(mode, uri, musicOn, color, design)
                            launchWallpaperSelector()
                        },
                        onDownloadActiveMedia = { isVideo -> exportMedia(isVideo) },
                        onResetAll = { executeHardReset() },
                        onLanguageChange = { code, auto ->
                            getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).edit()
                                .putBoolean("auto_lang", auto)
                                .putString("selected_lang", code)
                                .apply()
                            recreate()
                        }
                    )
                }
            }
        }
    }

    private fun commitConfig(mode: String, uri: Uri, musicOn: Boolean, color: Int, design: String) {
        val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).edit()
        prefs.putString("mode", mode)
        prefs.putBoolean("music_reactive_enabled", musicOn)
        prefs.putInt("visualizer_color", color)
        prefs.putString("math_design", design)

        if (mode == "VIDEO") {
            prefs.putString("video_path", uri.toString())
        } else {
            val localTarget = File(filesDir, "active_rendered_image.png")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(localTarget).use { output -> input.copyTo(output) }
            }
            prefs.putString("image_path", localTarget.absolutePath)
        }
        prefs.apply()
    }

    private fun exportMedia(isVideo: Boolean) {
        lifecycleScope.launch {
            val file = if (isVideo) File(filesDir, "active_rendered_video.mp4") else File(filesDir, "active_rendered_image.png")
            if (!file.exists()) {
                Toast.makeText(this@MainActivity, "No media to export.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            withContext(Dispatchers.IO) {
                val contentUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val mimeType = if (isVideo) "video/mp4" else "image/png"
                val relativePath = if (isVideo) Environment.DIRECTORY_MOVIES + "/Wallpaper_Downloads" else Environment.DIRECTORY_PICTURES + "/Wallpaper_Downloads"

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Wallpaper_${System.currentTimeMillis()}")
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val uri = contentResolver.insert(contentUri, values)
                uri?.let { outUri ->
                    contentResolver.openOutputStream(outUri)?.use { out ->
                        FileInputStream(file).use { input -> input.copyTo(out) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(outUri, values, null, null)
                    }
                }
            }
            Toast.makeText(this@MainActivity, "Saved to Wallpaper_Downloads Category!", Toast.LENGTH_LONG).show()
        }
    }

    private fun executeHardReset() {
        getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        File(filesDir, "active_rendered_image.png").delete()
        try { WallpaperManager.getInstance(this).clear() } catch (_: Exception) {}
        recreate()
    }

    private fun launchWallpaperSelector() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, WallpaperEngineService::class.java)
            )
        }
        startActivity(intent)
    }
}

@Composable
fun WallpaperAppDashboard(
    onSetWallpaper: (mode: String, uri: Uri, musicOn: Boolean, color: Int, design: String) -> Unit,
    onDownloadActiveMedia: (isVideo: Boolean) -> Unit,
    onResetAll: () -> Unit,
    onLanguageChange: (String, Boolean) -> Unit
) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var isVideoMode by remember { mutableStateOf(false) }
    var dimensionEffect by remember { mutableStateOf("4D") }

    var musicReactiveOn by remember { mutableStateOf(true) }
    var selectedColor by remember { mutableStateOf(AndroidColor.parseColor("#00E5FF")) }
    var selectedMathDesign by remember { mutableStateOf("SACRED_POLAR") }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri = it; isVideoMode = false }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri = it; isVideoMode = true }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Wallpaper", style = MaterialTheme.typography.headlineLarge)
            Text("Theme Parallax & AMOLED Studio", style = MaterialTheme.typography.bodyMedium)
        }

        // Language Selectors
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onLanguageChange("auto", true) }) { Text("Auto (Country)") }
                OutlinedButton(onClick = { onLanguageChange("hi", false) }) { Text("Hindi") }
                OutlinedButton(onClick = { onLanguageChange("en", false) }) { Text("English") }
            }
        }

        // Function 1: Image Upload (3D/4D/8D)
        item {
            Button(modifier = Modifier.fillMaxWidth(), onClick = { imagePicker.launch("image/*") }) {
                Text("1. Upload Image (3D, 4D, 8D Parallax)")
            }
            if (!isVideoMode && selectedUri != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("3D", "4D", "8D", "LIVE").forEach { dim ->
                        FilterChip(
                            selected = dimensionEffect == dim,
                            onClick = { dimensionEffect = dim },
                            label = { Text(dim) }
                        )
                    }
                }
            }
        }

        // Function 2: Video Upload
        item {
            Button(modifier = Modifier.fillMaxWidth(), onClick = { videoPicker.launch("video/*") }) {
                Text("2. Upload Video to Live Wallpaper")
            }
        }

        // Function 3: AMOLED Music Reactive Visualizer
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("3. AMOLED Music Engine", style = MaterialTheme.typography.titleMedium)
                        Switch(checked = musicReactiveOn, onCheckedChange = { musicReactiveOn = it })
                    }

                    if (musicReactiveOn) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Mathematical Geometry:", style = MaterialTheme.typography.bodySmall)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf("SACRED_POLAR", "LISSAJOUS", "AMOLED_BORDER").forEach { design ->
                                FilterChip(
                                    selected = selectedMathDesign == design,
                                    onClick = { selectedMathDesign = design },
                                    label = { Text(design.take(8)) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Color Spectrum:", style = MaterialTheme.typography.bodySmall)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf(
                                "#00E5FF" to Color(0xFF00E5FF),
                                "#00FF66" to Color(0xFF00FF66),
                                "#FF0055" to Color(0xFFFF0055),
                                "#9900FF" to Color(0xFF9900FF)
                            ).forEach { (hex, color) ->
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .clickable { selectedColor = AndroidColor.parseColor(hex) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Downloads Category Button
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { onDownloadActiveMedia(isVideoMode) }) {
                Text("Export to Downloads Category")
            }
        }

        // Set Wallpaper Action
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedUri != null,
                onClick = {
                    selectedUri?.let { uri ->
                        val mode = if (isVideoMode) "VIDEO" else dimensionEffect
                        onSetWallpaper(mode, uri, musicReactiveOn, selectedColor, selectedMathDesign)
                    }
                }
            ) {
                Text("Apply Wallpaper to System")
            }
        }

        // Reset Button
        item {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    selectedUri = null
                    onResetAll()
                }
            ) {
                Text("Reset All to Default")
            }
        }
    }
}
