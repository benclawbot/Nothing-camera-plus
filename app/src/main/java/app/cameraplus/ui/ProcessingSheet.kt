package app.cameraplus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cameraplus.settings.ProcessingSettings
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
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OffBlack,
        contentColor = White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                text = "PROCESSING",
                color = DimWhite,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(8.dp))
            val isNatural = settings == ProcessingSettings.NATURAL
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSettingsChange(ProcessingSettings.NATURAL) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "NATURAL",
                    color = if (isNatural) White else DimWhite,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (isNatural) "ACTIVE" else "RESET",
                    color = DimWhite,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            StrengthSlider(
                label = "SHARPENING",
                value = settings.sharpening,
                onChange = { onSettingsChange(settings.copy(sharpening = it)) },
            )
            StrengthSlider(
                label = "NOISE REDUCTION",
                value = settings.noiseReduction,
                onChange = { onSettingsChange(settings.copy(noiseReduction = it)) },
            )
            StrengthSlider(
                label = "HDR",
                value = settings.hdr,
                onChange = { onSettingsChange(settings.copy(hdr = it)) },
            )
            StrengthSlider(
                label = "SKIN SMOOTHING",
                value = settings.skinSmoothing,
                onChange = { onSettingsChange(settings.copy(skinSmoothing = it)) },
            )
        }
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
