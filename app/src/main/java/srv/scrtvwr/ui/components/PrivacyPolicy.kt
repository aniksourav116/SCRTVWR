package srv.scrtvwr.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import srv.scrtvwr.BuildConfig
import srv.scrtvwr.R

@Composable
fun PrivacyPolicyButton() {
    var visible by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { visible = true }) { Text(stringResource(R.string.privacy_policy_title)) }
    if (visible) AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text(stringResource(R.string.privacy_policy_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.privacy_policy_play))
                if (BuildConfig.PERSONAL_EDITION) Text(stringResource(R.string.privacy_policy_personal))
            }
        },
        confirmButton = { TextButton(onClick = { visible = false }) { Text(stringResource(R.string.privacy_policy_close)) } },
    )
}
