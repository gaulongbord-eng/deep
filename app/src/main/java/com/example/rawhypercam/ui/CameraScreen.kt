package com.example.rawhypercam.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.rawhypercam.Camera2Controller

data class FilmPreset(
    val name: String,
    val tint: Color,
    val alpha: Float
)

private val FILMS = listOf(
    FilmPreset("None", Color.Transparent, 0f),
    FilmPreset("Kodak Portra", Color(0xFFFFB347), 0.18f),
    FilmPreset("Fuji Superia", Color(0xFF66DDAA), 0.15f),
    FilmPreset("Ilford HP5", Color(0xFF888888), 0.25f),
    FilmPreset("Leica Classic", Color(0xFFFF4B2B), 0.12f)
)

@Composable
fun CameraScreen(
    controller: Camera2Controller,
    lenses: List<Camera2Controller.LensInfo>,
    onShutter: () -> Unit
) {
    var isProMode by remember { mutableStateOf(true) }
    var selectedLens by remember { mutableIntStateOf(0) }
    var iso by remember { mutableFloatStateOf(400f) }
    var shutterNs by remember { mutableFloatStateOf(1_000_000f) }
    var focusD by remember { mutableFloatStateOf(0f) }
    var ev by remember { mutableIntStateOf(0) }
    var showFilmMenu by remember { mutableStateOf(false) }
    var selectedFilm by remember { mutableStateOf(FILMS[0]) }
    val previewAspect = 4f / 3f

    val accent = Color(0xFFFF4B2B)

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        Box(
            Modifier.align(Alignment.Center).aspectRatio(previewAspect).fillMaxWidth()
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: SurfaceTexture, w: Int, h: Int
                            ) {
                                lenses.getOrNull(0)?.let { controller.openLens(it, st) }
                            }
                            override fun onSurfaceTextureSizeChanged(
                                st: SurfaceTexture, w: Int, h: Int
                            ) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                        setOnTouchListener { v, e ->
                            if (e.action == android.view.MotionEvent.ACTION_UP) {
                                controller.tapToFocus(e.x, e.y, v.width, v.height)
                            }
                            true
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (selectedFilm.alpha > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(selectedFilm.tint.copy(alpha = selectedFilm.alpha))
                )
            }
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ModeTab("Pro", isProMode, accent) { isProMode = true; controller.isProMode = true }
                ModeTab("Normal", !isProMode, accent) { isProMode = false; controller.isProMode = false }
            }
            Text(
                if (isProMode) "ISO ${iso.toInt()}  ${fmtShutter(shutterNs)}"
                else "EV ${if (ev >= 0) "+" else ""}$ev",
                color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp
            )
        }

        if (isProMode) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VSlider("ISO", iso, 100f..6400f, accent) {
                    iso = it
                    controller.updateManualControls(it.toInt(), shutterNs.toLong(), focusD)
                }
                VSlider("S", shutterNs, 100_000f..10_000_000f, accent) {
                    shutterNs = it
                    controller.updateManualControls(iso.toInt(), it.toLong(), focusD)
                }
                VSlider("F", focusD, 0f..10f, accent) {
                    focusD = it
                    controller.updateManualControls(iso.toInt(), shutterNs.toLong(), it)
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("EV", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Slider(
                    value = ev.toFloat(),
                    onValueChange = { ev = it.toInt(); controller.updateEv(ev) },
                    valueRange = -4f..4f, steps = 7,
                    modifier = Modifier.width(120.dp).graphicsLayer { rotationZ = -90f },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent)
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.85f),
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                lenses.forEachIndexed { idx, lens ->
                    TextButton(
                        onClick = { selectedLens = idx },
                        shape = CircleShape,
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (selectedLens == idx) accent.copy(alpha = 0.2f)
                                else Color.Transparent,
                            contentColor = if (selectedLens == idx) accent
                                else Color.White.copy(alpha = 0.6f)
                        )
                    ) { Text(lens.name, fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) {
                TextButton(
                    onClick = { showFilmMenu = true },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Movie, contentDescription = "Film",
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(selectedFilm.name, fontSize = 11.sp)
                }
                Box(
                    Modifier.size(76.dp).background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = onShutter,
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {}
                }
            }
        }

        if (showFilmMenu) {
            AlertDialog(
                onDismissRequest = { showFilmMenu = false },
                title = { Text("Film emulation") },
                text = {
                    Column {
                        FILMS.forEach { film ->
                            TextButton(onClick = {
                                selectedFilm = film
                                showFilmMenu = false
                            }) {
                                Text(
                                    film.name,
                                    color = if (selectedFilm.name == film.name) accent else Color.White
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFilmMenu = false }) { Text("OK") }
                }
            )
        }
    }
}

@Composable
private fun ModeTab(text: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text,
            color = if (selected) accent else Color.White.copy(alpha = 0.5f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun VSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    accent: Color, onChange: (Float) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
        Box(Modifier.width(44.dp).height(120.dp), contentAlignment = Alignment.Center) {
            Slider(
                value = value, onValueChange = onChange, valueRange = range,
                modifier = Modifier.width(120.dp).graphicsLayer { rotationZ = -90f },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White, activeTrackColor = accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                )
            )
        }
    }
}

private fun fmtShutter(ns: Float): String {
    val denom = (1_000_000_000L / ns.toLong()).coerceAtLeast(1)
    return "1/${denom}s"
}