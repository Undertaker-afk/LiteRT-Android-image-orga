package com.imageorga.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imageorga.app.index.Permissions

/** What the setup card and the settings screen can ask the system for. */
class PermissionActions(
    val requestRuntime: () -> Unit,
    val requestBattery: () -> Unit,
    val openAppSettings: () -> Unit,
)

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Root(vm) } }
    }

    override fun onStart() {
        super.onStart()
        vm.onStart()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }

    override fun onStop() {
        super.onStop()
        vm.onStop()
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun Root(vm: AppViewModel) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Category shown full screen: an id, UNSORTED, or null for the list.
    var openCategory by rememberSaveable { mutableStateOf<Long?>(null) }
    var viewerId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val images by vm.images.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val similarTo by vm.similarTo.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshPermissions() }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshPermissions() }
    val actions = remember {
        PermissionActions(
            requestRuntime = { runtimeLauncher.launch(Permissions.runtimePermissions()) },
            requestBattery = {
                val direct = Intent(
                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                try {
                    settingsLauncher.launch(direct)
                } catch (_: Exception) {
                    settingsLauncher.launch(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            openAppSettings = {
                settingsLauncher.launch(
                    Intent(
                        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            },
        )
    }

    BackHandler(enabled = viewerId != null || similarTo != null || openCategory != null || tab != 0) {
        when {
            viewerId != null -> viewerId = null
            similarTo != null -> vm.similarTo.value = null
            openCategory != null -> openCategory = null
            else -> tab = 0
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) }, label = { Text("Search") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1; openCategory = null },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }, label = { Text("Categories") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> SearchScreen(vm, actions, onOpenSettings = { tab = 2 }, onOpenPhoto = { viewerId = it.id })
                1 -> CategoriesScreen(
                    vm, openCategory,
                    onOpenCategory = { openCategory = it },
                    onOpenPhoto = { viewerId = it.id },
                )
                else -> SettingsScreen(vm, actions)
            }
        }
    }

    val shown = viewerId?.let { id -> images.firstOrNull { it.id == id } }
    if (shown != null) {
        PhotoViewer(
            image = shown,
            categories = categories,
            onClose = { viewerId = null },
            onFindSimilar = {
                viewerId = null
                openCategory = null
                tab = 0
                vm.query.value = ""
                vm.similarTo.value = shown
            },
            onMove = { vm.movePhoto(shown, it) },
            onAutoSort = { vm.autoSortPhoto(shown) },
        )
    }
}

/** Marks the "photos in no category" view. */
const val UNSORTED = -1L
