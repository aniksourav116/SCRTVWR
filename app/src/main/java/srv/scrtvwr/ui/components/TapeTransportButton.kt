package srv.scrtvwr.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A rewind or fast-forward button that behaves like the transport keys on a tape deck.
 *
 * A tap steps by [nudgeMillis], the precise nudge. Holding engages the spool: the position runs
 * continuously in [forward]'s direction, winding up from a crawl to full speed, and stops wherever
 * you let go. Playback is handed to the caller through [onSpoolingChange] so it can disengage while
 * the reels are turning, and the running position goes out through [onSpoolPreview] for a counter.
 *
 * Nothing is seeked while the button is held - only the target moves - so a long hold costs one
 * seek rather than a storm of them.
 */
@Composable
fun TapeTransportButton(
    icon: ImageVector,
    contentDescription: String,
    forward: Boolean,
    positionMillis: () -> Long,
    durationMillis: Long,
    nudgeMillis: Long,
    onSeek: (Long) -> Unit,
    onSpoolPreview: (Long?) -> Unit,
    onSpoolingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The gesture handler outlives recompositions, so reach the callbacks through updated state.
    val latestPosition by rememberUpdatedState(positionMillis)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestOnSpoolPreview by rememberUpdatedState(onSpoolPreview)
    val latestOnSpoolingChange by rememberUpdatedState(onSpoolingChange)
    var spooling by remember { mutableStateOf(false) }
    val label = contentDescription
    val lastMillis = durationMillis.coerceAtLeast(0L).toFloat()

    fun nudge() {
        val step = if (forward) nudgeMillis else -nudgeMillis
        latestOnSeek((latestPosition() + step).toFloat().coerceIn(0f, lastMillis).toLong())
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.contentDescription = label
                onClick {
                    nudge()
                    true
                }
            }
            .pointerInput(forward, durationMillis, nudgeMillis) {
                // Set while the press turns into a spool, so the release that ends the spool does
                // not also register as the tap that nudges.
                var suppressTap = false
                detectTapGestures(
                    onPress = {
                        suppressTap = false
                        // A brief press is an ordinary nudge. Lean on the button and the transport
                        // engages instead, the way a tape deck starts spooling once you hold it.
                        if (withTimeoutOrNull(SPOOL_ENGAGE_MILLIS) { tryAwaitRelease() } != null) {
                            return@detectTapGestures
                        }
                        suppressTap = true
                        spooling = true
                        latestOnSpoolingChange(true)
                        var target = latestPosition().toFloat().coerceIn(0f, lastMillis)
                        var held = 0L
                        coroutineScope {
                            val spool = launch {
                                while (true) {
                                    delay(SPOOL_TICK_MILLIS)
                                    held += SPOOL_TICK_MILLIS
                                    val travel = spoolRate(held) * SPOOL_TICK_MILLIS
                                    target = (if (forward) target + travel else target - travel)
                                        .coerceIn(0f, lastMillis)
                                    latestOnSpoolPreview(target.toLong())
                                }
                            }
                            tryAwaitRelease()
                            spool.cancel()
                        }
                        latestOnSpoolPreview(null)
                        latestOnSeek(target.toLong())
                        spooling = false
                        latestOnSpoolingChange(false)
                    },
                    onTap = {
                        if (suppressTap) suppressTap = false else nudge()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (spooling) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Spool speed after [heldMillis] of holding, in video milliseconds travelled per real millisecond.
 *
 * It winds up from [SPOOL_START_RATE] to [SPOOL_MAX_RATE] rather than starting at full tilt, so a
 * short hold creeps forward a few seconds and a long one covers minutes - the reels taking a moment
 * to get up to speed is what makes the gesture controllable at both ends.
 */
private fun spoolRate(heldMillis: Long): Float {
    val windUp = (heldMillis / SPOOL_WIND_UP_MILLIS).coerceIn(0f, 1f)
    return SPOOL_START_RATE + (SPOOL_MAX_RATE - SPOOL_START_RATE) * windUp
}

/** How long the button must be held before a nudge turns into a spool. */
private const val SPOOL_ENGAGE_MILLIS = 250L
private const val SPOOL_TICK_MILLIS = 50L
private const val SPOOL_WIND_UP_MILLIS = 2_000f
private const val SPOOL_START_RATE = 4f
private const val SPOOL_MAX_RATE = 60f
