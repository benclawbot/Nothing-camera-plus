package app.cameraplus.settings

/**
 * A setting the user can pin to the viewfinder's top bar, so it stays one tap
 * away instead of living three taps deep in the settings sheet (a Nothing forum
 * request: "50MP quality should be on the top bar").
 *
 * [value] is filled in by the UI from the live setting, which keeps this enum
 * pure data.
 */
enum class PinnedSetting(val label: String) {
    NONE("OFF"),
    ASPECT("ASPECT"),
    SIZE("SIZE"),
    FLASH("FLASH"),
    LOOK("LOOK"),
    TIMER("TIMER");

    fun next(): PinnedSetting = entries[(ordinal + 1) % entries.size]
}

/** The entry after this one in its own enumeration, wrapping around. */
inline fun <reified T : Enum<T>> T.nextEntry(): T {
    val all = enumValues<T>()
    return all[(ordinal + 1) % all.size]
}
