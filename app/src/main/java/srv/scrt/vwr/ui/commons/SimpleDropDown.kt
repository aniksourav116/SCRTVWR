package srv.scrt.vwr.ui.commons

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SimpleDropdownSelector(
    label: String = "Select item",
    items: List<T>,
    selected: T?,
    onSelected: (T) -> Unit,
    labelProvider: (T) -> String = { it.toString() },
    searchable: Boolean = false,
    emptySearchMessage: String = "No matching options.",
    searchTextProvider: (T) -> String = labelProvider
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val visibleItems = if (searchable && searchQuery.isNotBlank()) {
        items.filter { searchTextProvider(it).contains(searchQuery.trim(), ignoreCase = true) }
    } else {
        items
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (searchable) {
                if (!expanded) {
                    searchQuery = ""
                    expanded = true
                }
            } else {
                expanded = it
            }
        }
    ) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor(
                    type = if (searchable) {
                        ExposedDropdownMenuAnchorType.PrimaryEditable
                    } else {
                        ExposedDropdownMenuAnchorType.PrimaryNotEditable
                    },
                    enabled = true
                )
                .fillMaxWidth(),
            readOnly = !searchable,
            value = if (searchable && expanded) {
                searchQuery
            } else {
                selected?.let(labelProvider) ?: ""
            },
            onValueChange = {
                if (searchable) {
                    searchQuery = it
                    expanded = true
                }
            },
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (visibleItems.isEmpty() && searchable && searchQuery.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text(emptySearchMessage) },
                    onClick = {},
                    enabled = false
                )
            }
            visibleItems.forEach { item ->
                DropdownMenuItem(
                    text = { Text(labelProvider(item)) },
                    onClick = {
                        onSelected(item)
                        expanded = false
                    }
                )
            }
        }
    }
}
