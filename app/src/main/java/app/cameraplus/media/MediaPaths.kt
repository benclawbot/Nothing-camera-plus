package app.cameraplus.media

/**
 * Where captures are written.
 *
 * Photos and videos go to the platform's standard camera folder,
 * `DCIM/Camera` — the same place the original app stores them, and where
 * other camera and gallery apps already look. Because that folder is shared,
 * the in-app gallery identifies its own rows by owner package instead of by
 * path (see [MediaLibrary]).
 */
object MediaPaths {

    /** MediaStore `RELATIVE_PATH` value, and the on-device folder. */
    const val RELATIVE_PATH = "DCIM/Camera"
}
