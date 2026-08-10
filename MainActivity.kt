package com.example.videorecorder

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

class MainActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "video_recorder_prefs"
        const val KEY_FOLDER_URI = "folder_uri"
    }

    private lateinit var prefs: SharedPreferences

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* результат обрабатывать не обязательно — просто ждём разрешения */ }

    // Системный диалог выбора папки (Storage Access Framework)
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Получаем постоянный доступ к папке, а не разовый
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
            selectedFolderName.value = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
        }
    }

    private val selectedFolderName = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        if (!hasAllPermissions()) {
            permissionLauncher.launch(requiredPermissions)
        }

        val savedUri = prefs.getString(KEY_FOLDER_URI, null)
        if (savedUri != null) {
            selectedFolderName.value = DocumentFile.fromTreeUri(this, Uri.parse(savedUri))?.name
        }

        setContent {
            MaterialTheme {
                RecordScreen(
                    folderName = selectedFolderName.value,
                    onChooseFolder = { folderPickerLauncher.launch(null) },
                    onStart = {
                        startService(Intent(this, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_START
                        })
                    },
                    onStop = {
                        startService(Intent(this, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_STOP
                        })
                    }
                )
            }
        }
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun RecordScreen(
    folderName: String?,
    onChooseFolder: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    var isRecording by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Кнопка выбора папки — сверху экрана
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 24.dp, end = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.DarkGray)
                .clickable(onClick = onChooseFolder)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = folderName?.let { "Папка: $it" } ?: "Выбрать папку для сохранения",
                color = Color.White,
                fontSize = 14.sp
            )
        }

        // Кнопки записи — внизу по центру
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Основная кнопка — старт записи
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color.Red else Color.White)
                    .clickable {
                        if (!isRecording) {
                            isRecording = true
                            onStart()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isRecording) "REC" else "●",
                    color = if (isRecording) Color.White else Color.Red,
                    fontWeight = FontWeight.Bold
                )
            }

            // Отдельная кнопка "Стоп" — рядом, активна только во время записи
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color.White else Color.DarkGray)
                    .clickable(enabled = isRecording) {
                        isRecording = false
                        onStop()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "■",
                    color = if (isRecording) Color.Black else Color.Gray,
                    fontSize = 20.sp
                )
            }
        }
    }
}
