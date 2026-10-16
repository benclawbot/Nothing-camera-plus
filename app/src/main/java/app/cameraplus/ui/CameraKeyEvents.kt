package app.cameraplus.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** MainActivity-to-Compose bridge for the opt-in volume-key shutter. */
object CameraKeyEvents {
    val volumeShutter = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun emitVolumeShutter() {
        volumeShutter.tryEmit(Unit)
    }
}
