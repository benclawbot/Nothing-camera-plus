package app.cameraplus.camera

/**
 * Pose-to-rotation math for still captures, shared by every save path (fast
 * JPEG, software pipeline, ARGB modes, RAW companion) so they all agree.
 *
 * The device pose comes from the orientation sensors: an
 * `OrientationEventListener` reading, rounded to the nearest right angle.
 * A raw camera buffer needs a clockwise rotation of
 * `pose + SENSOR_ORIENTATION` to stand upright when the phone is held that
 * way — with one caveat for cameras whose sensor reports 270, which need an
 * extra 180-degree flip in landscape poses. All values are clockwise degrees,
 * matching `Matrix.postRotate` and the Exif orientation tags.
 */
object CaptureOrientation {

    /** Rounds an orientation-sensor reading to the nearest right angle (0..359). */
    fun roundPoseDegrees(sensorDegrees: Int): Int {
        val normalized = ((sensorDegrees % 360) + 360) % 360
        return ((normalized + 45) / 90 * 90) % 360
    }

    /**
     * Clockwise rotation that turns the raw sensor buffer of a camera
     * upright for the pose the phone was held in at capture time.
     *
     * @param poseDegrees rounded device pose (0 = natural portrait)
     * @param sensorOrientation the camera's reported `SENSOR_ORIENTATION`
     */
    fun captureRotationDegrees(poseDegrees: Int, sensorOrientation: Int): Int {
        val pose = ((poseDegrees % 360) + 360) % 360
        val landscapeFlip = if (sensorOrientation == 270 && pose % 180 != 0) 180 else 0
        return (pose + sensorOrientation + landscapeFlip) % 360
    }

    /**
     * Rotation still needed when part of the uprighting was already applied
     * by the capture pipeline — CameraX pre-rotates in-memory frames to the
     * target rotation, so the software pass only adds the difference.
     */
    fun remainingRotationDegrees(desiredDegrees: Int, alreadyAppliedDegrees: Int): Int =
        (((desiredDegrees - alreadyAppliedDegrees) % 360) + 360) % 360

    /**
     * Rotation the capture pipeline already baked into a delivered frame, in
     * clockwise degrees. The app pins its capture target rotation to natural
     * portrait, so the pipeline turns raw sensor buffers toward portrait by
     * `SENSOR_ORIENTATION` degrees; the delivered rotation metadata reports
     * the part still missing, and the difference is what was baked.
     */
    fun pipelineAppliedRotationDegrees(
        sensorOrientation: Int,
        deliveredRotationDegrees: Int,
    ): Int = (((sensorOrientation - deliveredRotationDegrees) % 360) + 360) % 360
}
