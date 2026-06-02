package srv.scrt.vwr.ui.commons

import android.content.res.Configuration
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseScaffoldSimple(
    modifier: Modifier = Modifier,
    title: String = "ConnecTreat",
    subtitle: String = "",
    hasNavigationIcon: Boolean = true,
    onNavigationClick: () -> Unit = { },
    isInDarkMode : Boolean = isSystemInDarkTheme(),
    content: @Composable (Modifier) -> Unit = { _ -> }
) {
        val colorScheme = MaterialTheme.colorScheme

        val topBarColor = if (isInDarkMode) {
            colorScheme.surface
        } else {
            colorScheme.background
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = colorScheme.background,
            topBar = {
                Surface(
//                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    color = topBarColor
                ) {
                    TopAppBar(
                        title = {
                            if (subtitle.isBlank()) {
                                Text(
                                    text = title.trim(),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .basicMarquee(
                                            iterations = Int.MAX_VALUE,
                                            initialDelayMillis = 0,
                                            repeatDelayMillis = 0,
                                            velocity = 50.dp
                                        )

                                )
                            } else {
                                Column {
                                    Text(
                                        text = title.trim(),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .basicMarquee(
                                                iterations = Int.MAX_VALUE,
                                                initialDelayMillis = 0,
                                                repeatDelayMillis = 0,
                                                velocity = 50.dp
                                            )

                                    )
                                    Text(
                                        text = subtitle.trim(),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .basicMarquee(
                                                iterations = Int.MAX_VALUE,
                                                initialDelayMillis = 0,
                                                repeatDelayMillis = 0,
                                                velocity = 50.dp
                                            )

                                    )


                                }
                            }


                        },
                        navigationIcon = {
                            if (hasNavigationIcon) {
                                IconButton(onClick = onNavigationClick) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        },
                        // Let the Surface provide the background
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent
                        )
                    )
                }
            }
        ) { innerPadding ->
            content(modifier.padding(innerPadding))
        }
}

@Preview(showBackground = true, name = "Light Mode")
@Composable
fun BaseScaffoldSimplePreviewLight() {
    BaseScaffoldSimple(
        title = "This is a very long title to demonstrate the marquee effect in the top app bar",
        subtitle = "This is a subtitle that is also quite long to see how it behaves",
        isInDarkMode = false,
        onNavigationClick = { }
    ) { modifier ->
        Box(
            modifier = modifier.fillMaxSize()
        ) {
            Text(text = "Content goes here", modifier = modifier.align(Alignment.Center))
        }
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Dark Mode")
@Composable
fun BaseScaffoldSimplePreviewDark() {
    BaseScaffoldSimple(
        title = "This is a very long title to demonstrate the marquee effect in the top app bar",
        subtitle = "This is a subtitle that is also quite long to see how it behaves",
        isInDarkMode = true,
        onNavigationClick = { }
    ) { modifier ->
        Box(
            modifier = modifier.fillMaxSize()
        ) {
            Text(text = "Content goes here", modifier = modifier.align(Alignment.Center))
        }
    }
}

@Preview(showBackground = true, name = "Light Mode")
@Composable
fun BaseScaffoldSimplePreviewNoSubtitleLight() {
    BaseScaffoldSimple(
        title = "Short Title",
        isInDarkMode = false,
        onNavigationClick = { }
    ) { modifier ->
        Box(
            modifier = modifier.fillMaxSize()
        ) {
            Text(text = "Content goes here", modifier = modifier.align(Alignment.Center))
        }
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Dark Mode")
@Composable
fun BaseScaffoldSimplePreviewNoSubtitleDark() {
    BaseScaffoldSimple(
        title = "Short Title",
        isInDarkMode = true,
        onNavigationClick = { }
    ) { modifier ->
        Box(
            modifier = modifier.fillMaxSize()
        ) {
            Text(text = "Content goes here", modifier = modifier.align(Alignment.Center))
        }
    }
}
