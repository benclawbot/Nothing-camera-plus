package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMasksTest {

    private val width = 64
    private val height = 48

    private fun plane(fill: Int): ByteArray = ByteArray(width * height) { fill.toByte() }

    @Test
    fun blownFrameMarksEveryCellAsHighlight() {
        val masks = OverlayMasks.of(plane(255), width, height)

        assertEquals(OverlayMasks.DEFAULT_COLUMNS, masks.columns)
        assertEquals(OverlayMasks.DEFAULT_ROWS, masks.rows)
        assertTrue(masks.highlights.all { it })
        assertFalse(masks.shadows.any { it })
        assertFalse(masks.peaking.any { it })
    }

    @Test
    fun blackFrameMarksEveryCellAsShadow() {
        val masks = OverlayMasks.of(plane(0), width, height)

        assertTrue(masks.shadows.all { it })
        assertFalse(masks.highlights.any { it })
    }

    @Test
    fun midGreyFrameIsBlank() {
        val masks = OverlayMasks.of(plane(128), width, height)

        assertTrue(masks.isBlank)
        assertEquals(0, masks.highlightSpans().size)
        assertEquals(0, masks.shadowSpans().size)
        assertEquals(0, masks.peakingSpans().size)
    }

    @Test
    fun aFewClippedPixelsDoNotLightACell() {
        // Two blown pixels inside a 256-pixel cell is well under the 20%
        // share the mask requires.
        val luma = plane(128)
        luma[0] = 255.toByte()
        luma[1] = 255.toByte()

        val masks = OverlayMasks.of(luma, width, height, columns = 4, rows = 3)

        assertFalse(masks.highlights[0])
        assertTrue(masks.isBlank)
    }

    @Test
    fun aClippedShareOfACellDoesLightIt() {
        // The whole top-left cell blown: 16x16 of 256 pixels is 100%.
        val luma = plane(128)
        val cellWidth = width / 4
        val cellHeight = height / 3
        for (y in 0 until cellHeight) {
            for (x in 0 until cellWidth) {
                luma[y * width + x] = 255.toByte()
            }
        }

        val masks = OverlayMasks.of(luma, width, height, columns = 4, rows = 3)

        assertTrue(masks.highlights[0])
        assertEquals(1, masks.highlightSpans().size)
    }

    @Test
    fun peakingLightsOnlyCellsWithAnEdge() {
        // Left half black, right half white: one vertical edge down the middle.
        val luma = ByteArray(width * height) { index ->
            if (index % width < width / 2) 0 else 255.toByte()
        }

        val masks = OverlayMasks.of(
            luma = luma,
            width = width,
            height = height,
            highlights = false,
            shadows = false,
            peaking = true,
        )

        assertTrue(masks.peaking.any { it })
        val middleColumn = masks.columns / 2 - 1
        assertTrue(masks.peakingSpans().any { it.firstColumn <= middleColumn && it.lastColumn >= middleColumn })
        // The flat halves must stay unmarked.
        assertFalse(masks.peaking[0])
        assertFalse(masks.peaking[masks.columns - 1])
    }

    @Test
    fun spansMergeAdjacentCellsPerRow() {
        val masks = OverlayMasks.of(plane(255), width, height)

        val spans = masks.highlightSpans()

        assertEquals(masks.rows, spans.size)
        assertTrue(spans.all { it.firstColumn == 0 && it.lastColumn == masks.columns - 1 })
    }

    @Test
    fun spansSplitOnGaps() {
        val luma = plane(128)
        // Blow the left third of the first row of cells only.
        val cellWidth = width / OverlayMasks.DEFAULT_COLUMNS
        val cellHeight = height / OverlayMasks.DEFAULT_ROWS
        for (y in 0 until cellHeight) {
            for (x in 0 until cellWidth) {
                luma[y * width + x] = 255.toByte()
            }
        }

        val spans = OverlayMasks.of(luma, width, height).highlightSpans()

        assertEquals(1, spans.size)
        assertEquals(0, spans.first().row)
        assertEquals(0, spans.first().firstColumn)
        assertEquals(0, spans.first().lastColumn)
    }

    @Test
    fun emptyDimensionsYieldTheEmptyMask() {
        assertEquals(OverlayMasks.EMPTY, OverlayMasks.of(ByteArray(0), 0, 0))
        assertTrue(OverlayMasks.EMPTY.isBlank)
        assertTrue(OverlayMasks.EMPTY.highlightSpans().isEmpty())
    }
}
