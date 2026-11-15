package com.weich.daptune

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.BluetoothAudio
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weich.daptune.core.designsystem.DapTuneTheme
import com.weich.daptune.feature.about.AboutScreen
import com.weich.daptune.feature.about.AboutUiState
import com.weich.daptune.feature.automation.SettingsScreen
import com.weich.daptune.feature.editor.EditorScreen
import com.weich.daptune.feature.profiles.ProfilesScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DapTuneTheme {
                BluetoothPermissionGate(
                    onPermissionGranted = appViewModel::onBluetoothPermissionGranted,
                ) {
                    LaunchedEffect(Unit) { appViewModel.restoreAutomation() }
                    DapTuneApp(appViewModel)
                }
            }
        }
    }
}

private enum class AppDestination(
    val label: String,
    val outlinedIcon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Editor("调音", Icons.Outlined.Equalizer, Icons.Rounded.Equalizer),
    Profiles("配置", Icons.Outlined.Tune, Icons.Rounded.Tune),
    Settings(
        "设置",
        Icons.Outlined.Settings,
        Icons.Rounded.Settings,
    ),
}

@Composable
private fun DapTuneApp(appViewModel: AppViewModel) {
    val state by appViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { AppDestination.entries.size })

    LaunchedEffect(Unit) {
        appViewModel.checkForUpdatesAutomatically(BuildConfig.VERSION_NAME)
    }

    if (showAbout) {
        AboutScreen(
            versionName = BuildConfig.VERSION_NAME,
            state = AboutUiState(
                automaticUpdateChecksEnabled = state.automaticUpdateChecksEnabled,
                lastUpdateCheckAtEpochMillis = state.lastUpdateCheckAtEpochMillis,
                updateCheckInProgress = state.updateCheckInProgress,
                updateCheckCompleted = state.updateCheckCompleted,
                latestRelease = state.latestRelease,
                updateAvailable = state.updateAvailable,
                updateCheckError = state.updateCheckError,
            ),
            onBack = { showAbout = false },
            onCheckForUpdates = {
                appViewModel.checkForUpdatesNow(BuildConfig.VERSION_NAME)
            },
            onAutomaticUpdateChecksChanged = { enabled ->
                appViewModel.setAutomaticUpdateChecksEnabled(enabled, BuildConfig.VERSION_NAME)
            },
            onOpenRelease = { openUrl(context, it) },
            onOpenProject = { openUrl(context, ProjectUrl) },
            onOpenLicense = { openUrl(context, LicenseUrl) },
            onOpenPrivacyPolicy = { openUrl(context, PrivacyUrl) },
        )
    } else {
        MainPager(pagerState, onOpenAbout = { showAbout = true })
    }

    state.pendingUpdateAnnouncement?.let { release ->
        AlertDialog(
            onDismissRequest = { appViewModel.consumeUpdateAnnouncement(release.tagName) },
            title = { Text("发现新版本 ${release.tagName}") },
            text = { Text("可前往 GitHub Release 查看并下载正式安装包。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        appViewModel.consumeUpdateAnnouncement(release.tagName)
                        openUrl(context, release.releasePageUrl)
                    },
                ) { Text("查看更新") }
            },
            dismissButton = {
                TextButton(
                    onClick = { appViewModel.consumeUpdateAnnouncement(release.tagName) },
                ) { Text("稍后") }
            },
        )
    }
}

@Composable
private fun MainPager(pagerState: PagerState, onOpenAbout: () -> Unit) {
    val destinations = AppDestination.entries
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                destinations.forEachIndexed { index, destination ->
                    val selected = pagerState.currentPage == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        icon = {
                            Icon(
                                if (selected) destination.selectedIcon else destination.outlinedIcon,
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            beyondViewportPageCount = 1,
            key = { destinations[it].name },
        ) {
            when (destinations[it]) {
                AppDestination.Editor -> EditorScreen()
                AppDestination.Profiles -> ProfilesScreen(isActive = pagerState.currentPage == it)
                AppDestination.Settings -> SettingsScreen(onOpenAbout = onOpenAbout)
            }
        }
    }
}

@Composable
private fun BluetoothPermissionGate(
    onPermissionGranted: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionGranted by remember { mutableStateOf(hasBluetoothConnectPermission(context)) }
    var initialRequestStarted by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = hasBluetoothConnectPermission(context)
        if (permissionGranted) onPermissionGranted()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val newlyGranted = hasBluetoothConnectPermission(context)
                if (newlyGranted && !permissionGranted) onPermissionGranted()
                permissionGranted = newlyGranted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(permissionGranted, initialRequestStarted) {
        if (!permissionGranted && !initialRequestStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            initialRequestStarted = true
            launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    if (permissionGranted) {
        content()
    } else {
        BluetoothPermissionScreen(
            onRequestPermission = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
        )
    }
}

@Composable
private fun BluetoothPermissionScreen(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.BluetoothAudio,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(24.dp))
                Text("允许附近的设备", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "用于准确识别当前蓝牙播放设备并切换专属配置。设备地址只在本机哈希处理，不会保存原始值。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Button(onClick = onRequestPermission) { Text("允许") }
                TextButton(onClick = onOpenSettings) { Text("打开系统设置") }
            }
        }
    }
}

private fun hasBluetoothConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private const val ProjectUrl = "https://github.com/silverpoetry/DapTune"
private const val LicenseUrl = "https://github.com/silverpoetry/DapTune/blob/main/LICENSE"
private const val PrivacyUrl = "https://github.com/silverpoetry/DapTune/blob/main/PRIVACY.md"
