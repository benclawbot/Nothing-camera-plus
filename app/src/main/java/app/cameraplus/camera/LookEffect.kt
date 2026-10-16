package app.cameraplus.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.ProcessingException
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import app.cameraplus.settings.ProcessingSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Live look strengths for the preview effect. Written from the main thread
 * when the processing sheet changes, read by the GL thread per frame; the
 * volatile floats make the hand-off lock-free.
 */
class LookParameters {
    @Volatile var hdr: Float = 0f
    @Volatile var noiseReduction: Float = 0f
    @Volatile var skinSmoothing: Float = 0f
    @Volatile var sharpening: Float = 0f
    @Volatile var filter: Int = 0

    fun update(settings: ProcessingSettings) {
        hdr = settings.hdr
        noiseReduction = settings.noiseReduction
        skinSmoothing = settings.skinSmoothing
        sharpening = settings.sharpening
        filter = settings.filter.ordinal
    }

    fun isNeutral(): Boolean =
        hdr == 0f && noiseReduction == 0f && skinSmoothing == 0f && sharpening == 0f && filter == 0
}

/** Error hand-off between the GL thread and the session. */
private class EffectHooks {
    @Volatile
    var listener: (Throwable) -> Unit = {}

    fun raise(error: Throwable) {
        listener(error)
    }
}

/** A dedicated thread owns every GL object; all calls post onto it. */
private object LookThread {
    private val thread = HandlerThread("camera-plus-look").apply { start() }
    val handler: Handler = Handler(thread.looper)
    val executor: Executor = Executor { command -> handler.post(command) }
}

/**
 * M3.3 preview parity: a GL effect on the preview stream that mirrors the
 * still-image pipeline ([app.cameraplus.processing.ImageProcessor]) so the
 * viewfinder shows the intent that will be applied to the saved photo.
 *
 * The math matches the CPU pipeline step for step — same tone curve, same
 * blend factors, same skin gate — with the spatial steps (noise reduction,
 * skin smoothing, sharpening) approximated by a fixed small-kernel GLSL
 * version. It is a close look match, not a pixel-identical one; the saved
 * image remains the authority.
 *
 * The effect handles the camera and surface rotation itself
 * ([CameraEffect.TRANSFORMATION_CAMERA_AND_SURFACE_ROTATION]) using the
 * SurfaceTexture transform matrix.
 */
class LookEffect private constructor(
    parameters: LookParameters,
    private val hooks: EffectHooks,
) : CameraEffect(
    CameraEffect.PREVIEW,
    CameraEffect.TRANSFORMATION_CAMERA_AND_SURFACE_ROTATION,
    CameraEffect.OUTPUT_OPTION_ONE_FOR_ALL_TARGETS,
    LookThread.executor,
    LookRenderer(parameters, hooks),
    Consumer { throwable -> hooks.raise(throwable) },
) {

    constructor(parameters: LookParameters = LookParameters()) : this(parameters, EffectHooks())

    /** Registers the error sink (called from the session on every bind). */
    fun onError(listener: (Throwable) -> Unit) {
        hooks.listener = listener
    }
}

/**
 * SurfaceProcessor that renders camera frames through the look shader into
 * the effect's output surface. Owns the EGL context, the input SurfaceTexture
 * and the per-target window surface.
 */
