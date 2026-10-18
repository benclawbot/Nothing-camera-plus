package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Tests for the translation-only stitcher, its box-average resize helper and
 * the frame/progress plumbing around them.
 *
 * All synthetic: no `android.graphics` anywhere, so these run as plain JVM
 * unit tests. The scenes are rendered from a deterministic "scene column"
 * function and cut into frames the way a real pan would produce them, which
 * makes the expected shift known exactly rather than approximately.
 */
class PanoramaStitcherTest {

    // ---------------------------------------------------------------- scenes

    /**
     * A two-tone scene with non-repeating block lengths, so the horizontal
     * structure is unique within the search range and can be aligned. A
     * periodic scene deliberately would not be: see
     * [estimateShiftXOnAPeriodicSceneIsAmbiguous].
     */
    private fun toneAt(sceneX: Int): Int = if (blockIndex(sceneX) % 2 == 0) DARK else BRIGHT

    /**
     * Which tone block a scene column falls in. Blocks are laid out from the
     * origin with lengths 3..39 cycling every 37 blocks, so the layout only
     * repeats every 777 columns — far outside any search radius used here.
     */
    private fun blockIndex(sceneX: Int): Int {
        var start = 0
        var block = 0
        while (true) {
            val length = 3 + ((block * 7919) % 37)
            if (sceneX < start + length) return block
            start += length
            block++
        }
    }

