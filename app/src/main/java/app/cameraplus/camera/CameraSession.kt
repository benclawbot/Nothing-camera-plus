package app.cameraplus.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

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
 * All methods are main-thread only, matching where CameraX delivers its
 * callbacks via the main executor.
 */
class CameraSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var boundPreviewView: PreviewView? = null
    private var onZslChanged: (Boolean) -> Unit = {}
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false
    private var zeroShutterLagSupported = false
    private var appliedCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY

    fun bind(
        previewView: PreviewView,
        onZslChanged: (Boolean) -> Unit = {},
        onError: (Exception) -> Unit = {},
    ) {
        boundPreviewView = previewView
        this.onZslChanged = onZslChanged
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

    fun capture(onSaved: (Uri) -> Unit, onError: (Exception) -> Unit) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera is not ready"))
            return
        }

        val name = "CameraPlus_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera+")
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: run {
            onError(IllegalStateException("Could not create a MediaStore entry"))
            return
        }

        val options = ImageCapture.OutputFileOptions.Builder(resolver, uri, values).build()
        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved(outputFileResults.savedUri ?: uri)
            }

            override fun onError(exception: ImageCaptureException) {
                // Do not leave a dangling empty entry behind.
                resolver.delete(uri, null, null)
                onError(exception)
            }
        })
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
        provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
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