private class LookRenderer(
    private val parameters: LookParameters,
    private val hooks: EffectHooks,
) : SurfaceProcessor {

    private val handler = LookThread.handler

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var initialized = false

    private var program = 0
    private var attrPosition = -1
    private var attrTexCoord = -1
    private var uniformTexMatrix = -1
    private var uniformTexture = -1
    private var uniformTexel = -1
    private var uniformHdr = -1
    private var uniformNr = -1
    private var uniformSkin = -1
    private var uniformSharpen = -1
    private var uniformFilter = -1

    private var textureId = 0
    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inputWidth = 1
    private var inputHeight = 1

    private var outputSurface: Surface? = null
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputWidth = 1
    private var outputHeight = 1

    private var frameAvailable = false
    private val texMatrix = FloatArray(16)

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(16 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            // x, y, u, v — a triangle strip covering the viewport.
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                ),
            )
            position(0)
        }

    override fun onInputSurface(request: SurfaceRequest) {
        handler.post {
            try {
                ensureInitialized()
                releaseInput()
                makeIdleCurrent()

                val names = IntArray(1)
                GLES20.glGenTextures(1, names, 0)
                textureId = names[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR,
                )
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR,
                )
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE,
                )
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE,
                )

                val texture = SurfaceTexture(textureId)
                texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                texture.setOnFrameAvailableListener(
                    {
                        frameAvailable = true
                        handler.post(::drawFrame)
                    },
                    handler,
                )
                inputTexture = texture
                inputWidth = request.resolution.width
                inputHeight = request.resolution.height

                val surface = Surface(texture)
                inputSurface = surface
                request.provideSurface(surface, LookThread.executor) { result ->
                    if (result.resultCode != SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY) {
                        handler.post { releaseInput() }
                    }
                }
            } catch (t: Throwable) {
                hooks.raise(asProcessingError(t))
            }
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        handler.post {
            try {
                ensureInitialized()
                val surface = output.getSurface(LookThread.executor) { event ->
                    if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                        handler.post { detachOutput() }
                    }
                }
                if (surface == null) {
                    throw IllegalStateException("effect output surface is not available")
                }
                outputWidth = output.size.width.coerceAtLeast(1)
                outputHeight = output.size.height.coerceAtLeast(1)
                attachOutput(surface)
            } catch (t: Throwable) {
                hooks.raise(asProcessingError(t))
            }
        }
    }

    private fun asProcessingError(t: Throwable): Throwable =
        if (t is ProcessingException) t else IllegalStateException("preview look effect failed", t)

    private fun drawFrame() {
        if (!frameAvailable) return
        val texture = inputTexture ?: return
        if (windowSurface == EGL14.EGL_NO_SURFACE || program == 0) return
        frameAvailable = false
        try {
            EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)

            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(
                attrPosition, 2, GLES20.GL_FLOAT, false, 4 * Float.SIZE_BYTES, vertexBuffer,
            )
            GLES20.glEnableVertexAttribArray(attrPosition)
            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(
                attrTexCoord, 2, GLES20.GL_FLOAT, false, 4 * Float.SIZE_BYTES, vertexBuffer,
            )
            GLES20.glEnableVertexAttribArray(attrTexCoord)

            GLES20.glUniformMatrix4fv(uniformTexMatrix, 1, false, texMatrix, 0)
            GLES20.glUniform2f(uniformTexel, 1f / inputWidth, 1f / inputHeight)
            GLES20.glUniform1f(uniformHdr, parameters.hdr)
            GLES20.glUniform1f(uniformNr, parameters.noiseReduction)
            GLES20.glUniform1f(uniformSkin, parameters.skinSmoothing)
            GLES20.glUniform1f(uniformSharpen, parameters.sharpening)
            GLES20.glUniform1f(uniformFilter, parameters.filter.toFloat())

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uniformTexture, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(display, windowSurface)
        } catch (t: Throwable) {
            hooks.raise(asProcessingError(t))
        }
    }

    private fun attachOutput(surface: Surface) {
        detachOutput()
        val chosen = config ?: throw IllegalStateException("EGL is not initialized")
        windowSurface = EGL14.eglCreateWindowSurface(
            display, chosen, surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        if (windowSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("could not attach the effect output surface")
        }
        outputSurface = surface
    }

    private fun detachOutput() {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
        outputSurface = null
    }

    private fun releaseInput() {
        inputTexture?.setOnFrameAvailableListener(null)
        inputTexture?.release()
        inputTexture = null
        inputSurface?.release()
        inputSurface = null
        if (textureId != 0) {
            makeIdleCurrent()
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        frameAvailable = false
    }

    private fun makeIdleCurrent() {
        EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
    }

    private fun ensureInitialized() {
        if (initialized) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("no EGL display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw IllegalStateException("could not initialize EGL")
        }
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw IllegalStateException("no suitable EGL config")
        }
        val chosen = configs[0] ?: throw IllegalStateException("no suitable EGL config")
        config = chosen
        context = EGL14.eglCreateContext(
            display,
            chosen,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("could not create the EGL context")
        }
        idleSurface = EGL14.eglCreatePbufferSurface(
            display,
            chosen,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        if (idleSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("could not create the idle EGL surface")
        }
        makeIdleCurrent()
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        attrPosition = GLES20.glGetAttribLocation(program, "aPosition")
        attrTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uniformTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uniformTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uniformTexel = GLES20.glGetUniformLocation(program, "uTexel")
        uniformHdr = GLES20.glGetUniformLocation(program, "uHdr")
        uniformNr = GLES20.glGetUniformLocation(program, "uNr")
        uniformSkin = GLES20.glGetUniformLocation(program, "uSkin")
        uniformSharpen = GLES20.glGetUniformLocation(program, "uSharpen")
        uniformFilter = GLES20.glGetUniformLocation(program, "uFilter")
        initialized = true
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val handle = GLES20.glCreateProgram()
        GLES20.glAttachShader(handle, vertex)
        GLES20.glAttachShader(handle, fragment)
        GLES20.glLinkProgram(handle)
        val status = IntArray(1)
        GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(handle)
            GLES20.glDeleteProgram(handle)
            throw IllegalStateException("could not link the look shader: $log")
        }
        return handle
    }

    private fun compile(type: Int, source: String): Int {
        val handle = GLES20.glCreateShader(type)
        GLES20.glShaderSource(handle, source)
        GLES20.glCompileShader(handle)
        val status = IntArray(1)
        GLES20.glGetShaderiv(handle, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(handle)
            GLES20.glDeleteShader(handle)
            throw IllegalStateException("could not compile the look shader: $log")
        }
        return handle
    }

    private companion object {
        const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform vec2 uTexel;
            uniform float uHdr;
            uniform float uNr;
            uniform float uSkin;
            uniform float uSharpen;
            uniform float uFilter;

            vec3 tap(vec2 offset) {
                return texture2D(uTexture, vTexCoord + offset * uTexel).rgb;
            }

            bool isSkin(vec3 c) {
                float r = c.r * 255.0;
                float g = c.g * 255.0;
                float b = c.b * 255.0;
                float spread = max(r, max(g, b)) - min(r, min(g, b));
                return r > 95.0 && g > 40.0 && b > 20.0 &&
                    spread > 15.0 && spread < 130.0 &&
                    abs(r - g) > 15.0 && r > g && r > b;
            }

            void main() {
                vec3 color = texture2D(uTexture, vTexCoord).rgb;

                // Clean-room color looks, matched to ImageProcessor's ordering.
                float luma = dot(color, vec3(0.299, 0.587, 0.114));
                if (uFilter > 0.5 && uFilter < 1.5) {
                    color = vec3(luma);
                } else if (uFilter > 1.5 && uFilter < 2.5) {
                    color *= vec3(1.08, 1.01, 0.90);
                } else if (uFilter > 2.5 && uFilter < 3.5) {
                    color *= vec3(0.91, 1.00, 1.09);
                } else if (uFilter > 3.5 && uFilter < 4.5) {
                    color = vec3(luma) + (color - vec3(luma)) * 1.28;
                } else if (uFilter > 4.5) {
                    color = vec3(
                        dot(color, vec3(0.393, 0.769, 0.189)),
                        dot(color, vec3(0.349, 0.686, 0.168)),
                        dot(color, vec3(0.272, 0.534, 0.131))
                    );
                }

                // Local average for the blend steps (mirrors the box blur).
                vec3 blur = color * 0.2;
                blur += tap(vec2(2.0, 0.0)) * 0.2;
                blur += tap(vec2(-2.0, 0.0)) * 0.2;
                blur += tap(vec2(0.0, 2.0)) * 0.2;
                blur += tap(vec2(0.0, -2.0)) * 0.2;

                // HDR tone curve (mirrors ImageProcessor.toneCurve).
                if (uHdr > 0.0) {
                    vec3 lifted = pow(color, vec3(1.0 / (1.0 + uHdr)));
                    color = mix(color, lifted, uHdr);
                }
                // Noise reduction (mirrors the 0.7 blend factor).
                color = mix(color, blur, uNr * 0.7);
                // Skin smoothing (mirrors the 0.8 blend factor and gate).
                if (uSkin > 0.0 && isSkin(color)) {
                    color = mix(color, blur, uSkin * 0.8);
                }
                // Unsharp mask (mirrors the 1.5 amount).
                if (uSharpen > 0.0) {
                    vec3 small = (
                        tap(vec2(1.0, 0.0)) + tap(vec2(-1.0, 0.0)) +
                        tap(vec2(0.0, 1.0)) + tap(vec2(0.0, -1.0))
                    ) * 0.25;
                    color += (color - small) * (uSharpen * 1.5);
                }

                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """
    }
}
