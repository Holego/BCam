package com.example.videorecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

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
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            // Разрешения получены, кнопка готова к работе
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!hasAllPermissions()) {
            permissionLauncher.launch(requiredPermissions)
        }

        setContent {
            MaterialTheme {
                RecordScreen(
                    onStart = { startService(Intent(this, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_START
                    }) },
                    onStop = { startService(Intent(this, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_STOP
                    }) }
                )
            }
        }
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun RecordScreen(onStart: () -> Unit, onStop: () -> Unit) {
    var isRecording by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(if (isRecording) Color.Red else Color.White)
                .clickableToggle {
                    isRecording = !isRecording
                    if (isRecording) onStart() else onStop()
                }
        )
    }
}

// Небольшой хелпер, чтобы не тащить лишний импорт clickable отдельно
private fun Modifier.clickableToggle(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInputClick(onClick)
    )

private fun Modifier.pointerInputClick(onClick: () -> Unit): Modifier =
    this.then(
        androidx.compose.foundation.clickable(onClick = onClick)
    )
