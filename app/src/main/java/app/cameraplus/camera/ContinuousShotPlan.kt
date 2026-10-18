package app.cameraplus.camera

/**
 * Policy for the clean-room continuous shot behind the hold-shutter gesture.
 *
 * The original camera treats press-and-hold on the shutter as a burst when
 * its hold-shutter preference selects continuous shot, bounded to ten saved
 * frames by its storage layer. Camera+ mirrors the user-facing rule: while
 * the shutter is held, stills are queued one at a time as pipeline room and
 * mode allow, and the burst ends on release or at [MAX_SHOTS].
 */
object ContinuousShotPlan {
    /** Burst length cap; matches the original's burst count limit. */
    const val MAX_SHOTS = 10

    /**
     * How often the burst loop looks for queue room. The capture queue
     * itself paces the frames; this only bounds reaction latency.
     */
    const val TRIGGER_INTERVAL_MS = 120L

    /** True while a held burst may still trigger another capture. */
    fun shouldContinue(held: Boolean, shotsTaken: Int): Boolean =
        held && shotsTaken < MAX_SHOTS

    /** Whether the capture queue has room for another burst frame. */
    fun canTrigger(inFlight: Int, maxInFlight: Int): Boolean =
        inFlight < maxInFlight
}
