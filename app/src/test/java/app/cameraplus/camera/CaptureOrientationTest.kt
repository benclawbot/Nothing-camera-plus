package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pose + sensor orientation -> clockwise capture rotation. Reference device
 * numbers: back cameras report SENSOR_ORIENTATION 90, the front reports 270.
 */
class CaptureOrientationTest {

    private fun rotation(pose: Int, sensor: Int) =
        CaptureOrientation.captureRotationDegrees(pose, sensor)

    @Test
    fun poseReadingsRoundToRightAngles() {
        assertEquals(0, CaptureOrientation.roundPoseDegrees(0))
        assertEquals(0, CaptureOrientation.roundPoseDegrees(359))
        assertEquals(0, CaptureOrientation.roundPoseDegrees(44))
        assertEquals(90, CaptureOrientation.roundPoseDegrees(45))
        assertEquals(90, CaptureOrientation.roundPoseDegrees(100))
        assertEquals(180, CaptureOrientation.roundPoseDegrees(190))
        assertEquals(270, CaptureOrientation.roundPoseDegrees(250))
        assertEquals(0, CaptureOrientation.roundPoseDegrees(325))
        assertEquals(90, CaptureOrientation.roundPoseDegrees(450))
    }

    @Test
    fun backCameraPortraitNeedsAnEighthTurn() {
        assertEquals(90, rotation(0, 90))
    }

    @Test
    fun backCameraLandscapeIsAlreadyUpright() {
        assertEquals(0, rotation(270, 90))
        assertEquals(180, rotation(90, 90))
    }

    @Test
    fun backCameraUpsideDown() {
        assertEquals(270, rotation(180, 90))
    }

    @Test
    fun frontCameraPortraitNeedsThreeEighthTurns() {
        assertEquals(270, rotation(0, 270))
    }

    @Test
    fun frontCameraLandscapeGetsTheSensor270Flip() {
        assertEquals(0, rotation(270, 270))
        assertEquals(180, rotation(90, 270))
        // Upright poses never take the flip.
        assertEquals(90, rotation(180, 270))
    }

    @Test
    fun alignedSensorsKeepThePlainSum() {
        assertEquals(0, rotation(0, 0))
        assertEquals(90, rotation(90, 0))
        assertEquals(270, rotation(270, 0))
        assertEquals(180, rotation(180, 0))
    }

    @Test
    fun deliveredRotationSplitsThePipelineWork() {
        // HAL baked the pixels: nothing owed to the consumer, the pipeline
        // applied the whole sensor orientation.
        assertEquals(
            90,
            CaptureOrientation.pipelineAppliedRotationDegrees(90, 0),
        )
        // HAL left pixels raw and wrote the rotation into Exif: nothing was
        // baked, the metadata owes the whole rotation.
        assertEquals(
            0,
            CaptureOrientation.pipelineAppliedRotationDegrees(90, 90),
        )
        // Front camera, Exif-only behavior.
        assertEquals(
            0,
            CaptureOrientation.pipelineAppliedRotationDegrees(270, 270),
        )
    }

    @Test
    fun remainingRotationSubtractsWhatWasBaked() {
        // Reference device, back camera: portrait hold wants 90 and the HAL
        // baked 90 -> nothing left to do; landscape hold wants 180 -> 90 left.
        assertEquals(0, CaptureOrientation.remainingRotationDegrees(90, 90))
        assertEquals(90, CaptureOrientation.remainingRotationDegrees(180, 90))
        assertEquals(180, CaptureOrientation.remainingRotationDegrees(270, 90))
        assertEquals(270, CaptureOrientation.remainingRotationDegrees(0, 90))
    }

    @Test
    fun bakedAndExifOnlyPipelinesBothEndUpright() {
        // Whatever the HAL does (bake the rotation with an undefined tag, or
        // leave raw pixels with the rotation in Exif), applying the remaining
        // rotation to the delivered pixels must total the pose rotation.
        for (sensor in listOf(90, 270)) {
            for (pose in listOf(0, 90, 180, 270)) {
                val desired = rotation(pose, sensor)
                for (delivered in listOf(0, sensor)) {
                    val applied = CaptureOrientation.pipelineAppliedRotationDegrees(
                        sensor,
                        delivered,
                    )
                    val remaining = CaptureOrientation.remainingRotationDegrees(desired, applied)
                    assertEquals(
                        "sensor=$sensor pose=$pose delivered=$delivered",
                        desired % 360,
                        (applied + remaining) % 360,
                    )
                }
            }
        }
    }
}
