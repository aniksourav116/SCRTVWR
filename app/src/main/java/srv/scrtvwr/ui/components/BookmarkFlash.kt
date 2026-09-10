package srv.scrtvwr.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import srv.scrtvwr.R

/**
 * Heart burst confirming a double-tap bookmark.
 *
 * [flashId] is a counter rather than a flag: every increment replays the animation, so repeated
 * double taps keep confirming the action even when the media is already bookmarked. A value of 0
 * means nothing has been bookmarked yet on this screen and draws nothing.
 */
@Composable
fun BookmarkFlash(
    flashId: Int,
    modifier: Modifier = Modifier,
) {
    if (flashId == 0) return

    val scale = remember { Animatable(FLASH_MIN_SCALE) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(flashId) {
        scale.snapTo(FLASH_MIN_SCALE)
        alpha.snapTo(0f)
        launch {
            alpha.animateTo(1f, tween(120))
            alpha.animateTo(0f, tween(320, delayMillis = 220))
        }
        scale.animateTo(FLASH_MAX_SCALE, tween(200))
        scale.animateTo(1f, tween(140))
    }

    Icon(
        imageVector = Icons.Default.Favorite,
        contentDescription = stringResource(R.string.bookmarked),
        tint = Color.White,
        modifier = modifier
            .size(FLASH_SIZE)
            .graphicsLayer(
                scaleX = scale.value,
                scaleY = scale.value,
                alpha = alpha.value,
            ),
    )
}

private val FLASH_SIZE = 120.dp
private const val FLASH_MIN_SCALE = 0.6f
private const val FLASH_MAX_SCALE = 1.15f
