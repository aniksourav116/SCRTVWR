package srv.scrt.vwr.ui.commons

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.dp

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun AdjustableScreen(
    modifier: Modifier = Modifier,
    isVertical: Boolean = true,
    isSwitchable: Boolean = false,
    initialFirstWeight: Float = 0.5f,
    minFirstWeight: Float = 0.1f,
    maxFirstWeight: Float = 0.9f,
    isControlVisible: Boolean = true,
    firstContent: @Composable () -> Unit,
    secondContent: @Composable () -> Unit,
) {
    val isVertical by remember { mutableStateOf(isVertical) }

    BoxWithConstraints ( modifier = modifier  )  {
        val maxWidth = constraints.maxWidth
        val maxHeight = constraints.maxHeight

        val isVertical = if (isSwitchable) {
            maxHeight >= maxWidth
        } else {
            isVertical
        }

        Crossfade(targetState = isVertical, label = "orientationCrossfade") { it ->
            if (it) {
                AdjustableColumnScreen(
                    modifier = modifier,
                    initialUpperWeight = initialFirstWeight,
                    minUpperWeight = minFirstWeight,
                    maxUpperWeight = maxFirstWeight,
                    isControlVisible = isControlVisible,
                    upperContent = firstContent,
                    lowerContent = secondContent
                )
            } else {
                AdjustableRowScreen(
                    modifier = modifier,
                    initialLeftWeight = initialFirstWeight,
                    minLeftWeight = minFirstWeight,
                    maxLeftWeight = maxFirstWeight,
                    isControlVisible = isControlVisible,
                    leftContent = firstContent,
                    rightContent = secondContent
                )
            }
        }

    }
}


@Composable
fun AdjustableColumnScreen(
    modifier: Modifier = Modifier,
    initialUpperWeight: Float = 0.5f,
    minUpperWeight: Float = 0.1f,
    maxUpperWeight: Float = 0.9f,
    isControlVisible: Boolean = true,
    upperContent: @Composable () -> Unit,
    lowerContent: @Composable () -> Unit,
) {
    var upperWeightTarget by remember { mutableFloatStateOf(initialUpperWeight) }
    val upperWeight by animateFloatAsState(targetValue = upperWeightTarget, label = "upperWeightAnimation")
    val lowerWeight = animateFloatAsState(targetValue = 1f - upperWeightTarget, label = "lowerWeightAnimation").value
    var totalHeight by remember { mutableFloatStateOf(0f) }

    val draggableState = rememberDraggableState { delta ->
        if (totalHeight > 0) {
            val deltaWeight = delta / totalHeight
            val newWeight = upperWeightTarget + deltaWeight
            // Clamp the weight to prevent one view from disappearing entirely.
            upperWeightTarget = newWeight.coerceIn(minUpperWeight, maxUpperWeight)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .onGloballyPositioned { coordinates ->
                totalHeight = coordinates.size.height.toFloat()
            }

    ) {
        Box(
            modifier = Modifier
                .weight(upperWeight)
                .fillMaxWidth()
                .layoutId("upperContent")
        ) {
            upperContent()
        }

        Box(
            modifier = Modifier
                .height(16.dp)
                .padding(4.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .clip(shape = RoundedCornerShape(8.dp))
                .layoutId("separator")
                .draggable(state = draggableState, orientation = Orientation.Vertical)
        ) {
            this@Column.AnimatedVisibility(
                visible = isControlVisible,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(6.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(Color(0x99CCCCCC))
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(lowerWeight)
                .fillMaxWidth()
                .layoutId("lowerContent")
        ) {
            lowerContent()
        }
    }
}

@Composable
fun AdjustableRowScreen(
    modifier: Modifier = Modifier,
    initialLeftWeight: Float = 0.5f,
    minLeftWeight: Float = 0.1f,
    maxLeftWeight: Float = 0.9f,
    isControlVisible: Boolean = true,
    leftContent: @Composable () -> Unit,
    rightContent: @Composable () -> Unit,
) {
    var leftWeightTarget by remember { mutableFloatStateOf(initialLeftWeight) }
    val leftWeight by animateFloatAsState(targetValue = leftWeightTarget, label = "leftWeightAnimation")
    val rightWeight = animateFloatAsState(targetValue = 1f - leftWeightTarget, label = "rightWeightAnimation").value
    var totalWidth by remember { mutableFloatStateOf(0f) }

    val draggableState = rememberDraggableState { delta ->
        if (totalWidth > 0) {
            val deltaWeight = delta / totalWidth
            val newWeight = leftWeightTarget + deltaWeight
            leftWeightTarget = newWeight.coerceIn(minLeftWeight, maxLeftWeight)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .onGloballyPositioned { coordinates ->
                totalWidth = coordinates.size.width.toFloat()
            }
    ) {
        Box(
            modifier = Modifier
                .weight(leftWeight)
                .fillMaxHeight()
                .layoutId("leftContent")
        ) {
            leftContent()
        }

        Box(
            modifier = Modifier
                .width(16.dp)
                .padding(4.dp)
                .fillMaxHeight()
                .align(Alignment.CenterVertically)
                .clip(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .layoutId("separator")
                .draggable(state = draggableState, orientation = Orientation.Horizontal)
        ) {
            this@Row.AnimatedVisibility(
                visible = isControlVisible,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .height(64.dp)
                            .width(6.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(Color(0x99CCCCCC))
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(rightWeight)
                .fillMaxHeight()
                .layoutId("rightContent")
        ) {
            rightContent()
        }
    }
}

