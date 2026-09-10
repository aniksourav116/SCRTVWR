package srv.scrtvwr.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import srv.scrtvwr.R
import kotlin.math.roundToInt

/**
 * Invisible vertical sliders occupying the outer [SIDE_GESTURE_WIDTH_FRACTION] of the player:
 * screen brightness on the left edge, media volume on the right, each covering the whole range
 * over a single top-to-bottom drag.
 *
 * Call this from inside the player gesture layer rather than on top of it. Pointer events reach a
 * child and its ancestors alike, so taps and horizontal seeks still land on the handler behind
 * these zones; only vertical drags are consumed, which is also what keeps an adjustment from
 * flicking the pager over to the next video.
 */
@Composable
fun BoxScope.VideoSideGestures(enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var adjustment by remember { mutableStateOf<ScreenAdjustment?>(null) }
    var adjusting by remember { mutableStateOf(false) }

    // Leave the readout up briefly after the finger lifts so the value it settled on is readable.
    LaunchedEffect(adjusting, adjustment) {
        if (!adjusting && adjustment != null) {
            delay(ADJUSTMENT_LINGER_MILLIS)
            adjustment = null
        }
    }

    if (enabled) {
        SideGestureZone(
            alignment = Alignment.CenterStart,
            currentLevel = { activity.brightnessLevel() },
            onLevelChange = { level ->
                activity?.applyBrightness(level)
                adjustment = ScreenAdjustment(ScreenAdjustmentType.Brightness, level)
            },
            onAdjustingChange = { adjusting = it },
        )
        SideGestureZone(
            alignment = Alignment.CenterEnd,
            currentLevel = { audioManager?.volumeLevel() ?: 0f },
            onLevelChange = { level ->
                audioManager?.applyVolume(level)
                adjustment = ScreenAdjustment(ScreenAdjustmentType.Volume, level)
            },
            onAdjustingChange = { adjusting = it },
        )
    }

    adjustment?.let { current ->
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                current.icon(),
                contentDescription = stringResource(current.type.labelRes()),
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
            Box(
                modifier = Modifier
                    .width(LEVEL_BAR_WIDTH_DP.dp)
                    .height(LEVEL_BAR_HEIGHT_DP.dp)
                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50)),
            ) {
                Box(
                    modifier = Modifier
                        .width((LEVEL_BAR_WIDTH_DP * current.level).dp)
                        .fillMaxHeight()
                        .background(Color.White, RoundedCornerShape(50)),
                )
            }
            Text(
                stringResource(R.string.percentage, (current.level * 100).roundToInt()),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun BoxScope.SideGestureZone(
    alignment: Alignment,
    currentLevel: () -> Float,
    onLevelChange: (Float) -> Unit,
    onAdjustingChange: (Boolean) -> Unit,
) {
    // The gesture handler outlives recompositions, so reach the callbacks through updated state.
    val latestCurrentLevel by rememberUpdatedState(currentLevel)
    val latestOnLevelChange by rememberUpdatedState(onLevelChange)
    val latestOnAdjustingChange by rememberUpdatedState(onAdjustingChange)
    Box(
        modifier = Modifier
            .align(alignment)
            .fillMaxHeight()
            .fillMaxWidth(SIDE_GESTURE_WIDTH_FRACTION)
            .pointerInput(Unit) {
                var level = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        level = latestCurrentLevel()
                        latestOnAdjustingChange(true)
                    },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        // Dragging up raises the level; one full-height drag spans the whole range.
                        level = (level - amount / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        latestOnLevelChange(level)
                    },
                    onDragEnd = { latestOnAdjustingChange(false) },
                    onDragCancel = { latestOnAdjustingChange(false) },
                )
            },
    )
}

private data class ScreenAdjustment(val type: ScreenAdjustmentType, val level: Float)

private enum class ScreenAdjustmentType { Brightness, Volume }

private fun ScreenAdjustment.icon(): ImageVector = when (type) {
    ScreenAdjustmentType.Brightness -> Icons.Default.BrightnessHigh
    ScreenAdjustmentType.Volume ->
        if (level <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
}

private fun ScreenAdjustmentType.labelRes(): Int = when (this) {
    ScreenAdjustmentType.Brightness -> R.string.brightness
    ScreenAdjustmentType.Volume -> R.string.volume
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Brightness this window is currently forcing, falling back to the system setting the first time
 * round so the gesture picks up where the display already is instead of jumping.
 */
private fun Activity?.brightnessLevel(): Float {
    val windowBrightness = this?.window?.attributes?.screenBrightness ?: BRIGHTNESS_UNSET
    if (windowBrightness >= 0f) return windowBrightness
    val systemBrightness = this?.let {
        runCatching {
            Settings.System.getInt(it.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrNull()
    }
    return systemBrightness?.coerceIn(0f, 1f) ?: DEFAULT_BRIGHTNESS
}

private fun Activity.applyBrightness(level: Float) {
    val attributes = window.attributes
    attributes.screenBrightness = level.coerceIn(MIN_BRIGHTNESS, 1f)
    window.attributes = attributes
}

private fun AudioManager.volumeLevel(): Float {
    val maxVolume = getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return if (maxVolume <= 0) 0f else getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat()
}

private fun AudioManager.applyVolume(level: Float) {
    val maxVolume = getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (maxVolume <= 0) return
    val volume = (level * maxVolume).roundToInt().coerceIn(0, maxVolume)
    // A locked-down stream throws rather than clamping, and that must not kill the gesture.
    runCatching { setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }
}

/** Share of the player width reserved along each edge for the brightness and volume gestures. */
const val SIDE_GESTURE_WIDTH_FRACTION = 0.1f
private const val LEVEL_BAR_WIDTH_DP = 120
private const val LEVEL_BAR_HEIGHT_DP = 4
private const val ADJUSTMENT_LINGER_MILLIS = 600L
private const val BRIGHTNESS_UNSET = -1f
private const val DEFAULT_BRIGHTNESS = 0.5f

/** Never drive the backlight all the way to zero: some devices read that as a blank screen. */
private const val MIN_BRIGHTNESS = 0.01f
