package app.cameraplus.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks the self-timer's remaining seconds (Open Camera's voice countdown).
 *
 * The engine initialises asynchronously, so a request that arrives before it is
 * ready is dropped rather than queued — a countdown is only useful in real
 * time. Everything is guarded because a device without a speech engine must
 * only lose the voice, never the shot.
 */
class VoiceCountdown(context: Context) : TextToSpeech.OnInitListener {

    private var ready = false
    private val engine = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            runCatching { engine.language = Locale.getDefault() }
        }
    }

    /** Says [seconds]; [TextToSpeech.QUEUE_FLUSH] keeps it in step with the tick. */
    fun say(seconds: Int) {
        if (!ready || seconds <= 0) return
        runCatching {
            engine.speak(seconds.toString(), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    fun release() {
        runCatching {
            engine.stop()
            engine.shutdown()
        }
    }

    private companion object {
        const val UTTERANCE_ID = "cameraplus-countdown"
    }
}
