package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelMathTest {

    @Test
    fun uprightDeviceIsLevel() {
        assertEquals(0f, LevelMath.rollDegrees(0f, -9.81f), 0.01f)
        assertTrue(LevelMath.isLevel(LevelMath.rollDegrees(0f, -9.81f)))
    }

    @Test
    fun rollReflectsSidewaysTilt() {
        // 30 degrees: gx = g * sin(30), gy = -g * cos(30)
        val roll = LevelMath.rollDegrees(4.905f, -8.495f)
        assertEquals(30f, roll, 0.1f)
        assertFalse(LevelMath.isLevel(roll))
    }

    @Test
    fun toleranceDefinesLevel() {
        assertTrue(LevelMath.isLevel(0.6f))
        assertFalse(LevelMath.isLevel(0.8f))
        assertTrue(LevelMath.isLevel(0.9f, tolerance = 1f))
    }

    @Test
    fun skyAndFloorAttitudesAreNotPlausible() {
        assertTrue(LevelMath.isPlausibleShootingAttitude(2f))
        assertFalse(LevelMath.isPlausibleShootingAttitude(-9.6f))
    }
}
