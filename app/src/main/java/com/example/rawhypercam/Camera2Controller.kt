package com.example.rawhypercam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class Camera2Controller(
    private val context: Context,
    private val onLensList: (List<LensInfo>) -> Unit,
    private val onPreviewSize: (Size) -> Unit,
    private val onError: (String) -> Unit
) {
    data class LensInfo(
        val id: String,
        val name: String,
        val focalLength: Float,
        val hasOis: Boolean
    )

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var currentLens: LensInfo? = null
    private var currentRequestBuilder: CaptureRequest.Builder? = null
    private var pendingRawFile: File? = null

    var iso: Int = 400
    var exposureTimeNs: Long = 1_000_000L
    var focusDistance: Float = 0f
    var evCompensation: Int = 0
    var isProMode: Boolean = true

    fun start() {
        backgroundThread = HandlerThread("Camera2BG").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        discoverLenses()
    }

    private fun discoverLenses() {
        try {
            val lenses = mutableListOf<LensInfo>()
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) continue

                val isLogicalMulti = caps.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                )
                if (isLogicalMulti) {
                    for (physId in chars.physicalCameraIds) {
                        val physChars = cameraManager.getCameraCharacteristics(physId)
                        val physCaps = physChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                        if (physCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                            lenses.add(buildLensInfo(physId, physChars))
                        }
                    }
                } else {
                    lenses.add(buildLensInfo(id, chars))
                }
            }
            lenses.sortBy { it.focalLength }
            onLensList(lenses)
        } catch (e: Exception) {
            onError("Discovery failed: ${e.message}")
        }
    }

    private fun buildLensInfo(id: String, chars: CameraCharacteristics): LensInfo {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: 0f
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
    ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        val name = when {
            focal < 3.5f -> "0.6x"
            focal < 5.5f -> "1x"
            else -> "2.6x"
        }
        return LensInfo(id, name, focal, ois)
    }

    fun openLens(lens: LensInfo, texture: SurfaceTexture) {
        currentLens = lens
        try {
            cameraDevice?.close()
            previewSurface?.release()

            val chars = cameraManager.getCameraCharacteristics(lens.id)
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val aspect = if (activeArray != null && activeArray.height() > 0)
                activeArray.width().toFloat() / activeArray.height() else 4f / 3f
            val previewW = 1080
            val previewH = (previewW / aspect).toInt()
            onPreviewSize(Size(previewW, previewH))

            texture.setDefaultBufferSize(previewW, previewH)
            previewSurface = Surface(texture)

            @Suppress("MissingPermission")
            cameraManager.openCamera(lens.id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createPreviewSession()
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close(); cameraDevice = null
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null
                    onError("Camera error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            onError("openLens failed: ${e.message}")
        }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        try {
            device.createCaptureSession(listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        sendPreviewRequest()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError("Session config failed")
                    }
                }, backgroundHandler)
        } catch (e: Exception) {
            onError("createCaptureSession failed: ${e.message}")
        }
    }

    private fun sendPreviewRequest() {
    val device = cameraDevice ?: return
    val session = captureSession ?: return
    val surface = previewSurface ?: return
    val lens = currentLens ?: return
    try {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface)

        if (lens.hasOis) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        if (isProMode) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            if (focusDistance > 0f) {
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evCompensation)
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        // ✅ Центровзвешенный замер через CONTROL_AE_REGIONS
        val chars = cameraManager.getCameraCharacteristics(lens.id)
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (activeArray != null) {
            val centerX = activeArray.width() / 2
            val centerY = activeArray.height() / 2
            val radius = (activeArray.width() * 0.15f).toInt()
            val centerRect = MeteringRectangle(
                (centerX - radius).coerceAtLeast(0),
                (centerY - radius).coerceAtLeast(0),
                (radius * 2).coerceAtMost(activeArray.width()),
                (radius * 2).coerceAtMost(activeArray.height()),
                MeteringRectangle.METERING_WEIGHT_MAX
            )
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(centerRect))
        }

        currentRequestBuilder = builder
        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
    } catch (e: Exception) {
        onError("Preview request failed: ${e.message}")
    }
}

    fun tapToFocus(x: Float, y: Float, viewW: Int, viewH: Int) {
        val builder = currentRequestBuilder ?: return
        val lens = currentLens ?: return
        val session = captureSession ?: return
        try {
            val chars = cameraManager.getCameraCharacteristics(lens.id)
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val nx = (x / viewW).coerceIn(0f, 1f)
            val ny = (y / viewH).coerceIn(0f, 1f)
            val cx = (nx * activeArray.width()).toInt()
            val cy = (ny * activeArray.height()).toInt()
            val r = (activeArray.width() * 0.05f).toInt()
            val rect = MeteringRectangle(
                (cx - r).coerceAtLeast(0),
                (cy - r).coerceAtLeast(0),
                (r * 2).coerceAtMost(activeArray.width()),
                (r * 2).coerceAtMost(activeArray.height()),
                MeteringRectangle.METERING_WEIGHT_MAX
            )
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("Camera2", "tapToFocus failed", e)
        }
    }

    fun updateManualControls(newIso: Int, newShutterNs: Long, newFocus: Float) {
        iso = newIso
        exposureTimeNs = newShutterNs
        focusDistance = newFocus
        if (isProMode) sendPreviewRequest()
    }

    fun updateEv(ev: Int) {
        evCompensation = ev
        if (!isProMode) sendPreviewRequest()
    }

    fun captureRaw(outputFile: File) {
        val device = cameraDevice ?: run { onError("No camera"); return }
        val lens = currentLens ?: return
        val surface = previewSurface ?: return
        try {
            val chars = cameraManager.getCameraCharacteristics(lens.id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width * it.height } ?: return

            rawReader?.close()
            rawReader = ImageReader.newInstance(
                rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 1
            )
            val rawSurface = rawReader!!.surface
            pendingRawFile = outputFile

            rawReader!!.setOnImageAvailableListener({ reader ->
                val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                saveRaw(img, pendingRawFile)
                img.close()
            }, backgroundHandler)

            device.createCaptureSession(listOf(surface, rawSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                            builder.addTarget(surface)
                            builder.addTarget(rawSurface)
                            if (lens.hasOis) {
                                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                    CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)
                            }
                            if (isProMode) {
                                builder.set(CaptureRequest.CONTROL_AE_MODE,
                                    CameraMetadata.CONTROL_AE_MODE_OFF)
                                builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
                                if (focusDistance > 0f) {
                                    builder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CameraMetadata.CONTROL_AF_MODE_OFF)
                                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                                }
                            } else {
                                builder.set(CaptureRequest.CONTROL_AE_MODE,
                                    CameraMetadata.CONTROL_AE_MODE_ON)
                                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                                    evCompensation)
                            }
                            session.capture(builder.build(),
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult
                                    ) {
                                        backgroundHandler?.postDelayed(
                                            { createPreviewSession() }, 400
                                        )
                                    }
                                }, backgroundHandler)
                        } catch (e: Exception) {
                            onError("Capture failed: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError("Still session config failed")
                    }
                }, backgroundHandler)
        } catch (e: Exception) {
            onError("captureRaw failed: ${e.message}")
        }
    }

    private fun saveRaw(image: android.media.Image, file: File?) {
        if (file == null) return
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            FileOutputStream(file).use { it.write(bytes) }
            Log.d("RAW", "Saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("RAW", "saveRaw failed", e)
        }
    }

    fun close() {
        try {
            captureSession?.close()
            cameraDevice?.close()
            rawReader?.close()
            previewSurface?.release()
        } catch (_: Exception) { }
        backgroundThread?.quitSafely()
    }
}
