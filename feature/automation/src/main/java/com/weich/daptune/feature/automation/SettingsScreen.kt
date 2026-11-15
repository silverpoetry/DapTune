package com.weich.daptune.feature.automation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothAudio
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SettingsInputHdmi
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weich.daptune.core.designsystem.AppCard
import com.weich.daptune.core.designsystem.DapTuneTopAppBar
import com.weich.daptune.core.model.EqProfile
import com.weich.daptune.core.model.KnownOutputDevice
import com.weich.daptune.core.model.OutputRouteIdentityKind
import com.weich.daptune.core.model.OutputRouteType
import com.weich.daptune.core.model.OperationLogAction
import com.weich.daptune.core.model.OperationLogEntry
import com.weich.daptune.core.model.OperationLogOutcome
import com.weich.daptune.core.model.VerificationState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface ProfilePickerTarget {
    data object Default : ProfilePickerTarget
    data class Device(val device: KnownOutputDevice) : ProfilePickerTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    onOpenAbout: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    var pickerTarget by remember { mutableStateOf<ProfilePickerTarget?>(null) }
    var forgetTarget by remember { mutableStateOf<KnownOutputDevice?>(null) }
    var showOperationLogs by rememberSaveable { mutableStateOf(false) }
    var enableAfterNotificationPermission by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
        if (enableAfterNotificationPermission) viewModel.setEnabled(true)
        enableAfterNotificationPermission = false
    }

    fun setAutomation(enabled: Boolean) {
        val notificationPermissionMissing =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (enabled && notificationPermissionMissing) {
            enableAfterNotificationPermission = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setEnabled(enabled)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect(snackbar::showSnackbar)
    }

    BackHandler(enabled = showOperationLogs) { showOperationLogs = false }

    if (showOperationLogs) {
        OperationLogScreen(
            logs = state.operationLogs,
            onBack = { showOperationLogs = false },
            onClear = viewModel::clearOperationLogs,
            modifier = modifier,
        )
        return
    }

    val visibleDevices = remember(state.devices, state.currentRoute) {
        val current = state.devices.firstOrNull { it.route.key == state.currentRoute.key }
            ?: KnownOutputDevice(state.currentRoute, lastSeenAtEpochMillis = 0L)
        listOf(current) + state.devices.filterNot { it.route.key == current.route.key }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            DapTuneTopAppBar(
                title = "设置",
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeading("均衡器") }
            item {
                GainLimitSettingsCard(
                    limit = state.settings.gainLimit,
                    saving = state.gainLimitSaving,
                    onChange = viewModel::setGainLimit,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item { SectionHeading("自动切换", modifier = Modifier.padding(top = 4.dp)) }
            item {
                AppCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                ) {
                    Column {
                        ListItem(
                            headlineContent = { Text("按播放设备切换") },
                            supportingContent = { Text("播放设备变化时应用对应配置") },
                            leadingContent = {
                                Icon(Icons.Outlined.Tune, contentDescription = null)
                            },
                            trailingContent = {
                                Switch(
                                    checked = state.settings.automationEnabled,
                                    onCheckedChange = null,
                                )
                            },
                            modifier = Modifier.toggleable(
                                value = state.settings.automationEnabled,
                                role = Role.Switch,
                                onValueChange = ::setAutomation,
                            ),
                            colors = transparentListItemColors(),
                        )
                        SettingsDivider()
                        ListItem(
                            headlineContent = { Text("重启后恢复自动切换") },
                            supportingContent = { Text("设备重启后无需打开应用即可继续运行") },
                            leadingContent = {
                                Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null)
                            },
                            trailingContent = {
                                Switch(
                                    checked = state.settings.applyAtBoot,
                                    onCheckedChange = null,
                                )
                            },
                            modifier = Modifier.toggleable(
                                value = state.settings.applyAtBoot,
                                role = Role.Switch,
                                onValueChange = viewModel::setApplyAtBoot,
                            ),
                            colors = transparentListItemColors(),
                        )
                    }
                }
            }

            item { SectionHeading("规则", modifier = Modifier.padding(top = 4.dp)) }
            item {
                AppCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text("默认配置") },
                        supportingContent = { Text("没有专属规则时使用") },
                        leadingContent = {
                            Icon(Icons.Outlined.Tune, contentDescription = null)
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.defaultProfile?.name ?: "平直",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                            }
                        },
                        modifier = Modifier.clickable {
                            pickerTarget = ProfilePickerTarget.Default
                        },
                        colors = transparentListItemColors(),
                    )
                }
            }

            item { SectionHeading("播放设备", modifier = Modifier.padding(top = 4.dp)) }
            item {
                AppCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                ) {
                    Column {
                        visibleDevices.forEachIndexed { index, device ->
                            val assigned = state.profileFor(device.route.key)
                            val canConfigure =
                                device.route.identityKind == OutputRouteIdentityKind.PERSISTENT
                            DeviceRuleRow(
                                device = device,
                                assignedProfile = assigned,
                                defaultProfile = state.defaultProfile,
                                isCurrent = device.route.key == state.currentRoute.key,
                                canForget = device.route.key != state.currentRoute.key &&
                                    device.route.type != OutputRouteType.BUILT_IN_SPEAKER,
                                canConfigure = canConfigure,
                                onClick = {
                                    if (canConfigure) {
                                        pickerTarget = ProfilePickerTarget.Device(device)
                                    }
                                },
                                onForgetRequest = { forgetTarget = device },
                            )
                            if (index != visibleDevices.lastIndex) SettingsDivider()
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "系统音效若覆盖曲线，下一次播放设备事件会重新应用规则。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionHeading("记录", modifier = Modifier.padding(top = 4.dp)) }
            item {
                AppCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text("操作记录") },
                        supportingContent = {
                            Text(
                                text = state.operationLogs.firstOrNull()?.let(::operationLogSummary)
                                    ?: "暂无记录",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.History, contentDescription = null)
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (state.operationLogs.isNotEmpty()) {
                                    Text(
                                        text = state.operationLogs.size.toString(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                            }
                        },
                        modifier = Modifier.clickable { showOperationLogs = true },
                        colors = transparentListItemColors(),
                    )
                }
            }

            item { SectionHeading("应用", modifier = Modifier.padding(top = 4.dp)) }
            item {
                AppCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text("关于 DapTune") },
                        supportingContent = { Text("版本、更新与开源信息") },
                        leadingContent = {
                            Icon(Icons.Outlined.Info, contentDescription = null)
                        },
                        trailingContent = {
                            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable(onClick = onOpenAbout),
                        colors = transparentListItemColors(),
                    )
                }
            }
        }
    }

    pickerTarget?.let { target ->
        val selectedId = when (target) {
            ProfilePickerTarget.Default -> state.settings.defaultProfileId
            is ProfilePickerTarget.Device -> state.profileFor(target.device.route.key)?.id
        }
        ProfileSelectionDialog(
            title = when (target) {
                ProfilePickerTarget.Default -> "默认配置"
                is ProfilePickerTarget.Device -> target.device.route.displayName
            },
            profiles = state.profiles,
            selectedId = selectedId,
            allowFollowDefault = target is ProfilePickerTarget.Device,
            defaultProfileName = state.defaultProfile?.name ?: "平直",
            onDismiss = { pickerTarget = null },
            onSelect = { profileId ->
                when (target) {
                    ProfilePickerTarget.Default -> profileId?.let(viewModel::setDefaultProfile)
                    is ProfilePickerTarget.Device -> viewModel.bind(target.device.route.key, profileId)
                }
                pickerTarget = null
            },
        )
    }

    forgetTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("忘记设备？") },
            text = {
                Text("将从设备历史中移除“${device.route.displayName}”及其自动切换规则。再次连接后，它会作为新设备重新出现。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forget(device.route.key, device.route.displayName)
                        forgetTarget = null
                    },
                ) {
                    Text(
                        text = "忘记",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OperationLogScreen(
    logs: List<OperationLogEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("操作记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { confirmClear = true },
                        enabled = logs.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空记录")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        if (logs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "暂无记录",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 20.dp,
                ),
            ) {
                items(
                    items = logs,
                    key = OperationLogEntry::id,
                ) { entry ->
                    OperationLogRow(entry)
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空操作记录？") },
            text = { Text("现有记录将被永久删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear()
                        confirmClear = false
                    },
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun OperationLogRow(entry: OperationLogEntry) {
    val failed = entry.outcome == OperationLogOutcome.FAILURE
    ListItem(
        headlineContent = {
            Text(
                text = operationLogTitle(entry),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = operationLogDetail(entry)?.let { detail ->
            {
                Text(
                    text = detail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (failed) {
                    Icons.Outlined.ErrorOutline
                } else {
                    Icons.Outlined.CheckCircleOutline
                },
                contentDescription = null,
                tint = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = LogDateFormatter.format(
                        Instant.ofEpochMilli(entry.occurredAtEpochMillis)
                            .atZone(ZoneId.systemDefault()),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = LogTimeFormatter.format(
                        Instant.ofEpochMilli(entry.occurredAtEpochMillis)
                            .atZone(ZoneId.systemDefault()),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

private fun operationLogSummary(entry: OperationLogEntry): String =
    "${LogCompactFormatter.format(Instant.ofEpochMilli(entry.occurredAtEpochMillis).atZone(ZoneId.systemDefault()))} · " +
        operationLogTitle(entry)

private fun operationLogTitle(entry: OperationLogEntry): String {
    val profile = entry.profileName?.let { " · $it" }.orEmpty()
    val base = when (entry.action) {
        OperationLogAction.UNKNOWN -> "未知操作"
        OperationLogAction.AUTOMATION_ENABLED -> "开启自动切换"
        OperationLogAction.AUTOMATION_DISABLED -> "关闭自动切换"
        OperationLogAction.START_AT_BOOT_ENABLED -> "启用重启后恢复"
        OperationLogAction.START_AT_BOOT_DISABLED -> "停用重启后恢复"
        OperationLogAction.AUTOMATION_STARTED -> "启动恢复$profile"
        OperationLogAction.AUTOMATION_RECOVERED -> "后台恢复$profile"
        OperationLogAction.AUTOMATION_START_FAILED -> "自动切换启动"
        OperationLogAction.ROUTE_CHANGED -> "设备切换$profile"
        OperationLogAction.DOLBY_RESTORED -> "杜比状态恢复$profile"
        OperationLogAction.AUTOMATION_REFRESHED -> "重新应用$profile"
        OperationLogAction.PROFILE_SELECTED -> "切换配置$profile"
        OperationLogAction.DEFAULT_RULE_CHANGED -> "默认配置$profile"
        OperationLogAction.DEVICE_RULE_CHANGED ->
            if (entry.profileName == null) "设备规则 · 跟随默认" else "设备规则$profile"
        OperationLogAction.CURVE_APPLIED -> "应用当前曲线"
        OperationLogAction.DEVICE_FORGOTTEN -> "忘记设备"
    }
    return if (entry.outcome == OperationLogOutcome.FAILURE && !base.endsWith("失败")) {
        "${base}失败"
    } else {
        base
    }
}

private fun operationLogDetail(entry: OperationLogEntry): String? = buildList {
    entry.routeName?.let(::add)
    when (entry.verification) {
        VerificationState.VERIFIED -> add("回读验证通过")
        VerificationState.WRITE_ACCEPTED -> add("写入已接受")
        VerificationState.STALE -> add("状态待确认")
        VerificationState.FAILED -> add("验证失败")
        null -> Unit
    }
    entry.detail?.takeIf(String::isNotBlank)?.let(::add)
}.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")

private val LogDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val LogTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val LogCompactFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

@Composable
private fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun DeviceRuleRow(
    device: KnownOutputDevice,
    assignedProfile: EqProfile?,
    defaultProfile: EqProfile?,
    isCurrent: Boolean,
    canForget: Boolean,
    canConfigure: Boolean,
    onClick: () -> Unit,
    onForgetRequest: () -> Unit,
) {
    val ruleLabel = if (canConfigure) {
        assignedProfile?.name ?: "跟随默认 · ${defaultProfile?.name ?: "平直"}"
    } else {
        "无法验证设备身份"
    }
    var menuExpanded by remember(device.route.key) { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.route.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "当前",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = ruleLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                routeIcon(device.route.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            if (!canConfigure) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = "设备身份无法验证",
                    tint = MaterialTheme.colorScheme.error,
                )
            } else if (canForget) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "设备操作",
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("忘记设备") },
                            onClick = {
                                menuExpanded = false
                                onForgetRequest()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            } else {
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
        },
        modifier = Modifier.clickable(enabled = canConfigure, onClick = onClick),
        colors = transparentListItemColors(),
    )
}

@Composable
private fun ProfileSelectionDialog(
    title: String,
    profiles: List<EqProfile>,
    selectedId: String?,
    allowFollowDefault: Boolean,
    defaultProfileName: String,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 344.dp),
            ) {
                items(
                    items = profiles,
                    key = EqProfile::id,
                ) { profile ->
                    ProfileChoiceRow(
                        name = profile.name,
                        selected = selectedId == profile.id,
                        onClick = { onSelect(profile.id) },
                    )
                }
                if (allowFollowDefault) {
                    item { HorizontalDivider() }
                    item {
                        ProfileChoiceRow(
                            name = "跟随默认 · $defaultProfileName",
                            selected = selectedId == null,
                            onClick = { onSelect(null) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun ProfileChoiceRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun transparentListItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

private fun routeIcon(type: OutputRouteType): ImageVector = when (type) {
    OutputRouteType.BUILT_IN_SPEAKER -> Icons.Outlined.Speaker
    OutputRouteType.WIRED_HEADSET -> Icons.Outlined.Headphones
    OutputRouteType.BLUETOOTH,
    OutputRouteType.BLE_AUDIO,
    -> Icons.Outlined.BluetoothAudio
    OutputRouteType.USB -> Icons.Outlined.Usb
    OutputRouteType.HDMI -> Icons.Outlined.SettingsInputHdmi
    OutputRouteType.REMOTE -> Icons.Outlined.Smartphone
    OutputRouteType.UNKNOWN -> Icons.Outlined.Speaker
}
