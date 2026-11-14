package com.imageorga.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.imageorga.app.data.Category
import com.imageorga.app.data.ImageRow

@Composable
fun CategoriesScreen(
    vm: AppViewModel,
    openCategory: Long?,
    onOpenCategory: (Long?) -> Unit,
    onOpenPhoto: (ImageRow) -> Unit,
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val images by vm.images.collectAsStateWithLifecycle()
    // null = closed, a category with id 0 = creating a new one
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }

    val byCategory = remember(images) { images.groupBy { it.categoryId } }

    if (openCategory != null) {
        val category = categories.firstOrNull { it.id == openCategory }
        if (category == null && openCategory != UNSORTED) {
            // The category was deleted while open: go back to the list.
            androidx.compose.runtime.LaunchedEffect(openCategory) { onOpenCategory(null) }
        } else {
            val photos = byCategory[if (openCategory == UNSORTED) null else openCategory].orEmpty()
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onOpenCategory(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(category?.name ?: "Unsorted", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${photos.size} ${if (photos.size == 1) "photo" else "photos"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (category != null) {
                        IconButton(onClick = { editing = category }) { Icon(Icons.Filled.Edit, contentDescription = "Edit category") }
                        IconButton(onClick = { deleting = category }) { Icon(Icons.Filled.Delete, contentDescription = "Delete category") }
                    }
                }
                IndexingStrip(vm)
                if (photos.isEmpty()) {
                    EmptyHint(if (category != null) "No photos match this category yet." else "Every photo is in a category.")
                } else {
                    PhotoGrid(photos, onOpenPhoto)
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("Categories", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Describe a category in your own words. Photos that match are sorted into it automatically.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(categories, key = { it.id }) { c ->
                    CategoryCard(c.name, c.description, byCategory[c.id].orEmpty()) { onOpenCategory(c.id) }
                }
                if (images.isNotEmpty()) {
                    item(key = "unsorted") {
                        CategoryCard("Unsorted", "Photos that match no category", byCategory[null].orEmpty()) { onOpenCategory(UNSORTED) }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { editing = Category(0, "", "") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New category") },
            )
        }
    }

    editing?.let { c ->
        CategoryDialog(
            initial = c,
            onDismiss = { editing = null },
            onSave = { name, description ->
                vm.saveCategory(c.id.takeIf { it != 0L }, name, description)
                editing = null
            },
        )
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${c.name}”?") },
            text = { Text("Its photos stay on your device and are sorted again into the remaining categories.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCategory(c.id)
                    deleting = null
                    onOpenCategory(null)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CategoryCard(name: String, description: String, photos: List<ImageRow>, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${photos.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (description.isNotBlank()) {
                Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (photos.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    photos.take(4).forEach { p ->
                        AsyncImage(
                            model = p.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                    // Keep tiles the same size when there are fewer than four photos.
                    repeat(4 - minOf(4, photos.size)) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CategoryDialog(initial: Category, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "New category" else "Edit category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Topic") },
                    placeholder = { Text("e.g. Receipts") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    placeholder = { Text("e.g. paper receipts, invoices, bills") },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Text(
                    "List what the photos show, separated by commas. Concrete things work best. Any language works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
