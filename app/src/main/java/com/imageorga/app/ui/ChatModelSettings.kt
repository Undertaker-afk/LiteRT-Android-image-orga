package com.imageorga.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imageorga.app.data.Config
import com.imageorga.app.data.ModelCatalog

/** Settings for the optional local chat model that double-checks sorting and improves search. */
@Composable
fun ChatModelSettings(vm: AppViewModel) {
    val config by vm.config.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val download by vm.chatDownload.collectAsStateWithLifecycle()
    val note by vm.chatNote.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    ToggleRow(
        "Use a local chat model",
        "A Gemma model on this phone looks at photos before they are sorted and makes searches more forgiving. " +
            "Needs a large download and is much slower than normal indexing.",
        config.llmEnabled,
    ) { on -> vm.updateConfig { it.copy(llmEnabled = on) } }
    if (!config.llmEnabled) return

    // The chosen model; for "your own model" it stands in until a link is entered.
    val spec = ModelCatalog.resolveLlm(config)?.spec ?: ModelCatalog.llmById(config.llmModelId)
    val npu = ModelCatalog.deviceNpu
    val npuOffered = spec?.offersNpu(npu) == true

    // ---- which model ---------------------------------------------------------------------
    val options = ModelCatalog.llmModels + listOfNotNull(ModelCatalog.customLlm(""))
    options.forEach { spec ->
        val select = { vm.updateConfig { it.copy(llmModelId = spec.id) } }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = select).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = config.llmModelId == spec.id, onClick = select)
            Column(Modifier.weight(1f)) {
                Text(spec.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (spec.approxMb > 0) "${spec.summary} About ${"%.1f".format(spec.approxMb / 1000f)} GB." else spec.summary,
                    style = MaterialTheme.typography.bodySmall, color = muted,
                )
            }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (config.llmModelId == ModelCatalog.CUSTOM_LLM_ID) {
            var link by remember(config.llmCustomUrl) { mutableStateOf(config.llmCustomUrl) }
            val usable = link.isBlank() || ModelCatalog.customLlm(link)?.directUrl != null
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Download link to a .litertlm file") },
                placeholder = { Text("https://huggingface.co/…/model.litertlm") },
                singleLine = true,
                isError = !usable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Text(
                "The model must be a LiteRT-LM chat model. To check photos it has to accept images; text-only models can still help with categories and search.",
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
            if (link.trim() != config.llmCustomUrl) {
                TextButton(onClick = { vm.updateConfig { it.copy(llmCustomUrl = link.trim()) } }, enabled = usable) { Text("Use this link") }
            }
        }

        // ---- download ----------------------------------------------------------------------
        if (stats.chatModelFileName.isNotEmpty()) {
            Text(
                if (stats.chatModelReady) "Downloaded: ${stats.chatModelFileName}" else "Not downloaded: ${stats.chatModelFileName}",
                style = MaterialTheme.typography.bodySmall,
                color = if (stats.chatModelReady) muted else MaterialTheme.colorScheme.error,
            )
        }
        if (download.running) {
            val text = when {
                download.total > 0 -> "Downloading chat model · ${download.bytes / 1_000_000} of ${download.total / 1_000_000} MB"
                download.bytes > 0 -> "Downloading chat model · ${download.bytes / 1_000_000} MB"
                config.wifiOnly -> "Waiting for Wi-Fi to download the chat model"
                else -> "Waiting for a connection to download the chat model"
            }
            Text(text)
            if (download.total > 0) {
                LinearProgressIndicator(progress = { download.bytes.toFloat() / download.total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            TextButton(onClick = { vm.cancelChatDownload() }) { Text("Cancel download") }
        } else if (!stats.chatModelReady && stats.chatModelFileName.isNotEmpty()) {
            download.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.startChatDownload() }) { Text("Download chat model") }
        }

        // ---- where it runs -----------------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (npuOffered) {
                FilterChip(
                    selected = config.llmBackend == Config.BACKEND_NPU,
                    onClick = { vm.updateConfig { it.copy(llmBackend = Config.BACKEND_NPU) } },
                    label = { Text("NPU") },
                )
            }
            FilterChip(
                selected = config.llmBackend == Config.BACKEND_GPU,
                onClick = { vm.updateConfig { it.copy(llmBackend = Config.BACKEND_GPU) } },
                label = { Text("GPU") },
            )
            FilterChip(
                selected = config.llmBackend == Config.BACKEND_CPU || (config.llmBackend == Config.BACKEND_NPU && !npuOffered),
                onClick = { vm.updateConfig { it.copy(llmBackend = Config.BACKEND_CPU) } },
                label = { Text("CPU") },
            )
        }
        Text(
            when {
                npuOffered -> "An NPU build of this model exists for ${npu?.label} and is downloaded when NPU is chosen."
                npu != null -> "No NPU build of this chat model is published for ${npu.label}, so it runs on GPU or CPU. GPU is usually several times faster."
                else -> "This chat model runs on GPU or CPU here. GPU is usually several times faster."
            },
            style = MaterialTheme.typography.bodySmall, color = muted,
        )
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }

    // ---- what it does ----------------------------------------------------------------------
    ToggleRow(
        "Check photos before sorting",
        "The chat model looks at each photo that might fit a category and confirms or rejects it. Takes a few seconds per photo.",
        config.llmVerify, enabled = spec?.vision != false,
    ) { on -> vm.updateConfig { it.copy(llmVerify = on) } }
    ToggleRow(
        "Smart search",
        "After the instant results, the chat model adds concrete things your search could mean and the results are refined.",
        config.llmSearch,
    ) { on -> vm.updateConfig { it.copy(llmSearch = on) } }
    if (stats.chatModelReady && config.llmVerify) {
        OutlinedButton(onClick = { vm.recheckPhotos() }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Check all photos again")
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
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
