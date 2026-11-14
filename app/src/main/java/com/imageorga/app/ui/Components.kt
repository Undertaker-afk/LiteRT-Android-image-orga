package com.imageorga.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.imageorga.app.data.Category
import com.imageorga.app.data.ImageRow
import com.imageorga.app.index.PhotoAccess

@Composable
fun PhotoGrid(images: List<ImageRow>, onOpen: (ImageRow) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(images, key = { it.id }) { image ->
            AsyncImage(
                model = image.uri,
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpen(image) },
            )
        }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Thin strip shown wherever photos are listed while indexing runs. */
@Composable
fun IndexingStrip(vm: AppViewModel) {
    val progress by vm.indexProgress.collectAsStateWithLifecycle()
    if (!progress.running) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            "${progress.title} · ${progress.done} of ${progress.total}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Lists what still has to happen before the app can work: photo access, the model
 * download and, optionally, notifications. Disappears once everything required is done.
 */
@Composable
fun SetupCard(vm: AppViewModel, actions: PermissionActions, onOpenSettings: (() -> Unit)?) {
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val needPhotos = perms.photos == PhotoAccess.NONE
    val needModel = !stats.modelReady
    if (!needPhotos && !needModel) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium)
            if (needPhotos) {
                Text("Allow access to your photos so they can be indexed. Everything stays on this device.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.requestRuntime) { Text("Allow access") }
                    OutlinedButton(onClick = actions.openAppSettings) { Text("App settings") }
                }
            }
            if (needModel) {
                if (download.running) {
                    DownloadStatus(vm)
                } else {
                    Text("Download the model that understands your photos. This is needed once.")
                    download.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.startDownload() }) { Text("Download model") }
                        if (onOpenSettings != null) OutlinedButton(onClick = onOpenSettings) { Text("Choose model") }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadStatus(vm: AppViewModel) {
    val download by vm.download.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val text = when {
            download.total > 0 -> "Downloading model · ${download.bytes / 1_000_000} of ${download.total / 1_000_000} MB"
            download.bytes > 0 -> "Downloading model · ${download.bytes / 1_000_000} MB"
            config.wifiOnly -> "Waiting for Wi-Fi to download the model"
            else -> "Waiting for a connection to download the model"
        }
        Text(text)
        if (download.total > 0) {
            LinearProgressIndicator(
                progress = { download.bytes.toFloat() / download.total },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        TextButton(onClick = { vm.cancelDownload() }) { Text("Cancel download") }
    }
}

@Composable
fun PhotoViewer(
    image: ImageRow,
    categories: List<Category>,
    onClose: () -> Unit,
    onFindSimilar: () -> Unit,
    onMove: (Long?) -> Unit,
    onAutoSort: () -> Unit,
) {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    val categoryName = categories.firstOrNull { it.id == image.categoryId }?.name

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(image.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append(categoryName ?: "Unsorted")
                            if (image.manual) append(" · placed by you")
                            if (image.bucket.isNotEmpty()) append(" · ${image.bucket}")
                        },
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AsyncImage(
                model = image.uri,
                contentDescription = image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = onFindSimilar, label = { Text("Find similar") }, colors = viewerChipColors())
                AssistChip(onClick = { choosing = true }, label = { Text("Move to category") }, colors = viewerChipColors())
                AssistChip(onClick = { openExternally(context, image) }, label = { Text("Open") }, colors = viewerChipColors())
                AssistChip(onClick = { share(context, image) }, label = { Text("Share") }, colors = viewerChipColors())
            }
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Move to category") },
            text = {
                Column {
                    CategoryChoice("Sort automatically", selected = !image.manual) { onAutoSort(); choosing = false }
                    CategoryChoice("No category", selected = image.manual && image.categoryId == null) { onMove(null); choosing = false }
                    categories.forEach { c ->
                        CategoryChoice(c.name, selected = image.manual && image.categoryId == c.id) { onMove(c.id); choosing = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun viewerChipColors() = androidx.compose.material3.AssistChipDefaults.assistChipColors(
    labelColor = Color.White,
)

@Composable
private fun CategoryChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

private fun openExternally(context: Context, image: ImageRow) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(image.uri, "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun share(context: Context, image: ImageRow) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("image/*")
        .putExtra(Intent.EXTRA_STREAM, image.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {
    }
}