    private fun render(sceneFromX: Int, width: Int, height: Int, exposure: Int = 0): IntArray {
        val out = IntArray(width * height)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                // The vertical shading keeps the frames from being identical
                // row-for-row without changing what the columns project to.
                val shade = 200 + (y * 55) / height
                val tone = ((toneAt(x + sceneFromX) * shade) / 255) + exposure
                out[index++] = argb(tone, tone - (tone / 12), tone - (tone / 6))
            }
        }
        return out
    }

    private fun renderWithBandOffsets(offsets: List<Int>, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val band = (y * offsets.size / height).coerceIn(0, offsets.lastIndex)
            for (x in 0 until width) {
                val tone = toneAt(x + offsets[band])
                out[y * width + x] = argb(tone, tone - tone / 12, tone - tone / 6)
            }
        }
        return out
    }

    /**
     * A smooth tonal gradient, with a little broadband texture on top.
     *
     * The texture is not decoration: a pure ramp correlates just as well at
     * every small shift, so the matcher would (correctly) call it ambiguous.
     * Real photographs have this texture, and so does this fixture — a
     * brightness ramp that still has a few levels of high-frequency detail,
     * which is what keeps the correlation peak sharp.
     */
    private fun renderGradient(sceneFromX: Int, width: Int, height: Int, exposure: Int = 0): IntArray {
        val out = IntArray(width * height)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val column = x + sceneFromX
                val tone = 20 + (column * 180) / 512 + grain(column) + exposure
                out[index++] = argb(tone, tone - 6, tone - 14)
            }
        }
        return out
    }

    /** Deterministic aperiodic grain in roughly -20..20. */
    private fun grain(x: Int): Int {
        var hash = x * 374761393 + 668265263 + 1442695040
        hash = (hash xor (hash ushr 13)) * 1274126177
        return ((hash xor (hash ushr 16)) and 0x3FFFFFFF) % 41 - 20
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun redAt(pixels: IntArray, x: Int, width: Int, y: Int): Int = (pixels[y * width + x] ushr 16) and 0xFF

    // ------------------------------------------------------- shift estimation

    @Test
    fun estimateShiftXRecoversAKnownTwoToneShift() {
        val width = 240
        val height = 16
        val shift = 41
        // Camera turned right: the content that was at scene column `x + 41`
        // now sits at `x`, so the frame belongs that far right on the canvas.
        val previous = render(0, width, height)
        val current = render(shift, width, height)

        assertEquals(shift, PanoramaStitcher.estimateShiftX(previous, current, width, height, width / 3))
    }

    @Test
    fun estimateShiftXUsesMedianOfVerticalBandsUnderParallax() {
        val width = 240
        val height = 90
        val previous = renderWithBandOffsets(listOf(0, 0, 0), width, height)
        val current = renderWithBandOffsets(listOf(12, 40, 64), width, height)

        assertEquals(40, PanoramaStitcher.estimateShiftX(previous, current, width, height, width / 3))
    }

    @Test
    fun estimateShiftXIsNegativeWhenTheCameraTurnedLeft() {
        val width = 240
        val height = 16
        val shift = -37
        val previous = render(0, width, height)
        val current = render(shift, width, height)

        assertEquals(shift, PanoramaStitcher.estimateShiftX(previous, current, width, height, width / 3))
    }

    @Test
    fun estimateShiftXIsWithinOnePixelOfAKnownShift() {
        val width = 320
        val height = 12
        // All inside the default width/3 search radius.
        for (shift in listOf(-91, -23, 64, 94)) {
            val previous = render(0, width, height)
            val current = render(shift, width, height)
            val measured = PanoramaStitcher.estimateShiftX(previous, current, width, height, width / 3)
            assertTrue("shift $shift measured as $measured", abs(measured - shift) <= 1)
        }
    }

    @Test
    fun estimateShiftXNeverReportsAShiftOutsideTheSearchRadius() {
        val width = 200
        val height = 10
        // Whatever the scene and however far the pan went, the answer must
        // stay inside the radius the caller allowed.
        for (maxShift in listOf(5, 20, 40, 60)) {
            for (sceneShift in listOf(-70, -31, 9, 44, 90)) {
                val previous = render(0, width, height)
                val current = render(sceneShift, width, height)
                val measured = PanoramaStitcher.estimateShiftX(previous, current, width, height, maxShift)
                assertTrue("radius $maxShift, shift $sceneShift, measured $measured", abs(measured) <= maxShift)
            }
        }
    }

    @Test
    fun estimateShiftXGivesUpWhenTheTrueShiftIsFarOutsideTheSearchRadius() {
        val width = 200
        val height = 10
        // 120 px of pan in a 40 px window: nothing in range correlates, and
        // guessing a boundary shift would be worse than admitting defeat.
        val previous = render(0, width, height)
        val current = render(120, width, height)

        assertEquals(0, PanoramaStitcher.estimateShiftX(previous, current, width, height, maxShift = 40))
    }

    @Test
    fun estimateShiftXOfIdenticalFramesIsZero() {
        val width = 160
        val height = 12
        val frame = render(0, width, height)

        assertEquals(0, PanoramaStitcher.estimateShiftX(frame, frame, width, height, width / 3))
    }

    @Test
    fun estimateShiftXOnAllBlackFramesIsZero() {
        val width = 128
        val height = 8
        val black = IntArray(width * height)

        assertEquals(0, PanoramaStitcher.estimateShiftX(black, black, width, height, width / 3))
    }

    @Test
    fun estimateShiftXOnAFlatFrameIsZero() {
        val width = 128
        val height = 8
        val flat = IntArray(width * height) { argb(90, 95, 100) }

        assertEquals(0, PanoramaStitcher.estimateShiftX(flat, flat, width, height, width / 3))
    }

    @Test
    fun estimateShiftXOnHorizontalBandsOnlyIsZero() {
        // Strong vertical structure, but every column projects to the same
        // luminance: nothing to align horizontally.
        val width = 128
        val height = 16
        val bands = IntArray(width * height) { index ->
            val y = index / width
            val tone = if (y % 2 == 0) 20 else 240
            argb(tone, tone, tone)
        }

        assertEquals(0, PanoramaStitcher.estimateShiftX(bands, bands, width, height, width / 3))
    }

    @Test
    fun estimateShiftXOnAPeriodicSceneIsAmbiguous() {
        val width = 200
        val height = 8
        val previous = striped(0, width, height, period = 20)
        val current = striped(9, width, height, period = 20)

        // Every shift that is a whole period away correlates just as well as
        // the true one, so the honest answer is "no shift I can defend".
        assertEquals(0, PanoramaStitcher.estimateShiftX(previous, current, width, height, width / 3))
    }

    @Test
    fun estimateShiftXOnUnrelatedScenesIsZero() {
        val width = 160
        val height = 10
        val previous = striped(0, width, height, period = 7)
        val current = striped(3, width, height, period = 7)

        assertEquals(0, PanoramaStitcher.estimateShiftX(previous, current, width, height, width / 3))
    }

    @Test
    fun estimateShiftXWithNoSearchRoomIsZero() {
        val width = 160
        val height = 8
        val previous = render(0, width, height)
        val current = render(11, width, height)

        assertEquals(0, PanoramaStitcher.estimateShiftX(previous, current, width, height, maxShift = 0))
    }

    @Test
    fun estimateShiftXRejectsImpossibleGeometry() {
        val width = 16
        val height = 8
        try {
            PanoramaStitcher.estimateShiftX(IntArray(4), IntArray(4), width, height, 4)
            throw AssertionError("expected the undersized buffer to be rejected")
        } catch (expected: IllegalArgumentException) {
            // Caller bug, not scene content.
        }
    }

    private fun striped(fromSceneX: Int, width: Int, height: Int, period: Int): IntArray {
        val out = IntArray(width * height)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val tone = if (((x + fromSceneX) / period) % 2 == 0) 30 else 225
                out[index++] = argb(tone, tone, tone)
            }
        }
        return out
    }

    // -------------------------------------------------------------- stitching

    @Test
    fun stitchOfTwoShiftedGradientsIsTheExpectedSizeWithNoHardSeam() {
        val width = 160
        val height = 12
        val shift = 48
        val first = renderGradient(0, width, height)
        val second = renderGradient(shift, width, height, exposure = 8)

        val result = requireNotNull(PanoramaStitcher.stitch(listOf(first, second), width, height)) {
            "two overlapping frames must stitch"
        }
        assertEquals(width + shift, result.width)
        assertEquals(height, result.height)
        assertEquals(2, result.usedFrames)
        assertEquals(0, result.rejectedFrames)

        val row = height / 2
        // Without feathering, frame one would own everything up to its right
        // edge and frame two everything past it, so its +8 exposure would
        // arrive as a single-column step of 8 levels there.
        // Compare the same scene column on either side of the seam. The
        // adjacent scene column at the outer edge also contains the fixture's
        // per-column grain, which can hide the deliberately added exposure
        // offset by coincidence.
        val unblendedJump = abs(redAt(first, shift, width, row) - redAt(second, 0, width, row))
        assertTrue("fixture is wrong: no exposure seam to hide", unblendedJump >= 6)

        // Every column of the overlap must sit exactly on the linear ramp
        // between the two frames' values. A cut anywhere in here — feather
        // missing, weight normalised wrongly, the ramp the wrong way round —
        // puts at least one column off by more than the rounding below.
        val seam = width - shift
        for (x in 0 until seam) {
            val canvasX = shift + x
            val old = redAt(first, canvasX, width, row)
            val new = redAt(second, canvasX - shift, width, row)
            val fade = (x + 1f) / (seam + 1f)
            val expected = old + (new - old) * fade
            val actual = redAt(result.pixels, canvasX, result.width, row)
            assertTrue(
                "overlap column $canvasX: expected ~${expected.toInt()}, got $actual",
                abs(actual - expected) <= 1f,
            )
        }
    }

    @Test
    fun stitchCopiesTheUncoveredEndsVerbatim() {
        val width = 128
        val height = 8
        val shift = 32
        val first = render(0, width, height)
        val second = render(shift, width, height)

        val result = requireNotNull(PanoramaStitcher.stitch(listOf(first, second), width, height))

        for (x in 0 until shift) {
            assertEquals("column $x", redAt(first, x, width, 2), redAt(result.pixels, x, result.width, 2))
        }
        for (x in width until result.width) {
            assertEquals("column $x", redAt(second, x - shift, width, 6), redAt(result.pixels, x, result.width, 6))
        }
    }

    @Test
    fun stitchOfThreeFramesAccumulatesTheCanvasWidth() {
        val width = 128
        val height = 10
        val shift = 36
        val frames = listOf(
            render(0, width, height),
            render(shift, width, height),
            render(2 * shift, width, height),
        )

        val result = requireNotNull(PanoramaStitcher.stitch(frames, width, height))

        assertEquals(width + 2 * shift, result.width)
        assertEquals(3, result.usedFrames)
        assertEquals(0, result.rejectedFrames)
    }

    @Test
    fun stitchCountsADuplicateFrameAsRejectedAndKeepsGoing() {
        val width = 128
        val height = 10
        val shift = 36
        val first = render(0, width, height)
        val frames = listOf(first, first, render(shift, width, height))

        val result = requireNotNull(PanoramaStitcher.stitch(frames, width, height))

        assertEquals(2, result.usedFrames)
        assertEquals(1, result.rejectedFrames)
        assertEquals(width + shift, result.width)
    }

    @Test
    fun stitchRejectsAFrameThatSwingsBackTheOtherWay() {
        val width = 160
        val height = 10
        val shift = 44
        // First two frames sweep right; the third steps back inside the
        // canvas already covered, which would carve a hole in the panorama.
        val frames = listOf(
            render(0, width, height),
            render(shift, width, height),
            render(shift / 2, width, height),
        )

        val result = requireNotNull(PanoramaStitcher.stitch(frames, width, height))

        assertEquals(2, result.usedFrames)
        assertEquals(1, result.rejectedFrames)
        assertEquals(width + shift, result.width)
    }

    @Test
    fun stitchRejectsAnUndersizedFrameInsteadOfThrowing() {
        val width = 96
        val height = 8
        val frames = listOf(render(0, width, height), IntArray(4), render(30, width, height))

        val result = requireNotNull(PanoramaStitcher.stitch(frames, width, height))

        assertEquals(2, result.usedFrames)
        assertEquals(1, result.rejectedFrames)
    }

    @Test
    fun stitchWithASingleFrameReturnsNull() {
        val width = 64
        val height = 8
        assertNull(PanoramaStitcher.stitch(listOf(render(0, width, height)), width, height))
        assertNull(PanoramaStitcher.stitch(emptyList(), width, height))
    }

    @Test
    fun stitchOfAllBlackFramesReturnsNull() {
        val width = 64
        val height = 8
        val black = IntArray(width * height)

        assertNull(PanoramaStitcher.stitch(listOf(black, black.copyOf()), width, height))
    }

    @Test
    fun stitchWithOneBlackFrameReturnsNullBecauseOnlyOneFrameIsUsable() {
        val width = 128
        val height = 10
        val frames = listOf(render(0, width, height), IntArray(width * height))

        assertNull(PanoramaStitcher.stitch(frames, width, height))
    }

    @Test
    fun stitchReturnsNullWhenTheOverlapIsBelowTheMinimum() {
        val width = 160
        val height = 10
        val shift = 145 // leaves 15 columns of overlap: under 10% of the width
        val frames = listOf(render(0, width, height), render(shift, width, height))

        assertNull(
            "a 9% overlap is a torn canvas, not a panorama",
            PanoramaStitcher.stitch(frames, width, height, maxShift = width),
        )
    }

    @Test
    fun stitchOfTwoIdenticalFramesHasNothingToStitch() {
        val width = 128
        val height = 8
        val frame = render(0, width, height)

        assertNull(PanoramaStitcher.stitch(listOf(frame, frame.copyOf()), width, height))
    }

    // ---------------------------------------------- PanoramaCapture plumbing

    // PanoramaCapture lives in the Android-glue file, but its frame buffer,
    // progress model and downscale are pure; they are exercised here because
    // these are the panorama test files and no android.graphics call is made.

    @Test
    fun captureCountsFramesAndGrowsTheEstimatedWidth() {
        val capture = PanoramaCapture(maxFrames = 6, maxDimension = 400)
        val width = 200
        val height = 100

        val afterFirst = capture.addFrame(render(0, width, height), width, height)
        assertEquals(1, afterFirst.acceptedFrames)
        assertEquals(0, afterFirst.rejectedFrames)
        assertEquals(200, afterFirst.estimatedWidth)
        assertTrue(!afterFirst.isFull)

        val afterSecond = capture.addFrame(render(90, width, height), width, height)
        assertEquals(2, afterSecond.acceptedFrames)
        assertEquals(55, afterSecond.overlapPercent)
        assertTrue("expected the canvas to grow by the measured shift, got ${afterSecond.estimatedWidth}",
            afterSecond.estimatedWidth > 200)
        assertTrue(afterSecond.progressFraction > 0f)
    }

    @Test
    fun liveCaptureRejectsWrongDirectionAndNoMovementImmediately() {
        val capture = PanoramaCapture(maxDimension = 300)
        val width = 200
        val height = 100
        capture.addFrame(render(0, width, height), width, height, Direction.RIGHT.canvasShiftSign)

        val noMovement = capture.addFrame(render(0, width, height), width, height, Direction.RIGHT.canvasShiftSign)
        assertEquals(1, noMovement.acceptedFrames)
        assertEquals(PanoramaCapture.Rejection.NO_MOVEMENT, noMovement.lastRejection)

        val wrongDirection = capture.addFrame(render(-90, width, height), width, height, Direction.RIGHT.canvasShiftSign)
        assertEquals(1, wrongDirection.acceptedFrames)
        assertEquals(PanoramaCapture.Rejection.WRONG_DIRECTION, wrongDirection.lastRejection)

        val rightDirection = capture.addFrame(render(90, width, height), width, height, Direction.RIGHT.canvasShiftSign)
        assertEquals(2, rightDirection.acceptedFrames)
        assertEquals(Direction.RIGHT, rightDirection.direction)
        assertTrue(rightDirection.estimatedWidth > width)
    }

    @Test
    fun liveCaptureRejectsKeyframesThatWouldLeaveTooLittleOverlap() {
        val capture = PanoramaCapture(maxDimension = 300)
        val width = 200
        val height = 100
        capture.addFrame(render(0, width, height), width, height, Direction.RIGHT.canvasShiftSign)

        val tooFast = capture.addFrame(
            render(130, width, height),
            width,
            height,
            expectedDirection = Direction.RIGHT.canvasShiftSign,
        )

        assertEquals(1, tooFast.acceptedFrames)
        assertEquals(PanoramaCapture.Rejection.TOO_FAST, tooFast.lastRejection)
        assertEquals(1, tooFast.rejectedFrames)
    }

    @Test
    fun progressivePreviewUsesMeasuredCanvasGeometry() {
        val capture = PanoramaCapture(maxDimension = 400)
        val width = 160
        val height = 80
        capture.addFrame(render(0, width, height), width, height)
        val progress = capture.addFrame(render(64, width, height), width, height)

        val preview = requireNotNull(capture.previewPixels(400))

        assertEquals(progress.estimatedWidth, preview.second)
        assertEquals(height, preview.third)
        assertEquals(progress.estimatedWidth * height, preview.first.size)
    }

    @Test
    fun captureRejectsFramesOnceTheSweepIsFull() {
        val capture = PanoramaCapture(maxFrames = 2, maxDimension = 200)
        val width = 100
        val height = 50

        capture.addFrame(render(0, width, height), width, height)
        capture.addFrame(render(40, width, height), width, height)
        val full = capture.addFrame(render(80, width, height), width, height)

        assertEquals(2, full.acceptedFrames)
        assertEquals(1, full.rejectedFrames)
        assertTrue(full.isFull)
    }

    @Test
    fun captureDownscalesStoredFramesToTheWorkingResolution() {
        val width = 400
        val height = 300
        val capture = PanoramaCapture(maxDimension = 200)

        val progress = capture.addFrame(render(0, width, height), width, height)

        assertEquals(200, progress.estimatedWidth)
        assertEquals(150, progress.estimatedHeight)
        assertEquals(200, capture.workingResolutionWidth)
        assertEquals(150, capture.workingResolutionHeight)
    }

    @Test
    fun captureRejectsAMalformedFrameWithoutThrowing() {
        val capture = PanoramaCapture(maxDimension = 200)
        val width = 100
        val height = 50

        val truncated = capture.addFrame(IntArray(10), width, height)
        assertEquals(0, truncated.acceptedFrames)
        assertEquals(1, truncated.rejectedFrames)

        // Rejections are counted for the whole sweep, not just this frame.
        val empty = capture.addFrame(IntArray(0), 0, 0)
        assertEquals(0, empty.acceptedFrames)
        assertEquals(2, empty.rejectedFrames)
    }

    @Test
    fun captureRejectsAFrameFromADifferentCaptureSize() {
        val capture = PanoramaCapture(maxDimension = 400)
        capture.addFrame(render(0, 200, 100), 200, 100)

        val progress = capture.addFrame(render(0, 100, 100), 100, 100)

        assertEquals(1, progress.acceptedFrames)
        assertEquals(1, progress.rejectedFrames)
    }

    @Test
    fun captureFinishStitchesWhatWasCollectedAndClearsTheSweep() {
        val width = 160
        val height = 80
        val shift = 64
        val capture = PanoramaCapture(maxDimension = 400)
        capture.addFrame(render(0, width, height), width, height)
        capture.addFrame(render(shift, width, height), width, height)

        val result = requireNotNull(capture.finish())

        assertEquals(width + shift, result.width)
        assertEquals(height, result.height)
        assertEquals(2, result.usedFrames)
        assertEquals(0, capture.frameCount)
        assertNull(capture.finish())
    }

    @Test
    fun captureFinishWithASingleFrameReturnsNullAndClearsState() {
        val capture = PanoramaCapture(maxDimension = 200)
        capture.addFrame(render(0, 100, 50), 100, 50)

        assertNull(capture.finish())
        assertEquals(0, capture.frameCount)
        assertEquals(0, capture.workingResolutionWidth)
    }

    @Test
    fun captureResetDropsEverything() {
        val capture = PanoramaCapture(maxDimension = 200)
        capture.addFrame(render(0, 100, 50), 100, 50)
        capture.addFrame(IntArray(3), 100, 50)

        capture.reset()

        val progress = capture.progress()
        assertEquals(0, progress.acceptedFrames)
        assertEquals(0, progress.rejectedFrames)
        assertEquals(0f, progress.progressFraction, 1e-6f)
    }

    // --------------------------------------------------------------- downscale

    @Test
    fun downscaleAveragesEachSourceBox() {
        // 4x4 of four flat quadrants -> 2x2 of the quadrant means.
        val pixels = IntArray(16)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val tone = when {
                    x < 2 && y < 2 -> 20
                    x >= 2 && y < 2 -> 100
                    x < 2 -> 180
                    else -> 220
                }
                pixels[y * 4 + x] = argb(tone, tone, tone)
            }
        }

        val (out, width, height) = downscale(pixels, 4, 4, 2)

        assertEquals(2, width)
        assertEquals(2, height)
        // Flat quadrants: each output pixel is the mean of its 2x2 source box.
        assertEquals(20, (out[0] ushr 16) and 0xFF)
        assertEquals(100, (out[1] ushr 16) and 0xFF)
        assertEquals(180, (out[2] ushr 16) and 0xFF)
        assertEquals(220, (out[3] ushr 16) and 0xFF)
    }

    @Test
    fun downscaleLeavesSmallImagesAlone() {
        val pixels = IntArray(24) { argb(10, 20, 30) }

        val (out, width, height) = downscale(pixels, 6, 4, 8)

        assertEquals(6, width)
        assertEquals(4, height)
        assertTrue("no copy when nothing is scaled", out === pixels)
    }

    @Test
    fun downscaleKeepsTheAspectRatioAndTheAverageLevel() {
        val width = 400
        val height = 300
        val pixels = IntArray(width * height) { index ->
            val tone = (index / width) * 255 / height
            argb(tone, tone, tone)
        }
        val sourceMean = (0 until height).sumOf { y -> (y * 255 / height) }.toDouble() / height

        val (out, outWidth, outHeight) = downscale(pixels, width, height, 100)

        assertEquals(100, outWidth)
        assertEquals(75, outHeight)
        val outMean = (0 until outHeight).sumOf { y -> (out[y * outWidth] ushr 16) and 0xFF }.toDouble() / outHeight
        assertTrue("mean drifted from $sourceMean to $outMean", abs(outMean - sourceMean) <= 2)
        assertTrue("output must be opaque", out.all { (it ushr 24) and 0xFF == 0xFF })
    }

    @Test
    fun downscaleProducesOpaquePixelsEvenFromTransparentInput() {
        val pixels = IntArray(16) { 0x00FFFFFF }

        val (out, width, height) = downscale(pixels, 4, 4, 2)

        assertEquals(4, out.size)
        assertTrue(out.all { (it ushr 24) and 0xFF == 0xFF })
        assertEquals(255, (out[0] ushr 16) and 0xFF)
    }

    @Test
    fun downscaleRejectsImpossibleGeometry() {
        try {
            downscale(IntArray(4), 16, 16, 8)
            throw AssertionError("expected the undersized buffer to be rejected")
        } catch (expected: IllegalArgumentException) {
            // Caller bug.
        }
    }

    companion object {
        private const val DARK = 40
        private const val BRIGHT = 215
    }
}
