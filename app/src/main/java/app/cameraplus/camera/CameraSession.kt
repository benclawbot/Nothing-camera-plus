package app.cameraplus.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.cameraplus.processing.JpegProcessor
import app.cameraplus.settings.ProcessingSettings
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around CameraX: binds Preview + ImageCapture to a [PreviewView]
 * for the given lifecycle, supports lens flipping, flash toggle, and saves
 * stills through MediaStore (Pictures/Camera+).
 *
 * Instant-shutter behaviour (M2): when the device supports private
 * reprocessing and flash is off, captures use the zero-shutter-lag stream, so
 * the frame is taken from just before the tap and processing happens
 * afterwards. Flash forces the standard path, so toggling flash or flipping
 * lenses rebinds the session whenever the effective capture mode changes.
 * Captures are never blocked: repeated taps queue up.
 *
 * Processing (M3): the neutral intent takes the fast path straight to
 * MediaStore; any non-neutral intent is applied in software to the frame in
 * memory on a background thread before the file is written.
 *
 * All public methods are main-thread only, matching where CameraX delivers
 * its callbacks via the main executor.
 */
class CameraSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val ioExecutor: Executor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var boundPreviewView: PreviewView? = null
    private var onZslChanged: (Boolean) -> Unit = {}
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false
    private var zeroShutterLagSupported = false
    private var appliedCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY

    private var errorSink: (Exception) -> Unit = {}
    private var lookEnabled = false
    private val lookParameters = LookParameters()
    private var lookEffect: LookEffect? = null

    fun bind(
        previewView: PreviewView,
        onZslChanged: (Boolean) -> Unit = {},
        onError: (Exception) -> Unit = {},
    ) {
        boundPreviewView = previewView
        this.onZslChanged = onZslChanged
        errorSink = onError
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                rebind()
            } catch (t: Throwable) {
                onError(t.asException())
            }
        }, mainExecutor)
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        boundCamera = null
        imageCapture = null
    }

    fun flipLens(onError: (Exception) -> Unit = {}) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        rebind(onError)
    }

    fun setFlashEnabled(enabled: Boolean, onError: (Exception) -> Unit = {}) {
        if (flashEnabled == enabled) return
        flashEnabled = enabled
        val desiredZsl = CaptureProfile.usesZeroShutterLag(zeroShutterLagSupported, flashEnabled)
        val appliedZsl = appliedCaptureMode == ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
        if (desiredZsl != appliedZsl) {
            // The effective capture mode changes with flash: rebind so the
            // session really runs in the mode the policy picked.
            rebind(onError)
        } else {
            imageCapture?.flashMode = flashMode()
        }
    }

    /**
     * Updates the live preview look (M3.3): the same intent that shapes saved
     * stills is mirrored onto the viewfinder through a GL effect. Enabling or
     * disabling the effect changes the session shape, so it rebinds; strength
     * changes while active only update uniforms and apply to the next frame.
     */
    fun setLook(settings: ProcessingSettings, onError: (Exception) -> Unit = {}) {
        lookParameters.update(settings)
        val desired = !lookParameters.isNeutral()
        if (desired != lookEnabled) {
            lookEnabled = desired
            rebind(onError)
        }
    }

    private fun ensureLookEffect(): LookEffect {
        return lookEffect ?: LookEffect(lookParameters).also { effect ->
            effect.onError { throwable -> errorSink(throwable.asException()) }
            lookEffect = effect
        }
    }

    /**
     * Tap-to-focus / tap-to-meter (M5): runs a combined AF+AE metering action
     * around the tapped point in preview-view coordinates. It auto-cancels,
     * so the camera returns to its own judgement after a few seconds.
     */
    fun focusAndMeterAt(x: Float, y: Float) {
        val previewView = boundPreviewView ?: return
        val camera = boundCamera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    /**
     * Captures a still. [settings] controls whether the frame goes straight
     * to MediaStore (neutral intent) or through software processing first.
     */
    fun capture(
        settings: ProcessingSettings,
        onSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera is not ready"))
            return
        }
        if (settings == ProcessingSettings.NATURAL) {
            captureToFile(capture, onSaved, onError)
        } else {
            captureToMemory(capture, settings, onSaved, onError)
        }
    }

    private fun createEntry(): Pair<Uri, ContentValues>? {
        val name = "CameraPlus_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera+")
        }
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values) ?: return null
        return uri to values
    }

    /** Fast path: the JPEG goes straight from the camera to the file. */
    private fun captureToFile(
        capture: ImageCapture,
        onSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val entry = createEntry() ?: run {
            onError(IllegalStateException("Could not create a MediaStore entry"))
            return
        }
        val (uri, values) = entry
        val options = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, uri, values)
            .build()
        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved(outputFileResults.savedUri ?: uri)
            }

            override fun onError(exception: ImageCaptureException) {
                // Do not leave a dangling empty entry behind.
                context.contentResolver.delete(uri, null, null)
                onError(exception)
            }
        })
    }

    /** Processed path: decode, apply the intent, encode, write. */
    private fun captureToMemory(
        capture: ImageCapture,
        settings: ProcessingSettings,
        onSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val entry = createEntry() ?: run {
            onError(IllegalStateException("Could not create a MediaStore entry"))
            return
        }
        val (uri, _) = entry
        capture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val jpeg = image.toJpegBytes()
                image.close()
                if (jpeg == null) {
                    context.contentResolver.delete(uri, null, null)
                    onError(IllegalStateException("Capture did not produce a JPEG frame"))
                    return
                }
                val resolver = context.contentResolver
                ioExecutor.execute {
                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            JpegProcessor.process(jpeg, settings, out)
                        } ?: throw IllegalStateException("Could not open the output stream")
                        mainExecutor.execute { onSaved(uri) }
                    } catch (t: Throwable) {
                        resolver.delete(uri, null, null)
                        mainExecutor.execute { onError(t.asException()) }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                context.contentResolver.delete(uri, null, null)
                onError(exception)
            }
        })
    }

    private fun ImageProxy.toJpegBytes(): ByteArray? {
        if (format != ImageFormat.JPEG) return null
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun rebind(onError: (Exception) -> Unit = {}) {
        val provider = cameraProvider ?: return
        val previewView = boundPreviewView ?: return
        try {
            bindInternal(provider, previewView)
        } catch (t: Throwable) {
            onError(t.asException())
        }
    }

    private fun bindInternal(provider: ProcessCameraProvider, previewView: PreviewView) {
        zeroShutterLagSupported = detectZeroShutterLagSupport(provider)
        val captureMode = desiredCaptureMode()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(captureMode)
            .build()
            .also { it.flashMode = flashMode() }
        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        provider.unbindAll()
        val sessionConfig = SessionConfig.Builder(preview, capture).apply {
            if (lookEnabled) addEffect(ensureLookEffect())
        }.build()
        boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, sessionConfig)
        imageCapture = capture
        appliedCaptureMode = captureMode
        onZslChanged(captureMode == ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
    }

    private fun desiredCaptureMode(): Int =
        if (CaptureProfile.usesZeroShutterLag(zeroShutterLagSupported, flashEnabled)) {
            ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
        } else {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }

    /**
     * Zero shutter lag needs a private reprocessing stream; probes the
     * characteristics of the camera for the currently selected lens.
     */
    private fun detectZeroShutterLagSupport(provider: ProcessCameraProvider): Boolean {
        val info = provider.availableCameraInfos.firstOrNull { cameraInfo ->
            Camera2CameraInfo.from(cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == lensFacing
        } ?: return false
        val capabilities = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return false
        return capabilities.any {
            it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING
        }
    }

    private fun flashMode(): Int = if (flashEnabled) {
        ImageCapture.FLASH_MODE_ON
    } else {
        ImageCapture.FLASH_MODE_OFF
    }

    private fun Throwable.asException(): Exception = this as? Exception ?: Exception(this)
}
