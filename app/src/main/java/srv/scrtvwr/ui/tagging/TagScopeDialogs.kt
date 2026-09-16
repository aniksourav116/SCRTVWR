package srv.scrtvwr.ui.tagging

import android.os.Environment
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import srv.scrtvwr.R
import srv.scrtvwr.tagging.TagFolderRule
import srv.scrtvwr.tagging.TagScanScope
import srv.scrtvwr.tagging.TagScopeMode
import java.io.File

/** What the user asked for about one folder, from the folder's own dialog. */
enum class FolderTagAction {
    TagNow,
    AlwaysTag,
    NeverTag,
    UseDefault,
}

/**
 * The standing answer to "which folders may be tagged": everywhere or only the chosen folders, plus
 * the folders singled out either way. Editing is immediate - every change goes straight through.
 */
@Composable
fun TagScopeDialog(
    scope: TagScanScope,
    onModeChange: (TagScopeMode) -> Unit,
    onRuleChange: (TagFolderRule) -> Unit,
    onRuleRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Whether the folder about to be picked is one to tag or one to leave alone.
    var picking by remember { mutableStateOf<Boolean?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tagging_folders)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TagScopeMode.entries.forEach { mode ->
                    ScopeModeRow(
                        mode = mode,
                        selected = scope.mode == mode,
                        onSelect = { onModeChange(mode) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                if (scope.rules.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (scope.mode == TagScopeMode.SelectedFolders) {
                                R.string.tag_scope_no_folders
                            } else {
                                R.string.tag_scope_no_exceptions
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(scope.rules, key = TagFolderRule::path) { rule ->
                            FolderRuleRow(
                                rule = rule,
                                onSubfoldersChange = { includeSubfolders ->
                                    onRuleChange(rule.copy(includeSubfolders = includeSubfolders))
                                },
                                onRemove = { onRuleRemove(rule.path) },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { picking = true }) {
                        Text(stringResource(R.string.tag_scope_add_folder))
                    }
                    TextButton(onClick = { picking = false }) {
                        Text(stringResource(R.string.tag_scope_add_skipped_folder))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )

    picking?.let { included ->
        FolderPickerDialog(
            onPick = { folder ->
                onRuleChange(TagFolderRule(folder.absolutePath, includeSubfolders = true, included = included))
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun ScopeModeRow(mode: TagScopeMode, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    when (mode) {
                        TagScopeMode.AllStorage -> R.string.tag_scope_all_storage
                        TagScopeMode.SelectedFolders -> R.string.tag_scope_selected_folders
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    when (mode) {
                        TagScopeMode.AllStorage -> R.string.tag_scope_all_storage_summary
                        TagScopeMode.SelectedFolders -> R.string.tag_scope_selected_folders_summary
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FolderRuleRow(
    rule: TagFolderRule,
    onSubfoldersChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (rule.included) Icons.Default.Folder else Icons.Default.Block,
            contentDescription = stringResource(
                if (rule.included) R.string.tag_scope_tagged else R.string.tag_scope_skipped,
            ),
            modifier = Modifier.size(20.dp),
            tint = if (rule.included) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = File(rule.path).name.ifBlank { rule.path },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rule.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.clickable { onSubfoldersChange(!rule.includeSubfolders) },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = rule.includeSubfolders, onCheckedChange = onSubfoldersChange)
            Text(
                text = stringResource(R.string.tag_scope_subfolders_short),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.tag_scope_remove_folder),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Walks storage so the user can hand one folder to tagging. It lists folders only: files are not
 * what is being chosen, and leaving them out keeps a picture folder from filling the list.
 */
@Composable
fun FolderPickerDialog(
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
    start: File? = Environment.getExternalStorageDirectory(),
) {
    val startPath = remember(start) { start?.absolutePath ?: STORAGE_ROOT }
    var currentPath by rememberSaveable(startPath) { mutableStateOf(startPath) }
    val current = remember(currentPath) { File(currentPath) }
    val folders by produceState(emptyList<File>(), currentPath) {
        value = withContext(Dispatchers.IO) {
            current.listFiles().orEmpty().filter(File::isDirectory).sortedBy { it.name.lowercase() }
        }
    }
    // Climbing stops at /storage, which is as far up as the volumes themselves go.
    val parent = remember(currentPath) {
        current.parentFile?.takeIf { currentPath.length > STORAGE_ROOT.length && currentPath.startsWith(STORAGE_ROOT) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { parent?.let { currentPath = it.absolutePath } },
                        enabled = parent != null,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.parent_folder),
                        )
                    }
                    Text(
                        text = currentPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (folders.isEmpty()) {
                    Text(
                        text = stringResource(R.string.folder_picker_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(folders, key = { folder -> folder.absolutePath }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentPath = folder.absolutePath }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text(stringResource(R.string.use_this_folder)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Everything that can be said about the folder the user is looking at: tag it once, tag it always,
 * never tag it, or go back to whatever the mode decides.
 */
@Composable
fun FolderTaggingDialog(
    folderName: String,
    rule: TagFolderRule?,
    onApply: (FolderTagAction, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var action by rememberSaveable(folderName) {
        mutableStateOf(
            when {
                rule == null -> FolderTagAction.TagNow
                rule.included -> FolderTagAction.AlwaysTag
                else -> FolderTagAction.NeverTag
            },
        )
    }
    var includeSubfolders by rememberSaveable(folderName) { mutableStateOf(rule?.includeSubfolders ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tag_folder_title, folderName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FolderTagAction.entries
                    // "No rule" is only worth offering once there is a rule to take away.
                    .filter { it != FolderTagAction.UseDefault || rule != null }
                    .forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { action = candidate }
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = action == candidate, onClick = { action = candidate })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(candidate.titleResource()),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(candidate.summaryResource()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                if (action != FolderTagAction.UseDefault) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeSubfolders = !includeSubfolders }
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = includeSubfolders,
                            onCheckedChange = { includeSubfolders = it },
                        )
                        Text(
                            text = stringResource(R.string.tag_scope_include_subfolders),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(action, includeSubfolders) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun FolderTagAction.titleResource(): Int = when (this) {
    FolderTagAction.TagNow -> R.string.tag_folder_now
    FolderTagAction.AlwaysTag -> R.string.tag_folder_always
    FolderTagAction.NeverTag -> R.string.tag_folder_never
    FolderTagAction.UseDefault -> R.string.tag_folder_default
}

private fun FolderTagAction.summaryResource(): Int = when (this) {
    FolderTagAction.TagNow -> R.string.tag_folder_now_summary
    FolderTagAction.AlwaysTag -> R.string.tag_folder_always_summary
    FolderTagAction.NeverTag -> R.string.tag_folder_never_summary
    FolderTagAction.UseDefault -> R.string.tag_folder_default_summary
}

/** Volumes live directly under this, so it is as high as the picker ever needs to climb. */
private const val STORAGE_ROOT = "/storage"
