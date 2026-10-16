package app.cameraplus.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false

    fun bind(
        previewView: PreviewView,
        onError: (Exception) -> Unit = {},
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindInternal(provider, previewView)
            } catch (t: Throwable) {
                onError(t as? Exception ?: Exception(t))
            }
        }, mainExecutor)
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        imageCapture = null
    }

    fun flipLens(previewView: PreviewView, onError: (Exception) -> Unit = {}) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val provider = cameraProvider ?: return
        try {
            bindInternal(provider, previewView)
        } catch (t: Throwable) {
            onError(t as? Exception ?: Exception(t))
        }
    }

    fun setFlashEnabled(enabled: Boolean) {
        flashEnabled = enabled
        imageCapture?.flashMode = flashMode()
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

    private fun bindInternal(provider: ProcessCameraProvider, previewView: PreviewView) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
            .also { it.flashMode = flashMode() }
        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        imageCapture = capture
    }

    private fun flashMode(): Int = if (flashEnabled) {
        ImageCapture.FLASH_MODE_ON
    } else {
        ImageCapture.FLASH_MODE_OFF
    }
}
