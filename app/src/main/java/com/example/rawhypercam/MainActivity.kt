package com.example.rawhypercam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import com.example.rawhypercam.ui.CameraScreen
import com.example.rawhypercam.ui.theme.RawHyperCamTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var controller: Camera2Controller
    private val lenses = mutableStateListOf<Camera2Controller.LensInfo>()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) controller.start()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        controller = Camera2Controller(
            context = this,
            onLensList = { list -> lenses.clear(); lenses.addAll(list) },
            onPreviewSize = { },
            onError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        )

        setContent {
            RawHyperCamTheme {
                CameraScreen(
                    controller = controller,
                    lenses = lenses,
                    onShutter = { capturePhoto() }
                )
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) controller.start() else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun capturePhoto() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        val file = File(dir, "RAW_${System.currentTimeMillis()}.dng")
        controller.captureRaw(file)
        Toast.makeText(this, "Capturing RAW...", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}