package srv.scrtvwr.ui.dashboard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import srv.scrtvwr.ui.dashboard.screens.viewmodels.OthersUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.OthersViewModel

@Composable
fun Others(
    modifier: Modifier = Modifier,
    viewModel: OthersViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    OthersScreen(
        uiState = uiState,
        onShowHiddenFilesChange = viewModel::setShowHiddenFiles,
        onSlideShowStayMillisChange = viewModel::setSlideShowStayMillis,
        onImagePreloadDistanceChange = viewModel::setImagePreloadDistance,
        modifier = modifier,
    )
}

@Composable
fun OthersScreen(
    uiState: OthersUiState,
    onShowHiddenFilesChange: (Boolean) -> Unit,
    onSlideShowStayMillisChange: (Long) -> Unit,
    onImagePreloadDistanceChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var slideShowStayMillisText by remember { mutableStateOf(uiState.slideShowStayMillis.toString()) }
    var imagePreloadDistanceText by remember { mutableStateOf(uiState.imagePreloadDistance.toString()) }

    LaunchedEffect(uiState.slideShowStayMillis) {
        if (slideShowStayMillisText.toLongOrNull() != uiState.slideShowStayMillis) {
            slideShowStayMillisText = uiState.slideShowStayMillis.toString()
        }
    }

    LaunchedEffect(uiState.imagePreloadDistance) {
        if (imagePreloadDistanceText.toIntOrNull() != uiState.imagePreloadDistance) {
            imagePreloadDistanceText = uiState.imagePreloadDistance.toString()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Other options", style = MaterialTheme.typography.headlineSmall)
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            headlineContent = { Text("Show hidden files") },
            supportingContent = { Text("Include hidden items in directory listings.") },
            leadingContent = { Icon(Icons.Default.Visibility, contentDescription = null) },
            trailingContent = {
                Switch(
                    checked = uiState.showHiddenFiles,
                    onCheckedChange = onShowHiddenFilesChange,
                )
            },
        )
        NumericPreferenceListItem(
            headline = "Slide show speed",
            supporting = "Milliseconds each image stays on screen.",
            value = slideShowStayMillisText,
            onValueChange = { value ->
                slideShowStayMillisText = value.filter(Char::isDigit)
                slideShowStayMillisText.toLongOrNull()?.let(onSlideShowStayMillisChange)
            },
            leadingContent = { Icon(Icons.Default.Slideshow, contentDescription = null) },
            suffix = "ms",
        )
        NumericPreferenceListItem(
            headline = "Image preload distance",
            supporting = "How many nearby pages are prepared in the viewer.",
            value = imagePreloadDistanceText,
            onValueChange = { value ->
                imagePreloadDistanceText = value.filter(Char::isDigit)
                imagePreloadDistanceText.toIntOrNull()?.let(onImagePreloadDistanceChange)
            },
            leadingContent = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
        )
    }
}

@Composable
private fun NumericPreferenceListItem(
    headline: String,
    supporting: String,
    value: String,
    onValueChange: (String) -> Unit,
    leadingContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
) {
    ListItem(
        modifier = modifier.fillMaxWidth(),
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        leadingContent = leadingContent,
        trailingContent = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                suffix = suffix?.let { { Text(it) } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                modifier = Modifier.fillMaxWidth(0.42f),
            )
        },
    )
}
