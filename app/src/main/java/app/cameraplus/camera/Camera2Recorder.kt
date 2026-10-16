package app.cameraplus.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Slow motion and time lapse on raw `android.hardware.camera2` plus
 * [MediaRecorder] — the two modes CameraX has no API for. All decision-making
 * lives in [RecorderPlan]; this class only wires the plan onto the HAL, the
 * encoder and MediaStore.
 *
 * ## What it does
 *
 * Slow motion reads the back camera's `StreamConfigurationMap`, picks the
 * largest high-speed size at or below 1080p whose fps brackets cover the
 * requested rate, and records through
 * [CameraDevice.createConstrainedHighSpeedCaptureSession]. The encoder is told
 * `setCaptureRate(fps)` with `setVideoFrameRate(30)`, so 240 frames of sensor
 * time land in one second of video and play back eight times slower.
 *
 * Time lapse uses an ordinary `TEMPLATE_RECORD` session and the same trick the
 * other way round: `setCaptureRate(1 / intervalSeconds)` with a 30 fps video
 * frame rate, so a frame every four seconds plays back 120x faster than it
 * happened.
 *
 * Both save into MediaStore `Movies/Camera+` as MPEG-4/H.264 and return the
 * `Uri`, or `null` when the device cannot do what was asked (with [onError]
 * carrying the reason). Entries stay `IS_PENDING` while recording and are
 * published — or deleted, when nothing usable was written — when the clip ends.
 *
 * ## What it cannot do
 *
 * These are limits of the platform path, not oversights:
 *
 *  - **Audio is not dependable in high-speed sessions.** Most HALs will not keep
 *    a microphone path alive next to a constrained high-speed session, so
 *    [startSlowMotion]'s `audioEnabled` is honoured best effort: the audio
 *    source is configured, and the clip comes out silent — or the session is
 *    rejected and [onError] says so — on devices that refuse the combination.
 *    Stock slow motion is video-only on most phones for the same reason.
 *  - **No video snapshot.** The stock app can pull a still out of the
 *    high-speed stream; there is no supported way to do that outside the vendor
 *    pipeline.
 *  - **No vendor pipeline**: no HDR/Dolby/LUT handling, no wind-noise
 *    reduction, no stabilisation beyond whatever the HAL does for high speed.
 *    Frame cadence and bitrate are whatever the HAL and encoder negotiate.
 *  - **Quality is the device's, not ours.** [supportedSlowMotionRates] reports
 *    what the phone actually offers — plenty top out at 720p120 — and nothing
 *    is upscaled or frame-interpolated to fake a rate the sensor cannot give.
 *
 * ## Threading
 *
 * All camera2 and [MediaRecorder] work runs on one dedicated [HandlerThread]
 * (the shape the platform's own high-speed sample uses), so the blocking parts
 * — `prepare()`, `stop()`, `open()` — never run on the caller's thread. Every
 * wait on the HAL is bounded (see [OPEN_TIMEOUT_MS] and friends) and a timeout
 * surfaces as a plain [IllegalStateException] through [onError] instead of a
 * hang.
 *
 * ## Calling contract
 *
 *  - **Unbind CameraX first.** CameraX and this class both need exclusive
 *    ownership of the camera device; only one may be bound at a time (call
 *    `CameraSession.unbind()` before starting a slow-motion or time-lapse clip).
 *  - **CAMERA must be granted** (and RECORD_AUDIO too when asking for audio);
 *    a missing permission is reported, not worked around.
 *  - **The [textureView] must be attached to a window** and, for the preview to
 *    be pixel-exact, laid out at least as large as the recording size. Its
 *    buffer is sized to the recording through `setDefaultBufferSize`, so a
 *    smaller view still records — the preview is merely scaled down.
 *  - **Call [stop]** when the shutter is released and [close] when the recorder
 *    itself is discarded (which also stops a running clip).
 */
class Camera2Recorder(
    private val context: Context,
    private val textureView: TextureView,
    private val onError: (Exception) -> Unit = {},
    private val onStateChanged: (Boolean) -> Unit = {},
    private val onClipFinished: (Uri?) -> Unit = {},
) {
    private val appContext: Context = context.applicationContext
    private val cameraManager: CameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guarded by [lock]; written from the recorder thread and from stop(). */
    private val lock = Any()
    private var active: Recording? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var starting = false

    /** `true` while a clip is running or still being set up. */
    val isRecording: Boolean
        get() = synchronized(lock) { active != null || starting }

    // ---------------------------------------------------------------- public

    /**
     * Starts recording slow motion at [fps] through a constrained high-speed
     * session and suspends until the encoder is running.
     *
     * The caller's thread is never blocked on HAL calls: the work happens on
     * the recorder's own thread and each HAL step is awaited with a timeout.
     * Returns the MediaStore `Uri`, or `null` when this camera cannot record
     * [fps] at any size up to 1080p — [onError] explains why either way.
     *
     * @param audioEnabled best effort, see the class KDoc.
     */
    suspend fun startSlowMotion(fps: Int, audioEnabled: Boolean = false): Uri? =
        start(Requested(Kind.SLOW_MOTION, fps, 0f, audioEnabled))

    /**
     * Starts a time lapse — one frame every [intervalSeconds] of real time —
     * and suspends until the encoder is running. Same contract as
     * [startSlowMotion].
     *
     * The interval is clamped to `[MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS]`
     * because MediaRecorder implementations commonly reject capture rates
         * outside roughly 0.03…240 fps; the plan, and therefore the speed-up the
     * viewer ends up with, is computed from the clamped value.
     */
    suspend fun startTimeLapse(intervalSeconds: Float, audioEnabled: Boolean = false): Uri? =
        start(Requested(Kind.TIME_LAPSE, 0, intervalSeconds, audioEnabled))

    /**
     * Capture rates the back camera offers for slow motion at or below 1080p,
     * ascending — the entries a rate picker can show without lying. Empty when
     * the device has no high-speed support. Reads camera characteristics only
     * (no session, no preview), so it is cheap, but it does touch the HAL.
     */
    fun supportedSlowMotionRates(): List<Int> = runCatching {
        highSpeedOptions(backCamera().characteristics).flatMap { it.nominalRates() }
    }.getOrDefault(emptyList())
        .distinct()
        .sorted()

    /**
     * Stops the clip and releases the camera, session, encoder and file
     * descriptor, publishing or deleting the MediaStore entry. Safe when
     * nothing is running, safe to call twice, and it closes the camera even if
     * the encoder failed mid-clip.
     *
     * The blocking parts run on the recorder thread. The call waits up to
     * [STOP_WAIT_MS] for them, so the usual outcome is "returns once the file is
     * final"; when the HAL is slower than that it returns anyway and
     * [onStateChanged] lands when the teardown really finished.
     */
    fun stop() {
        val handler: Handler?
        val hadWork: Boolean
        synchronized(lock) {
            // Clearing this also cancels an in-flight start(): its last step
            // re-checks it and unwinds instead of leaving a clip running.
            hadWork = active != null || starting
            starting = false
            handler = workerHandler
        }
        if (!hadWork || handler == null) return

        val teardown = CountDownLatch(1)
        val posted = handler.post {
            try {
                finishRecording()
            } finally {
                notifyState(false)
                synchronized(lock) { shutdownWorker() }
                teardown.countDown()
            }
        }
        if (!posted) {
            notifyState(false)
            return
        }
        if (!teardown.await(STOP_WAIT_MS, TimeUnit.MILLISECONDS)) {
            // Teardown continues on the recorder thread; the state callback
            // above already told the caller that recording has ended.
        }
    }

    /**
     * Stops any running clip and shuts the recorder thread down for good. Call
     * when discarding the recorder (screen teardown); [stop] alone leaves the
     * thread parked and ready for the next start.
     */
    fun close() {
        stop()
        synchronized(lock) { shutdownWorker() }
    }

    // --------------------------------------------------------------- private

    /** Which mode a [Requested] asks for. */
    private enum class Kind { SLOW_MOTION, TIME_LAPSE }

    /** A camera as the HAL reports it: its id and its characteristics. */
    private class CameraRef(val id: String, val characteristics: CameraCharacteristics)

    /** What the caller asked for, before the device has been consulted. */
    private class Requested(
        val kind: Kind,
        val fps: Int,
        val intervalSeconds: Float,
        val audioEnabled: Boolean,
    )

    /** A request resolved against the camera: encoder geometry and rates. */
    private class Plan(
        val label: String,
        val width: Int,
        val height: Int,
        /** Frames per second the sensor is driven at. */
        val captureRate: Float,
        /** Frames per second written into the file. */
        val playbackFps: Int,
        /** Frame rate the bitrate is derived from (capture for slo-mo). */
        val bitrateFps: Int,
        val highSpeed: Boolean,
        val audioEnabled: Boolean,
        val fpsRange: IntRange?,
    )

    /**
     * One clip in flight. Fields are filled as the session is built and are
     * volatile because the building thread and the recorder thread both touch
     * them; [finishRecording] releases whatever exists, in reverse order.
     */
    private class Recording {
        @Volatile var device: CameraDevice? = null
        @Volatile var previewSurface: Surface? = null
        @Volatile var recorder: MediaRecorder? = null
        @Volatile var session: CameraCaptureSession? = null
        @Volatile var descriptor: ParcelFileDescriptor? = null
        @Volatile var uri: Uri? = null

        /** True once `MediaRecorder.start()` returned, i.e. real frames exist. */
        @Volatile var encoderRunning: Boolean = false
    }

    private suspend fun start(request: Requested): Uri? {
        synchronized(lock) {
            if (active != null || starting) {
                report(IllegalStateException("already recording — call stop() before starting another clip"))
                return null
            }
            starting = true
        }
        return try {
            val uri = runRecording(request)
            notifyState(true)
            uri
        } catch (t: Throwable) {
            report(t.asRecorderException("could not start ${request.kind} recording"))
            null
        } finally {
            synchronized(lock) { starting = false }
        }
    }

    /**
     * Builds the session. The [Recording] is registered before the first HAL
     * call so that a [stop] arriving mid-setup still releases whatever exists;
     * any failure unwinds through the same path, so the camera is never left
     * open and no empty MediaStore entry is left behind.
     */
    private suspend fun runRecording(request: Requested): Uri {
        val recording = Recording()
        synchronized(lock) { active = recording }
        try {
            ensureWorker()

            val camera = withCameraThread { backCamera() }
            val plan = withCameraThread { resolve(request, camera.characteristics) }
            checkStillStarting()

            recording.device = openCamera(camera.id)
            checkStillStarting()

            recording.previewSurface = previewSurface(plan.width, plan.height)
            checkStillStarting()

            val entry = withCameraThread { createMediaStoreEntry(plan.label) }
            recording.uri = entry.first
            recording.descriptor = entry.second

            recording.recorder = withCameraThread {
                MediaRecorder(appContext).apply { configure(this, plan, entry.second) }
            }
            recording.session = createSession(
                device = recording.device!!,
                recorder = recording.recorder!!,
                previewSurface = recording.previewSurface!!,
                highSpeed = plan.highSpeed,
            )
            checkStillStarting()

            withCameraThread { startEncoder(recording, plan) }
            return recording.uri!!
        } catch (t: Throwable) {
            releaseQuietly(recording)
            throw t
        }
    }

    /** Turns a user request into concrete encoder settings, or explains why not. */
    private fun resolve(request: Requested, characteristics: CameraCharacteristics): Plan {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("camera reports no stream configuration map")
        val prefix = if (request.kind == Kind.SLOW_MOTION) "SLOMO" else "TIMELAPSE"
        val label = "${prefix}_${timestamp()}.mp4"

        if (request.kind == Kind.SLOW_MOTION) {
            val slow = RecorderPlan.slowMotionPlan(request.fps)
            val options = highSpeedOptions(characteristics)
            if (options.isEmpty()) {
                throw IllegalStateException("this camera has no high-speed (slow motion) support")
            }
            val option = RecorderPlan.selectSlowMotionSize(options, slow.captureFps)
                ?: throw IllegalStateException(
                    "this camera cannot record ${slow.captureFps} fps at 1080p or below; it offers " +
                        options.flatMap { it.nominalRates() }.distinct().sorted(),
                )
            val range = option.bestRange(slow.captureFps)
                ?: throw IllegalStateException("no fps bracket covers ${slow.captureFps}")
            return Plan(
                label = label,
                width = option.width,
                height = option.height,
                captureRate = slow.captureFps.toFloat(),
                playbackFps = slow.playbackFps,
                bitrateFps = slow.captureFps,
                highSpeed = true,
                audioEnabled = request.audioEnabled,
                fpsRange = range,
            )
        }

        // Time lapse: an ordinary video stream driven far below its rate. The
        // plan is consulted for its capture rate only — how many frames a clip
        // ends up with is however long the shutter was held.
        val lapse = RecorderPlan.timeLapsePlan(
            intervalSeconds = request.intervalSeconds
                .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS),
            realSeconds = ESTIMATED_CLIP_SECONDS,
        )
        val size = videoSizes(map).maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: throw IllegalStateException("camera reports no supported video sizes")
        // The AE bracket stays at the camera's slowest: capturing far below any
        // bracket is the encoder's business, not auto-exposure's.
        val range = RecorderPlan.nearestFpsRange(
            aeBrackets(characteristics),
            lapse.captureFps,
        )
        return Plan(
            label = label,
            width = size.width,
            height = size.height,
            captureRate = lapse.captureFps,
            playbackFps = lapse.playbackFps,
            bitrateFps = lapse.playbackFps,
            highSpeed = false,
            audioEnabled = request.audioEnabled,
            fpsRange = range,
        )
    }

    /**
     * MPEG-4/H.264 with the capture/playback rate pair that produces the
     * effect: the sensor runs at [Plan.captureRate] while the file is written
     * at [Plan.playbackFps].
     */
    private fun configure(recorder: MediaRecorder, plan: Plan, descriptor: ParcelFileDescriptor) {
        if (plan.audioEnabled) requireMicPermission()
        val hint = orientationHint()
        // Portrait app, landscape sensor: the hint rotates the file, and the
        // encoder is told the dimensions it will actually end up producing.
        val portrait = hint == 90 || hint == 270

        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        if (plan.audioEnabled) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(descriptor.fileDescriptor)
        // ~8x the 1080p baseline at 240 fps; see RecorderPlan.bitrateFor.
        recorder.setVideoEncodingBitRate(RecorderPlan.bitrateFor(plan.width, plan.height, plan.bitrateFps))
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(
            if (portrait) plan.height else plan.width,
            if (portrait) plan.width else plan.height,
        )
        recorder.setOrientationHint(hint)
        recorder.setVideoFrameRate(plan.playbackFps)
        recorder.setCaptureRate(plan.captureRate.toDouble())
        recorder.prepare()
    }

    /** Configures the capture session: recorder surface plus the preview. */
    private suspend fun createSession(
        device: CameraDevice,
        recorder: MediaRecorder,
        previewSurface: Surface,
        highSpeed: Boolean,
    ): CameraCaptureSession = awaitBounded(SESSION_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val abandoned = AtomicBoolean(false)
            cont.invokeOnCancellation { abandoned.set(true) }
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (abandoned.get() || !cont.isActive) {
                        // Timed out or cancelled: the session must not leak.
                        closeQuietly(session)
                        return
                    }
                    cont.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    closeQuietly(session)
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException(
                                "camera rejected the " +
                                    (if (highSpeed) "constrained high-speed" else "plain recording") +
                                    " session (recorder + preview surface)",
                            ),
                        )
                    }
                }
            }
            val surfaces = listOf(recorder.surface, previewSurface)
            try {
                if (highSpeed) {
                    device.createConstrainedHighSpeedCaptureSession(surfaces, callback, cameraHandler())
                } else {
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(surfaces, callback, cameraHandler())
                }
            } catch (e: CameraAccessException) {
                if (cont.isActive) cont.resumeWithException(e)
            } catch (e: IllegalStateException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    } ?: throw IllegalStateException(
        "camera did not finish configuring the capture session within ${SESSION_TIMEOUT_MS}ms",
    )

    /**
     * Starts the repeating request and then the encoder.
     *
     * Both modes send a `TEMPLATE_RECORD` request whose
     * `CONTROL_AE_TARGET_FPS_RANGE` is the bracket the plan picked: the chosen
     * high-speed bracket for slow motion, the camera's slowest bracket for time
     * lapse. Constrained high-speed sessions require the platform-generated
     * request burst; a single repeating request is not valid for that session
     * type.
     */
    private fun startEncoder(recording: Recording, plan: Plan) {
        val device = recording.device ?: return
        val session = recording.session ?: return
        val recorder = recording.recorder ?: return
        val handler = cameraHandler()

        recorder.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
            ) {
                notifyState(false)
                handler.post { finishRecording() }
            }
        }
        recorder.setOnErrorListener { _, what, extra ->
            report(IllegalStateException("MediaRecorder failed (what=$what extra=$extra)"))
            notifyState(false)
            handler.post { finishRecording() }
        }

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(recorder.surface)
        recording.previewSurface?.let(builder::addTarget)
        plan.fpsRange?.let { range ->
            builder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(range.first, range.last),
            )
        }
        if (plan.highSpeed) {
            val highSpeedSession = session as? CameraConstrainedHighSpeedCaptureSession
                ?: throw IllegalStateException("high-speed camera did not create a constrained session")
            val requests = highSpeedSession.createHighSpeedRequestList(builder.build())
            highSpeedSession.setRepeatingBurst(requests, null, handler)
        } else {
            session.setRepeatingRequest(builder.build(), null, handler)
        }
        recorder.start()
        recording.encoderRunning = true
    }

    /**
     * Opens the camera, bounded: a HAL that never calls back must not hang the
     * UI, and a device that opens late is closed rather than leaked.
     */
    private suspend fun openCamera(cameraId: String): CameraDevice =
        awaitBounded(OPEN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val abandoned = AtomicBoolean(false)
                cont.invokeOnCancellation { abandoned.set(true) }
                val callback = object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (abandoned.get() || !cont.isActive) {
                            closeQuietly(device)
                            return
                        }
                        cont.resume(device)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        closeQuietly(device)
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("camera $cameraId disconnected while opening"),
                            )
                        }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        closeQuietly(device)
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("camera $cameraId failed to open (error $error)"),
                            )
                        }
                    }
                }
                try {
                    cameraManager.openCamera(cameraId, callback, cameraHandler())
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resumeWithException(e)
                } catch (e: IllegalArgumentException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        } ?: throw IllegalStateException("camera $cameraId did not open within ${OPEN_TIMEOUT_MS}ms")

    /**
     * The preview [Surface] from the caller's TextureView, with its buffer
     * sized to the recording.
     *
     * A TextureView has no SurfaceTexture until it is attached to a window, so
     * this waits for one, bounded: asking before the view is on screen is a
     * caller bug and is reported rather than waited on forever.
     */
    private suspend fun previewSurface(width: Int, height: Int): Surface =
        awaitBounded(PREVIEW_SURFACE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val handed = AtomicBoolean(false)
                cont.invokeOnCancellation {
                    mainHandler.post {
                        if (handed.compareAndSet(false, true)) textureView.surfaceTextureListener = null
                    }
                }
                mainHandler.post {
                    if (!cont.isActive) return@post

                    fun hand(texture: SurfaceTexture) {
                        if (!handed.compareAndSet(false, true)) return
                        textureView.surfaceTextureListener = null
                        // Sized to the recording rather than to the view, so a
                        // smaller preview still shows the whole frame, scaled.
                        texture.setDefaultBufferSize(width, height)
                        cont.resume(Surface(texture))
                    }

                    fun unavailable() {
                        if (!handed.compareAndSet(false, true)) return
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException(
                                    "the preview TextureView was destroyed before it became available",
                                ),
                            )
                        }
                    }

                    textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                            hand(texture)
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) = Unit

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            unavailable()
                            return true
                        }
                    }
                    // The texture may have become available while the listener
                    // was being installed, which never calls back for it.
                    if (textureView.isAvailable) {
                        textureView.surfaceTexture?.let { hand(it) } ?: unavailable()
                    }
                }
            }
        } ?: throw IllegalStateException(
            "the preview TextureView produced no surface within ${PREVIEW_SURFACE_TIMEOUT_MS}ms — " +
                "is it attached to a window, and at least ${width}x$height?",
        )

    private fun createMediaStoreEntry(label: String): Pair<Uri, ParcelFileDescriptor> {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, label)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + ALBUM)
            // Invisible to other apps until the clip is finalised.
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = appContext.contentResolver
            .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused a new video entry")
        val descriptor = try {
            appContext.contentResolver.openFileDescriptor(uri, "w")
        } catch (e: Exception) {
            deleteEntry(uri)
            throw e
        } ?: run {
            deleteEntry(uri)
            throw IllegalStateException("MediaStore gave no file for $uri")
        }
        return uri to descriptor
    }

    private fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        runCatching { appContext.contentResolver.update(uri, values, null, null) }
    }

    private fun deleteEntry(uri: Uri) {
        runCatching { appContext.contentResolver.delete(uri, null, null) }
    }

    // -------------------------------------------------------------- teardown

    /**
     * Stops the encoder, closes the session and the camera, then publishes or
     * deletes the file. Every step is individually guarded, so a clip that
     * failed halfway still ends up closing the camera. Runs on the recorder
     * thread.
     */
    private fun finishRecording() {
        val recording = synchronized(lock) { active.also { active = null } } ?: return

        val recorder = recording.recorder
        val wrote = when {
            recorder == null -> false
            recording.encoderRunning -> stopEncoder(recorder)
            // Never started: reset frees the codec, and there is no clip.
            else -> {
                runCatching { recorder.reset() }
                false
            }
        }
        closeQuietly(recording.session)
        closeQuietly(recording.device)
        closeQuietly(recording.previewSurface)
        runCatching { recorder?.release() }
        recording.recorder = null
        runCatching { recording.descriptor?.close() }

        val uri = recording.uri
        if (uri != null) {
            if (wrote) publish(uri) else deleteEntry(uri)
            mainHandler.post { runCatching { onClipFinished(if (wrote) uri else null) } }
        }
    }

    /**
     * `MediaRecorder.stop()` throws when the encoder never really ran (or was
     * already stopped). That file holds no frames, so the caller has to know:
     * `true` only when the clip was really finalised.
     */
    private fun stopEncoder(recorder: MediaRecorder): Boolean = try {
        recorder.stop()
        true
    } catch (e: RuntimeException) {
        false
    }

    /**
     * Failure-path teardown: normally on the recorder thread, but if that
     * thread is gone (or refusing work) the same release runs inline rather
     * than leaking the camera.
     */
    private suspend fun releaseQuietly(recording: Recording) {
        runCatching { withCameraThread { finishRecording() } }
            .onFailure { finishRecording() }
    }

    // --------------------------------------------------------------- devices

    /** Back camera, preferring the biggest sensor (the main lens). */
    private fun backCamera(): CameraRef {
        val ids = cameraManager.cameraIdList
        if (ids.isEmpty()) throw IllegalStateException("device reports no cameras")
        val read = ids.map { id -> id to runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull() }
        val backs = read.filter { (_, characteristics) ->
            characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        // No back-facing camera reported (a front-only device, or a broken HAL):
        // fall back to whatever is listed rather than refusing outright.
        return backs.ifEmpty { read }
            .mapNotNull { (id, characteristics) -> characteristics?.let { CameraRef(id, it) } }
            .maxByOrNull { sensorPixels(it.characteristics) }
            ?: throw IllegalStateException("no usable camera characteristics")
    }

    private fun sensorPixels(characteristics: CameraCharacteristics): Long =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            ?.let { it.width.toLong() * it.height.toLong() } ?: 0L

    /** High-speed sizes of one camera, each with its fps brackets. */
    private fun highSpeedOptions(characteristics: CameraCharacteristics): List<HighSpeedOption> {
        val map: StreamConfigurationMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return emptyList()
        return map.getHighSpeedVideoSizes().map { size ->
            HighSpeedOption(
                width = size.width,
                height = size.height,
                fpsRanges = fpsRanges(map.getHighSpeedVideoFpsRangesFor(size)),
            )
        }
    }

    /** Sizes the recorder can be configured with, i.e. plain video output. */
    private fun videoSizes(map: StreamConfigurationMap): List<Size> =
        map.getOutputSizes(MediaRecorder::class.java).toList()

    /** Every AE frame-rate bracket the camera advertises. */
    private fun aeBrackets(characteristics: CameraCharacteristics): List<IntRange> =
        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.let { fpsRanges(it) }
            ?: emptyList()

    private fun fpsRanges(ranges: Array<Range<Int>>): List<IntRange> =
        ranges.map { IntRange(it.lower, it.upper) }

    private fun requireMicPermission() {
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw SecurityException("RECORD_AUDIO is not granted; ask for it or start without audio")
        }
    }

    /**
     * Rotation hint for a portrait app holding a landscape stream: without it
     * every clip comes out sideways. Falls back to the portrait default when no
     * window is reachable.
     */
    private fun orientationHint(): Int {
        val rotation = runCatching {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
        }.getOrNull() ?: Surface.ROTATION_0
        return when (rotation) {
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
    }

    // -------------------------------------------------------------- plumbing

    /** Runs [block] on the recorder thread and awaits its result. */
    private suspend fun <T> withCameraThread(block: () -> T): T =
        suspendCancellableCoroutine { cont ->
            val posted = ensureWorker().post {
                try {
                    cont.resume(block())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }
            if (!posted) {
                cont.resumeWithException(IllegalStateException("the recorder thread is shutting down"))
            }
        }

    /**
     * Runs a HAL step with a hard deadline, `null` when it expires.
     *
     * The step registers itself on the recorder thread and is completed from
     * the HAL callback there, so what is bounded is the wait, not the work.
     */
    private suspend fun <T> awaitBounded(timeoutMs: Long, block: suspend () -> T): T? =
        withTimeoutOrNull(timeoutMs) { block() }

    /** Bails out of a setup that a [stop] has already cancelled. */
    private fun checkStillStarting() {
        if (!synchronized(lock) { starting }) {
            throw IllegalStateException("start was cancelled by stop() before the first frame")
        }
    }

    private fun ensureWorker(): Handler {
        synchronized(lock) {
            workerHandler?.let { if (worker?.isAlive == true) return it }
            val thread = HandlerThread("Camera2Recorder").also { it.start() }
            worker = thread
            return Handler(thread.looper).also { workerHandler = it }
        }
    }

    private fun cameraHandler(): Handler = ensureWorker()

    private fun shutdownWorker() {
        worker?.quitSafely()
        worker = null
        workerHandler = null
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun notifyState(recording: Boolean) {
        mainHandler.post { runCatching { onStateChanged(recording) } }
    }

    private fun report(error: Exception) {
        mainHandler.post { runCatching { onError(error) } }
    }

    private fun closeQuietly(device: CameraDevice?) {
        if (device == null) return
        runCatching { device.close() }
    }

    private fun closeQuietly(session: CameraCaptureSession?) {
        if (session == null) return
        runCatching { session.close() }
    }

    private fun closeQuietly(surface: Surface?) {
        if (surface == null) return
        runCatching { surface.release() }
    }

    private companion object {
        const val MIME_TYPE = "video/mp4"
        const val ALBUM = "Camera+"

        /** Bounded waits: a stuck HAL must surface an error, not a hang. */
        const val OPEN_TIMEOUT_MS = 3_000L
        const val SESSION_TIMEOUT_MS = 3_000L
        const val PREVIEW_SURFACE_TIMEOUT_MS = 2_000L

        /** How long [stop] waits for teardown before returning anyway. */
        const val STOP_WAIT_MS = 1_500L

        /** Capture rates MediaRecorder implementations actually accept. */
        const val MIN_INTERVAL_SECONDS = 0.2f
        const val MAX_INTERVAL_SECONDS = 32f

        /**
         * Only used to turn a time-lapse interval into a capture rate; the real
         * frame count depends on how long the shutter is held.
         */
        const val ESTIMATED_CLIP_SECONDS = 60f
    }
}

/** Adds context to anything thrown, so the UI can show something useful. */
internal fun Throwable.asRecorderException(context: String): Exception = when (this) {
    is Exception -> message?.takeIf { it.isNotBlank() }
        ?.let { IllegalStateException("$context: $it") }
        ?: this

    else -> RuntimeException(context, this)
}
