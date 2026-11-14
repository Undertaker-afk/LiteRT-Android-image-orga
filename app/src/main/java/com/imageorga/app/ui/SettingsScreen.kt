package com.imageorga.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imageorga.app.data.Config
import com.imageorga.app.data.ModelCatalog
import com.imageorga.app.index.PhotoAccess

@Composable
fun SettingsScreen(vm: AppViewModel, actions: PermissionActions) {
    val config by vm.config.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val progress by vm.indexProgress.collectAsStateWithLifecycle()
    val accelNote by vm.accelNote.collectAsStateWithLifecycle()
    var confirmRebuild by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    ) {
        Text("Settings", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)

        // ---- permissions ----------------------------------------------------------------
        Section("Permissions")
        PermissionRow(
            title = "Photos",
            status = when (perms.photos) {
                PhotoAccess.FULL -> "Allowed"
                PhotoAccess.PARTIAL -> "Only selected photos"
                PhotoAccess.NONE -> "Not allowed"
            },
            ok = perms.photos == PhotoAccess.FULL,
            detail = "Needed to read, index and show your photos.",
            action = when (perms.photos) {
                PhotoAccess.FULL -> null
                PhotoAccess.PARTIAL -> "Allow all" to actions.openAppSettings
                PhotoAccess.NONE -> "Allow" to actions.requestRuntime
            },
        )
        PermissionRow(
            title = "Notifications",
            status = if (perms.notifications) "Allowed" else "Not allowed",
            ok = perms.notifications,
            detail = "Shows progress while photos are indexed or a model downloads.",
            action = if (perms.notifications) null else "Allow" to actions.requestRuntime,
        )
        PermissionRow(
            title = "Unrestricted background use",
            status = if (perms.unrestrictedBattery) "Allowed" else "Restricted by battery saving",
            ok = perms.unrestrictedBattery,
            detail = "Optional. Lets new photos be indexed promptly while the app is closed.",
            action = if (perms.unrestrictedBattery) null else "Allow" to actions.requestBattery,
        )
        if (perms.photos != PhotoAccess.FULL || !perms.notifications) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.requestRuntime) { Text("Request all") }
                OutlinedButton(onClick = actions.openAppSettings) { Text("App settings") }
            }
        }

        // ---- model ----------------------------------------------------------------------
        Section("Model")
        ModelCatalog.models.forEach { spec ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.updateConfig { it.copy(modelId = spec.id) } }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = config.modelId == spec.id, onClick = { vm.updateConfig { it.copy(modelId = spec.id) } })
                Column(Modifier.weight(1f)) {
                    Text(spec.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${spec.summary} About ${spec.approxMb} MB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (stats.modelReady) "Downloaded: ${stats.modelFileName}" else "Not downloaded: ${stats.modelFileName}",
                style = MaterialTheme.typography.bodySmall,
                color = if (stats.modelReady) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            if (download.running) {
                DownloadStatus(vm)
            } else if (!stats.modelReady) {
                download.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { vm.startDownload() }) { Text("Download model") }
            }
            if (stats.unusedModelsMb > 0 && !download.running) {
                OutlinedButton(onClick = { vm.deleteUnusedModels() }) {
                    Text("Delete unused model files (${stats.unusedModelsMb} MB)")
                }
            }
        }
        SwitchRow(
            "Download over Wi-Fi only", "Model files are several hundred MB.",
            config.wifiOnly,
        ) { on -> vm.updateConfig { it.copy(wifiOnly = on) } }

        // ---- acceleration ---------------------------------------------------------------
        Section("Hardware acceleration")
        val npu = ModelCatalog.deviceNpu
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Chip: ${ModelCatalog.deviceChipName.ifBlank { "unknown" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (npu != null) {
                    FilterChip(
                        selected = config.backend == Config.BACKEND_NPU,
                        onClick = { vm.updateConfig { it.copy(backend = Config.BACKEND_NPU) } },
                        label = { Text("NPU") },
                    )
                }
                FilterChip(
                    selected = config.backend == Config.BACKEND_GPU,
                    onClick = { vm.updateConfig { it.copy(backend = Config.BACKEND_GPU) } },
                    label = { Text("GPU") },
                )
                FilterChip(
                    selected = config.backend == Config.BACKEND_CPU,
                    onClick = { vm.updateConfig { it.copy(backend = Config.BACKEND_CPU) } },
                    label = { Text("CPU") },
                )
            }
            Text(
                when {
                    npu == null -> "This chip has no NPU build of the model, so NPU is not offered. GPU is usually faster than CPU; CPU works everywhere."
                    npu.known -> "NPU is fastest and uses the least power. It needs its own model file built for ${npu.label}, which is downloaded automatically. Photos indexed on CPU or GPU are indexed again."
                    else -> "No NPU build is listed for ${npu.label} yet. Choosing NPU checks whether one has been published and falls back to CPU if not."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            accelNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val hasReport by com.imageorga.app.ml.Diagnostics.available.collectAsStateWithLifecycle()
            if (hasReport) {
                val context = androidx.compose.ui.platform.LocalContext.current
                OutlinedButton(onClick = {
                    val report = com.imageorga.app.ml.Diagnostics.read(context) ?: return@OutlinedButton
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Image Orga acceleration error")
                        .putExtra(android.content.Intent.EXTRA_TEXT, report)
                    try {
                        context.startActivity(android.content.Intent.createChooser(send, "Share error details"))
                    } catch (_: Exception) {
                    }
                }) { Text("Share error details") }
            }
        }

        // ---- quality --------------------------------------------------------------------
        Section("Quality and size")
        ChoiceRow(
            "Image detail",
            "Higher detail recognises small things better but indexes about half as fast. Changing it re-indexes all photos.",
            Config.VISION_TOKENS, config.visionTokens,
            label = { if (it == 70) "Standard" else "High" },
        ) { v -> vm.updateConfig { it.copy(visionTokens = v) } }
        ChoiceRow(
            "Index size per photo",
            "Larger is more accurate and uses more storage; 128 sorts noticeably less reliably (${"%.1f".format(config.dim * 4 / 1024f)} KB per photo now). Changing it re-indexes all photos.",
            Config.DIMS, config.dim,
            label = { "$it" },
        ) { v -> vm.updateConfig { it.copy(dim = v) } }

        // ---- sorting --------------------------------------------------------------------
        Section("Sorting")
        var strictness by remember(config.strictness) { mutableFloatStateOf(config.strictness) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("How strictly photos must match a category")
            Slider(
                value = strictness,
                onValueChange = { strictness = it },
                onValueChangeFinished = { vm.updateConfig { it.copy(strictness = strictness) } },
                valueRange = 0.04f..0.14f,
            )
            Row {
                Text("Loose: more photos sorted", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Strict: fewer mistakes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- chat model -----------------------------------------------------------------
        Section("Smart sorting and search")
        ChatModelSettings(vm)

        // ---- background -----------------------------------------------------------------
        Section("Background indexing")
        SwitchRow(
            "Index new photos automatically", "Runs in the background when new photos appear.",
            config.autoIndex,
        ) { on -> vm.updateConfig { it.copy(autoIndex = on) } }
        SwitchRow(
            "Only while charging", "Saves battery; new photos become searchable later.",
            config.chargingOnly, enabled = config.autoIndex,
        ) { on -> vm.updateConfig { it.copy(chargingOnly = on) } }

        // ---- index ----------------------------------------------------------------------
        Section("Index")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${stats.indexed} of ${stats.total} photos indexed" + if (stats.failed > 0) " · ${stats.failed} could not be read" else "")
            IndexingStripInline(progress.running, progress.title, progress.done, progress.total)
            progress.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress.running) {
                    OutlinedButton(onClick = { vm.stopIndexing() }) { Text("Stop") }
                } else {
                    Button(onClick = { vm.indexNow() }, enabled = stats.modelReady && perms.photos != PhotoAccess.NONE) { Text("Index now") }
                }
                OutlinedButton(onClick = { confirmRebuild = true }, enabled = stats.modelReady) { Text("Rebuild index") }
            }
        }
    }

    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text("Rebuild the index?") },
            text = { Text("All ${stats.indexed} indexed photos are analysed again. Categories and photos you placed by hand are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRebuild = false
                    vm.rebuildIndex()
                }) { Text("Rebuild") }
            },
            dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Text(
        title,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun PermissionRow(title: String, status: String, ok: Boolean, detail: String, action: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = action.second) { Text(action.first) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, detail: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
            }
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IndexingStripInline(running: Boolean, title: String, done: Int, total: Int) {
    if (!running) return
    Column {
        Text("$title · $done of $total", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
