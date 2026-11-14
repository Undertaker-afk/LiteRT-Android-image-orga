package com.imageorga.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imageorga.app.data.ImageRow

@Composable
fun SearchScreen(
    vm: AppViewModel,
    actions: PermissionActions,
    onOpenSettings: () -> Unit,
    onOpenPhoto: (ImageRow) -> Unit,
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val similarTo by vm.similarTo.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val images by vm.images.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val progress by vm.indexProgress.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                vm.similarTo.value = null
                vm.query.value = it
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search your photos") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        similarTo?.let { source ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                InputChip(
                    selected = true,
                    onClick = { vm.similarTo.value = null },
                    label = { Text("Similar to ${source.name}", maxLines = 1) },
                    trailingIcon = { Icon(Icons.Filled.Clear, contentDescription = "Clear") },
                )
            }
        }

        SetupCard(vm, actions, onOpenSettings)
        IndexingStrip(vm)
        progress.problem?.let {
            if (stats.modelReady) {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
            }
        }
        if (search.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        val error = search.error
        when {
            search.active && error != null -> EmptyHint(error)
            search.active && search.results.isEmpty() && !search.busy ->
                EmptyHint(if (stats.indexed == 0) "No photos are indexed yet." else "No matching photos.")
            search.active -> PhotoGrid(search.results, onOpenPhoto)
            images.isEmpty() -> EmptyHint("Your photos appear here once access is granted.")
            else -> PhotoGrid(images, onOpenPhoto)
        }
    }
}
