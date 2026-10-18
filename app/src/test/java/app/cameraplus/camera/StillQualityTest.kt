package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillQualityTest {

    @Test
    fun fastLeavesEveryCamera2StageAtThePlatformDefault() {
        assertEquals(StillQualityPlan.NONE, StillQualityPlan.forQuality(StillQuality.FAST))
        assertTrue(StillQualityPlan.forQuality(StillQuality.FAST).requested.isEmpty())
        assertFalse(StillQualityPlan.forQuality(StillQuality.FAST).blocksZeroShutterLag)
    }

    @Test
    fun balancedRequestsOnlyPreviewStreamTunedNoiseAndEdgeModes() {
        val plan = StillQualityPlan.forQuality(StillQuality.BALANCED)
        assertEquals(
            listOf(
                StillFeature.NOISE_REDUCTION to StillMode.ZERO_SHUTTER_LAG,
                StillFeature.EDGE to StillMode.ZERO_SHUTTER_LAG,
            ),
            plan.requested,
        )
        assertFalse(plan.blocksZeroShutterLag)
    }

    @Test
    fun highQualityRequestsFullReadoutProcessingAndLeavesZsl() {
        val plan = StillQualityPlan.forQuality(StillQuality.HIGH)
        assertEquals(6, plan.requested.size)
        assertTrue(plan.requested.all { it.second == StillMode.HIGH_QUALITY })
        assertTrue(plan.blocksZeroShutterLag)
    }

    @Test
    fun choicesWithoutAdvertisedModesDoNotOfferNoOpLevels() {
        assertEquals(
            listOf(StillQuality.FAST),
            StillQualitySupport.available(
                reportsZeroShutterLagModes = false,
                reportsHighQualityModes = false,
            ),
        )
        assertEquals(
            listOf(StillQuality.FAST, StillQuality.BALANCED),
            StillQualitySupport.available(
                reportsZeroShutterLagModes = true,
                reportsHighQualityModes = false,
            ),
        )
        assertEquals(
            StillQuality.entries.toList(),
            StillQualitySupport.available(
                reportsZeroShutterLagModes = true,
                reportsHighQualityModes = true,
            ),
        )
    }
}
