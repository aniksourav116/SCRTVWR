package srv.scrtvwr.ui.tagging

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import srv.scrtvwr.R
import srv.scrtvwr.tagging.CharacterProfile
import srv.scrtvwr.tagging.TagScanProgress

/** Asks before the first whole-device pass. Declining leaves lazy per-folder tagging running. */
@Composable
fun StorageScanConsentDialog(
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.tag_storage_title)) },
        text = { Text(stringResource(R.string.tag_storage_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.tag_storage_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text(stringResource(R.string.not_now)) }
        },
    )
}

/** Every character tagging has found, with rename, remove and "show only this one". */
@Composable
fun CharactersDialog(
    characters: List<CharacterProfile>,
    onRename: (String, String?) -> Unit,
    onRemove: (String) -> Unit,
    onFilter: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<CharacterProfile?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.characters)) },
        text = {
            if (characters.isEmpty()) {
                Text(stringResource(R.string.no_characters_yet))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(characters, key = CharacterProfile::id) { profile ->
                        CharacterRow(
                            profile = profile,
                            onRename = { renaming = profile },
                            onRemove = { onRemove(profile.id) },
                            onFilter = {
                                onFilter(profile.id)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )

    renaming?.let { profile ->
        RenameCharacterDialog(
            profile = profile,
            onRename = { label ->
                onRename(profile.id, label)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun CharacterRow(
    profile: CharacterProfile,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onFilter: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onFilter)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterCover(profile = profile, modifier = Modifier.size(44.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayLabel(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.character_image_count, profile.imageCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onFilter) {
            Icon(Icons.Default.FilterAlt, contentDescription = stringResource(R.string.show_only_character))
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.rename_character))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_character))
        }
    }
}

@Composable
private fun RenameCharacterDialog(
    profile: CharacterProfile,
    onRename: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.label.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_character)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.character_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name.trim().takeIf(String::isNotEmpty)) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Inline progress for the whole-storage pass, with a way to stop it. */
@Composable
fun TaggingProgressPanel(
    progress: TagScanProgress,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tagging_images),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = if (progress.total > 0) {
                            stringResource(R.string.tagging_progress, progress.processed, progress.total)
                        } else {
                            stringResource(R.string.tagging_counting_images)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onStop) { Text(stringResource(R.string.stop_tagging)) }
            }
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
