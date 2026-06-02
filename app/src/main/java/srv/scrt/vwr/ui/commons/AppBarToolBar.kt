package srv.scrt.vwr.ui.commons

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBarToolBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    balance: String? = null,
    registrationIconRes: Int? = null,
    showBackButton: Boolean = true,
    onBackClick: () -> Unit = {},
    isCentered: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}
) {
//    val isCentered = booleanResource(id = R.bool.centeredToolbar)
    val titleColor = MaterialTheme.colorScheme.onPrimary
    
    // Background drawable equivalent
    // In XML it's @drawable/app_bar_bg which is a solid ?colorPrimary rectangle
    val appBarBackground = MaterialTheme.colorScheme.primary

    CenterAlignedTopAppBar(
        modifier = modifier.background(appBarBackground),
        title = {
            Column(
                horizontalAlignment = if (isCentered) Alignment.CenterHorizontally else Alignment.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (isCentered) Arrangement.Center else Arrangement.Start,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = titleColor,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
                        modifier = if (balance == null && registrationIconRes == null) Modifier.weight(1f) else Modifier
                    )

                    if (balance != null || registrationIconRes != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            balance?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium.copy(color = titleColor),
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            registrationIconRes?.let {
                                Image(
                                    painter = painterResource(id = it),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }
                
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = titleColor,
//                            fontSize = dimensionResource(id = R.dimen.toolBarSubTitleTextSize).value.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        navigationIcon = {
            if (showBackButton) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = titleColor
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = titleColor,
            navigationIconContentColor = titleColor,
            actionIconContentColor = titleColor
        )
    )
}

@Preview
@Composable
fun AppBarToolBarPreview() {
    MaterialTheme {
        AppBarToolBar(
            title = "Dialer App",
            subtitle = "Connected",
            balance = "346 BDT",
            isCentered = false
        )
    }
}
