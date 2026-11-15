package com.weich.daptune.feature.profiles

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weich.daptune.core.designsystem.AppCard
import com.weich.daptune.core.designsystem.CurveExportDialog
import com.weich.daptune.core.designsystem.rememberCurveExporter
import com.weich.daptune.core.designsystem.CurveSparkline
import com.weich.daptune.core.designsystem.DapTuneTopAppBar
import com.weich.daptune.core.designsystem.formatGain
import com.weich.daptune.core.eq.CurveFileCodec
import com.weich.daptune.core.eq.CurveImportFormat
import com.weich.daptune.core.model.EqProfile
import com.weich.daptune.domain.runSuspendCatching
import java.io.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val autoEqState by viewModel.autoEqState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<EqProfile?>(null) }
    var exporting by remember { mutableStateOf<EqProfile?>(null) }
    val export = rememberCurveExporter { message -> scope.launch { snackbar.showSnackbar(message) } }
    var importFormat by remember { mutableStateOf(CurveImportFormat.AUTOMATIC) }
    var showingAutoEq by rememberSaveable { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runSuspendCatching {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val text = resolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use(Reader::readImportText)
                        ?: error("无法读取文件")
                    val name = resolver.displayName(uri)
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: "导入配置"
                    text to name
                }
            }.onSuccess { (text, name) -> viewModel.importText(text, name, importFormat) }
                .onFailure { snackbar.showSnackbar(it.message ?: "无法读取文件") }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect(snackbar::showSnackbar)
    }

    if (showingAutoEq) {
        AutoEqSearchScreen(
            state = autoEqState,
            snackbarHostState = snackbar,
            onQueryChange = viewModel::updateAutoEqQuery,
            onRetry = viewModel::retryAutoEqSearch,
            onImport = viewModel::importAutoEq,
            onBack = { showingAutoEq = false },
            isActive = isActive,
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            DapTuneTopAppBar(
                title = "配置",
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeading("导入")
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                ImportSourcesCard(
                    onOpenAutoEq = { showingAutoEq = true },
                    onImportFile = { format ->
                        importFormat = format
                        fileLauncher.launch(arrayOf("*/*"))
                    },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeading("我的配置", modifier = Modifier.padding(top = 8.dp))
            }
            if (state.userProfiles.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            Text("还没有自定义配置", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "在调音页保存曲线，或从上方导入。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(
                    items = state.userProfiles,
                    key = EqProfile::id,
                ) { profile ->
                    ProfileCard(
                        profile = profile,
                        selected = profile.id == state.selectedProfileId,
                        onClick = { viewModel.select(profile) },
                        onDuplicate = { viewModel.duplicate(profile) },
                        onExport = { exporting = profile },
                        onDelete = { deleting = profile },
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeading("内置", modifier = Modifier.padding(top = 8.dp))
            }
            items(
                items = state.builtIns,
                key = EqProfile::id,
            ) { profile ->
                ProfileCard(
                    profile = profile,
                    selected = profile.id == state.selectedProfileId,
                    onClick = { viewModel.select(profile) },
                    onDuplicate = { viewModel.duplicate(profile) },
                    onExport = { exporting = profile },
                    onDelete = null,
                )
            }
        }
    }

    exporting?.let { profile ->
        CurveExportDialog(
            onDismiss = { exporting = null },
            onSelect = { format ->
                export(profile.name, profile.curve, format)
                exporting = null
            },
        )
    }

    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除“${profile.name}”？") },
            text = { Text("使用此配置的设备将改为跟随默认配置。") },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(profile)
                        deleting = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun ImportSourcesCard(
    onOpenAutoEq: () -> Unit,
    onImportFile: (CurveImportFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    var formatMenuExpanded by remember { mutableStateOf(false) }
    val listColors = ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
    AppCard(modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text("AutoEq") },
                supportingContent = { Text("搜索官方推荐配置") },
                leadingContent = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                },
                colors = listColors,
                modifier = Modifier.clickable(onClick = onOpenAutoEq),
            )
            HorizontalDivider(Modifier.padding(start = 56.dp))
            Box(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("从文件导入") },
                    supportingContent = { Text("JSON、GraphicEQ、ParametricEQ、CSV") },
                    leadingContent = {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                    },
                    colors = listColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { formatMenuExpanded = true },
                )
                DropdownMenu(
                    expanded = formatMenuExpanded,
                    onDismissRequest = { formatMenuExpanded = false },
                ) {
                    CurveImportFormat.entries.forEach { format ->
                        DropdownMenuItem(
                            text = { Text(format.displayName()) },
                            onClick = {
                                formatMenuExpanded = false
                                onImportFile(format)
                            },
                        )
                        if (format == CurveImportFormat.AUTOMATIC) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private fun CurveImportFormat.displayName(): String = when (this) {
    CurveImportFormat.AUTOMATIC -> "自动识别"
    CurveImportFormat.DAPTUNE_JSON -> "DapTune 配置（JSON）"
    CurveImportFormat.GRAPHIC_EQ -> "GraphicEQ（Wavelet / AutoEq）"
    CurveImportFormat.PARAMETRIC_EQ -> "ParametricEQ（AutoEq / EAPO）"
    CurveImportFormat.FREQUENCY_GAIN_TABLE -> "CSV / TSV 频率－增益表"
}

private fun ContentResolver.displayName(uri: Uri): String? = query(
    uri,
    arrayOf(OpenableColumns.DISPLAY_NAME),
    null,
    null,
    null,
)?.use { cursor ->
    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    cursor.takeIf { nameColumn >= 0 && it.moveToFirst() }
        ?.getString(nameColumn)
        ?.takeIf(String::isNotBlank)
}

private fun Reader.readImportText(): String {
    val output = StringBuilder()
    val buffer = CharArray(8_192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toString()
        if (output.length + count > CurveFileCodec.MAX_IMPORT_CHARACTERS) {
            throw IllegalArgumentException("文件过大")
        }
        output.append(buffer, 0, count)
    }
}

@Composable
private fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProfileCard(
    profile: EqProfile,
    selected: Boolean,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        Text(
                            text = "当前",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("导出") },
                            leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("复制") },
                            leadingIcon = {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                        )
                        onDelete?.let { delete ->
                            DropdownMenuItem(
                                text = { Text("删除") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    delete()
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            CurveSparkline(
                curve = profile.curve,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp)
                    .padding(end = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = curveRange(profile),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun curveRange(profile: EqProfile): String {
    val gains = profile.curve.toQ4List()
    val minimum = gains.minOrNull() ?: 0
    val maximum = gains.maxOrNull() ?: 0
    return if (minimum == maximum) {
        "${formatGain(minimum)} dB"
    } else {
        "${formatGain(minimum)} — ${formatGain(maximum)} dB"
    }
}
