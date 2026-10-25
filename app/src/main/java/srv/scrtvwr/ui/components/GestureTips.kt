package srv.scrtvwr.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import srv.scrtvwr.R
import srv.scrtvwr.preferences.ScrtvwrPreferences

/** A screen with its own gesture guide, shown once until reset from the settings. */
enum class GestureTipsScreen {
    Viewer,
    Player,
}

data class GestureTip(
    val icon: ImageVector,
    val text: String,
)

/** Shows [tips] over the screen the first time [screen] opens, until they are dismissed. */
@Composable
fun FirstRunGestureTips(
    screen: GestureTipsScreen,
    tips: List<GestureTip>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember { ScrtvwrPreferences(context) }
    var visible by rememberSaveable { mutableStateOf(!preferences.hasSeenGestureTips(screen)) }
    if (!visible) return
    GestureTipsOverlay(
        tips = tips,
        onDismiss = {
            preferences.saveGestureTipsSeen(screen)
            visible = false
        },
        modifier = modifier,
    )
}

@Composable
fun GestureTipsOverlay(
    tips: List<GestureTip>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            // Holds every touch, so nothing reaches the viewer behind the guide.
            .pointerInput(Unit) { detectTapGestures { } }
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = stringResource(R.string.gesture_tips_title),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
            tips.forEach { tip ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(tip.icon, contentDescription = null, tint = Color.White)
                    Text(
                        text = tip.text,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.got_it))
            }
        }
    }
}
