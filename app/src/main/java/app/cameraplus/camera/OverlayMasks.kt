package app.cameraplus.camera

import kotlin.math.abs

/**
 * Coarse per-cell maps of what the viewfinder overlays should highlight: blown
 * highlights (zebra stripes), crushed blacks and, when asked, in-focus edges
 * (focus peaking).
 *
 * The analysis frame is folded into a [columns] x [rows] grid, so a mask costs
 * a few kilobytes instead of a full-resolution bitmap and the overlay draws one
 * rectangle per run of cells ([highlightSpans] and friends). The maths is
 * dependency-free and unit tested off-device.
 */
data class OverlayMasks(
    val columns: Int,
    val rows: Int,
    /** Analysis frame the grid was folded from, for view-space mapping. */
    val frameWidth: Int,
    val frameHeight: Int,
    /** Cells whose clipped-white share passed [CLIPPED_CELL_FRACTION]. */
    val highlights: BooleanArray,
    /** Cells whose clipped-black share passed [CLIPPED_CELL_FRACTION]. */
    val shadows: BooleanArray,
    /** Cells holding an edge at least [PEAKING_GRADIENT] strong. */
    val peaking: BooleanArray,
) {
    /** One row-wise run of marked cells; the overlay draws one rect per run. */
    data class Span(val row: Int, val firstColumn: Int, val lastColumn: Int)

    fun highlightSpans(): List<Span> = spans(highlights)

    fun shadowSpans(): List<Span> = spans(shadows)

    fun peakingSpans(): List<Span> = spans(peaking)

    val isBlank: Boolean
        get() = !highlights.any { it } && !shadows.any { it } && !peaking.any { it }

    private fun spans(cells: BooleanArray): List<Span> {
        if (columns <= 0 || rows <= 0) return emptyList()
        val runs = ArrayList<Span>()
        for (row in 0 until rows) {
            var start = -1
            for (column in 0 until columns) {
                val marked = cells.getOrNull(row * columns + column) == true
                if (marked && start < 0) start = column
                if (!marked && start >= 0) {
                    runs += Span(row, start, column - 1)
                    start = -1
                }
            }
            if (start >= 0) runs += Span(row, start, columns - 1)
        }
        return runs
    }

    companion object {
        /** Grid across the analysis frame: about 4x4 frame pixels per cell. */
        const val DEFAULT_COLUMNS = 48
        const val DEFAULT_ROWS = 36

        /** Luma at or above this counts as blown white... */
        const val HIGHLIGHT_LUMA = 250

        /** ...and at or below this as crushed black. */
        const val SHADOW_LUMA = 6

        /** A cell lights up once this share of its pixels clips. */
        const val CLIPPED_CELL_FRACTION = 0.2f

        /** Luma step (|dx| + |dy|) a cell needs to count as an edge. */
        const val PEAKING_GRADIENT = 40

        val EMPTY = OverlayMasks(
            columns = 0,
            rows = 0,
            frameWidth = 0,
            frameHeight = 0,
            highlights = BooleanArray(0),
            shadows = BooleanArray(0),
            peaking = BooleanArray(0),
        )

        /**
         * Folds a [width] x [height] luma plane into an overlay mask. Only the
         * requested maps are filled, so a peaking-only session does not pay for
         * the clipping scans.
         */
        fun of(
            luma: ByteArray,
            width: Int,
            height: Int,
            columns: Int = DEFAULT_COLUMNS,
            rows: Int = DEFAULT_ROWS,
            highlights: Boolean = true,
            shadows: Boolean = true,
            peaking: Boolean = false,
        ): OverlayMasks {
            if (width <= 0 || height <= 0 || columns <= 0 || rows <= 0) return EMPTY
            val highlightCells = BooleanArray(columns * rows)
            val shadowCells = BooleanArray(columns * rows)
            val peakingCells = BooleanArray(columns * rows)

            for (row in 0 until rows) {
                val top = row * height / rows
                val bottom = ((row + 1) * height / rows).coerceIn(top + 1, height)
                for (column in 0 until columns) {
                    val left = column * width / columns
                    val right = ((column + 1) * width / columns).coerceIn(left + 1, width)
                    var white = 0
                    var black = 0
                    var gradient = 0
                    var samples = 0
                    for (y in top until bottom) {
                        val rowBase = y * width
                        for (x in left until right) {
                            val index = rowBase + x
                            if (index !in luma.indices) continue
                            val value = luma[index].toInt() and 0xFF
                            samples++
                            if (highlights && value >= HIGHLIGHT_LUMA) white++
                            if (shadows && value <= SHADOW_LUMA) black++
                            if (peaking) {
                                val before = if (x > 0) luma[index - 1].toInt() and 0xFF else value
                                val after = if (x < width - 1) luma[index + 1].toInt() and 0xFF else value
                                val above = if (y > 0) luma[index - width].toInt() and 0xFF else value
                                val below = if (y < height - 1) luma[index + width].toInt() and 0xFF else value
                                gradient = maxOf(gradient, abs(after - before) + abs(below - above))
                            }
                        }
                    }
                    if (samples == 0) continue
                    val cell = row * columns + column
                    val clippedShare = samples * CLIPPED_CELL_FRACTION
                    if (highlights && white >= clippedShare) highlightCells[cell] = true
                    if (shadows && black >= clippedShare) shadowCells[cell] = true
                    if (peaking && gradient >= PEAKING_GRADIENT) peakingCells[cell] = true
                }
            }
            return OverlayMasks(
                columns = columns,
                rows = rows,
                frameWidth = width,
                frameHeight = height,
                highlights = highlightCells,
                shadows = shadowCells,
                peaking = peakingCells,
            )
        }
    }
}
