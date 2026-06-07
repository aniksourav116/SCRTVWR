package srv.scrtvwr.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp

@Composable
fun AdjustablePane(
    modifier: Modifier = Modifier,
    vertical: Boolean? = null,
    initialRatio: Float = 0.5f,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val useVertical = vertical ?: (maxHeight >= maxWidth)
        if (useVertical) {
            AdjustableColumn(initialRatio, first, second)
        } else {
            AdjustableRow(initialRatio, first, second)
        }
    }
}

@Composable
private fun AdjustableColumn(
    initialRatio: Float,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    var ratio by remember { mutableFloatStateOf(initialRatio.coerceIn(0.1f, 0.9f)) }
    var totalHeight by remember { mutableFloatStateOf(1f) }
    val drag = rememberDraggableState { ratio = (ratio + it / totalHeight).coerceIn(0.1f, 0.9f) }

    Column(Modifier.fillMaxSize().onSizeChanged { totalHeight = it.height.toFloat() }) {
        Box(Modifier.fillMaxWidth().weight(ratio)) { first() }
        DividerHandle(Modifier.fillMaxWidth().height(16.dp).draggable(drag, Orientation.Vertical))
        Box(Modifier.fillMaxWidth().weight(1f - ratio)) { second() }
    }
}

@Composable
private fun AdjustableRow(
    initialRatio: Float,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    var ratio by remember { mutableFloatStateOf(initialRatio.coerceIn(0.1f, 0.9f)) }
    var totalWidth by remember { mutableFloatStateOf(1f) }
    val drag = rememberDraggableState { ratio = (ratio + it / totalWidth).coerceIn(0.1f, 0.9f) }

    Row(Modifier.fillMaxSize().onSizeChanged { totalWidth = it.width.toFloat() }) {
        Box(Modifier.fillMaxHeight().weight(ratio)) { first() }
        DividerHandle(Modifier.fillMaxHeight().width(16.dp).draggable(drag, Orientation.Horizontal))
        Box(Modifier.fillMaxHeight().weight(1f - ratio)) { second() }
    }
}

@Composable
private fun DividerHandle(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize(0.35f)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}
