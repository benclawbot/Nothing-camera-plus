package app.cameraplus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cameraplus.settings.ProcessingSettings
import app.cameraplus.settings.PhotoFilter
import app.cameraplus.settings.WatermarkOptions
import app.cameraplus.settings.CapturePreferences
import app.cameraplus.camera.FlashChoice
import app.cameraplus.camera.PhotoAspectRatio
import app.cameraplus.camera.PhotoResolution
import app.cameraplus.camera.SelfTimer
import app.cameraplus.camera.VideoFrameRate
import app.cameraplus.camera.VideoQuality
import app.cameraplus.ui.theme.DimWhite
import app.cameraplus.ui.theme.FaintWhite
import app.cameraplus.ui.theme.OffBlack
import app.cameraplus.ui.theme.White
import kotlin.math.roundToInt

/**
 * Bottom sheet for the processing intent (M3): a one-tap NATURAL reset plus
 * per-step strengths. Persisting is handled by the caller so the same sheet
 * can be reused when the preview pipeline lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingSheet(
    settings: ProcessingSettings,
    onSettingsChange: (ProcessingSettings) -> Unit,
    capturePreferences: CapturePreferences,
    supportedVideoFrameRates: List<VideoFrameRate>,
    maximumPhotoResolutionSupported: Boolean,
    screenFlashSupported: Boolean,
    hdrExtensionAvailable: Boolean,
    onCapturePreferencesChange: (CapturePreferences) -> Unit,
    watermark: WatermarkOptions,
    onWatermarkChange: (WatermarkOptions) -> Unit,
    geotagEnabled: Boolean,
    onGeotagToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    var section by remember { mutableStateOf(SettingsSection.PHOTO) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.94f),
        containerColor = OffBlack,
        contentColor = White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "‹",
                    color = White,
                    fontSize = 28.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(end = 14.dp),
                )
                Text(
                    text = "CAMERA SETTINGS",
                    color = White,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 3.sp,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SettingsSection.entries.forEach { tab ->
                    Text(
                        text = tab.label,
                        color = if (section == tab) White else DimWhite,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.5.sp,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable { section = tab }.padding(vertical = 12.dp),
                    )
                }
            }
            when (section) {
                SettingsSection.PHOTO -> {
                    SettingsChoices("ASPECT RATIO", capturePreferences.photoAspectRatio.label,
                        PhotoAspectRatio.entries.map { it.label }) { value ->
                        PhotoAspectRatio.entries.firstOrNull { it.label == value }?.let {
                            onCapturePreferencesChange(capturePreferences.copy(photoAspectRatio = it))
                        }
                    }
                    SettingsChoices("PHOTO SIZE", capturePreferences.photoResolution.label,
                        buildList {
                            add(PhotoResolution.STANDARD.label)
                            if (maximumPhotoResolutionSupported) add(PhotoResolution.MAXIMUM.label)
                        }) { value ->
                        PhotoResolution.entries.firstOrNull { it.label == value }?.let {
                            onCapturePreferencesChange(capturePreferences.copy(photoResolution = it))
                        }
                    }
                    SettingsChoices("FLASH", capturePreferences.flashChoice.label,
                        FlashChoice.entries.filter { screenFlashSupported || it != FlashChoice.SCREEN }
                            .map { it.label }) { value ->
                        FlashChoice.entries.firstOrNull { it.label == value }?.let {
                            onCapturePreferencesChange(capturePreferences.copy(flashChoice = it))
                        }
                    }
                    SettingsChoices("SELF TIMER", capturePreferences.selfTimer.label,
                        SelfTimer.entries.map { it.label }) { value ->
                        SelfTimer.entries.firstOrNull { it.label == value }?.let {
                            onCapturePreferencesChange(capturePreferences.copy(selfTimer = it))
                        }
                    }
                    SettingsToggle("HARDWARE HDR EXTENSION", capturePreferences.photoHdrEnabled, hdrExtensionAvailable) {
                        onCapturePreferencesChange(capturePreferences.copy(photoHdrEnabled = !capturePreferences.photoHdrEnabled))
                    }
                    SettingsToggle("MIRROR FRONT CAMERA", capturePreferences.mirrorCapture) {
                        onCapturePreferencesChange(capturePreferences.copy(mirrorCapture = !capturePreferences.mirrorCapture))
                    }
                    SettingsToggle("SHUTTER SOUND", capturePreferences.shutterSoundEnabled) {
                        onCapturePreferencesChange(capturePreferences.copy(shutterSoundEnabled = !capturePreferences.shutterSoundEnabled))
                    }
                    SettingsToggle("VOLUME-KEY SHUTTER", capturePreferences.volumeKeyShutter) {
                        onCapturePreferencesChange(capturePreferences.copy(volumeKeyShutter = !capturePreferences.volumeKeyShutter))
                    }
                    SettingsToggle("TOUCH SHUTTER", capturePreferences.touchShutter) {
                        onCapturePreferencesChange(capturePreferences.copy(touchShutter = !capturePreferences.touchShutter))
                    }
                }

                SettingsSection.VIDEO -> {
                    SettingsChoices("VIDEO QUALITY", capturePreferences.videoQuality.label,
                        VideoQuality.entries.map { it.label }) { value ->
                        VideoQuality.entries.firstOrNull { it.label == value }?.let {
                            onCapturePreferencesChange(capturePreferences.copy(videoQuality = it))
                        }
                    }
                    SettingsChoices("VIDEO FRAME RATE", capturePreferences.videoFrameRate.label,
                        supportedVideoFrameRates.map { it.label }) { value ->
                        supportedVideoFrameRates.firstOrNull { it.label == value }?.let {
                            onCapturePreferencesChange(capturePreferences.copy(videoFrameRate = it))
                        }
                    }
                    Text(
                        text = "Resolution/frame-rate combinations depend on the camera and encoder.",
                        color = White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                SettingsSection.IMAGE -> {
                    SettingsToggle("NATURAL RESET", settings == ProcessingSettings.NATURAL) {
                        onSettingsChange(ProcessingSettings.NATURAL)
                    }
                    StrengthSlider("SHARPENING", settings.sharpening) {
                        onSettingsChange(settings.copy(sharpening = it))
                    }
                    StrengthSlider("NOISE REDUCTION", settings.noiseReduction) {
                        onSettingsChange(settings.copy(noiseReduction = it))
                    }
                    StrengthSlider("SOFTWARE HDR", settings.hdr) {
                        onSettingsChange(settings.copy(hdr = it))
                    }
                    StrengthSlider("SKIN SMOOTHING", settings.skinSmoothing) {
                        onSettingsChange(settings.copy(skinSmoothing = it))
                    }
                    SettingsChoices("CLEAN-ROOM COLOR LOOK", settings.filter.label,
                        PhotoFilter.entries.map { it.label }) { value ->
                        PhotoFilter.entries.firstOrNull { it.label == value }?.let {
                            onSettingsChange(settings.copy(filter = it))
                        }
                    }
                }

                SettingsSection.MORE -> {
                    SettingsToggle("GEOTAG LOCATION", geotagEnabled, onClick = onGeotagToggle)
                    SettingsToggle("CAMERA+ WATERMARK", watermark.enabled) {
                        onWatermarkChange(watermark.copy(enabled = !watermark.enabled))
                    }
                    if (watermark.enabled) {
                        SettingsToggle("DATE", watermark.includeDate) {
                            onWatermarkChange(watermark.copy(includeDate = !watermark.includeDate))
                        }
                        SettingsToggle("TIME", watermark.includeTime) {
                            onWatermarkChange(watermark.copy(includeTime = !watermark.includeTime))
                        }
                        SettingsToggle("LOCATION TEXT", watermark.includeLocation) {
                            onWatermarkChange(watermark.copy(includeLocation = !watermark.includeLocation))
                        }
                        OutlinedTextField(
                            value = watermark.authorName,
                            onValueChange = { onWatermarkChange(watermark.copy(authorName = it.take(32))) },
                            label = { Text("AUTHOR NAME") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = "Software stamps are clean-room generated; files use Camera+ media folders.",
                        color = White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = "RESTORE DEFAULTS",
                color = White,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clickable {
                        onCapturePreferencesChange(CapturePreferences())
                        onSettingsChange(ProcessingSettings.NATURAL)
                        onWatermarkChange(WatermarkOptions())
                        if (geotagEnabled) onGeotagToggle()
                    }
                    .padding(vertical = 12.dp),
            )
            Text(
                text = "Resets capture settings, look and watermark to their defaults.",
                color = DimWhite,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

private enum class SettingsSection(val label: String) {
    PHOTO("PHOTO"),
    VIDEO("VIDEO"),
    IMAGE("IMAGE"),
    MORE("MORE"),
}

@Composable
private fun SettingsChoices(
    title: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = title,
            color = DimWhite,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                Text(
                    text = option,
                    color = if (option == selected) White else DimWhite,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clickable { onSelect(option) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = White,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (active) "ON" else "OFF",
            color = White,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun WatermarkRow(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (active) White else DimWhite,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (active) "ON" else "OFF",
            color = White,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun StrengthSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = DimWhite,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${(value * 100).roundToInt()}%",
                color = White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = White,
                activeTrackColor = White,
                inactiveTrackColor = FaintWhite,
            ),
        )
    }
}
