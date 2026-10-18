package app.cameraplus.ui

import android.content.Intent

/**
 * Launch contract for camera shortcuts.
 *
 * A launcher shortcut (or any other app) can open Camera+ in a specific mode
 * by targeting [app.cameraplus.MainActivity] with [EXTRA_MODE] — the name of
 * a [CameraMode], e.g. `"PHOTO"` — plus the optional [EXTRA_FACING] (`"back"`
 * or `"front"`). The static shortcuts in `res/xml/shortcuts.xml` use the same
 * contract, and [ACTION] lets shortcut builders and automation target modes
 * the launcher itself does not display.
 *
 * Parsing is deliberately pure so the contract is unit-testable.
 */
object ModeShortcut {
    const val ACTION = "app.cameraplus.action.SHORTCUT_CAMERA"
    const val EXTRA_MODE = "app.cameraplus.extra.SHORTCUT_MODE"
    const val EXTRA_FACING = "app.cameraplus.extra.SHORTCUT_FACING"

    enum class Facing { BACK, FRONT }

    data class Selection(val mode: CameraMode, val facing: Facing?)

    /**
     * Parses shortcut extras. Returns null when no valid mode was requested —
     * a plain launcher tap carries no extras and must open the default state.
     */
    fun fromExtras(mode: String?, facing: String?): Selection? {
        val requested = mode?.trim()?.uppercase() ?: return null
        val parsedMode = CameraMode.entries.firstOrNull { it.name == requested } ?: return null
        val parsedFacing = when (facing?.trim()?.lowercase()) {
            "front" -> Facing.FRONT
            "back" -> Facing.BACK
            else -> null
        }
        return Selection(parsedMode, parsedFacing)
    }

    /** Reads the contract off an intent that may or may not carry it. */
    fun selectionFor(intent: Intent?): Selection? = intent?.let {
        fromExtras(it.getStringExtra(EXTRA_MODE), it.getStringExtra(EXTRA_FACING))
    }
}
