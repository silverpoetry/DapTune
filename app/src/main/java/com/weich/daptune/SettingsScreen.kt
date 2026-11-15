package com.weich.daptune

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.weich.daptune.core.designsystem.AppCard
import com.weich.daptune.core.model.GainLimit
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: AppUiState,
    onChange: (GainLimit) -> Unit,
    onErrorShown: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val snackbar = remember { SnackbarHostState() }
    var editingMaximum by rememberSaveable { mutableStateOf(false) }
    val limit = state.gainLimit
    LaunchedEffect(state.gainLimitError) {
        state.gainLimitError?.let {
            snackbar.showSnackbar(it)
            onErrorShown()
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("均衡器", style = MaterialTheme.typography.labelLarge) }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Column {
                        ListItem(
                            headlineContent = { Text("限制最大增益") },
                            supportingContent = { Text("编辑、导入和应用时生效") },
                            trailingContent = { Switch(checked = limit.enabled, onCheckedChange = null, enabled = !state.gainLimitSaving) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.toggleable(
                                value = limit.enabled,
                                role = Role.Switch,
                                enabled = !state.gainLimitSaving,
                                onValueChange = { onChange(limit.copy(enabled = it)) },
                            ),
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("最大增益") },
                            supportingContent = { Text("${formatLimitDb(limit.maximumDb)} dB") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !state.gainLimitSaving) { editingMaximum = true },
                        )
                    }
                }
            }
            item {
                Text(
                    "应用侧限制，不代表设备上限。修改设置不会改写已有配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
    if (editingMaximum) {
        var input by rememberSaveable { mutableStateOf(formatLimitDb(limit.maximumDb)) }
        val number = input.replace(',', '.').toDoubleOrNull()
        val parsed = number?.let { runCatching { GainLimit.fromDb(limit.enabled, it) } }
        val error = if (number == null) "请输入有效数值" else parsed?.exceptionOrNull()?.message
        AlertDialog(
            onDismissRequest = { editingMaximum = false },
            title = { Text("最大增益") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 18 && it.all { char -> char.isDigit() || char in "+-.," }) input = it },
                    suffix = { Text("dB") },
                    supportingText = { Text(error ?: "实际上限 ${formatLimitDb(parsed!!.getOrThrow().maximumDb)} dB") },
                    isError = error != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = { TextButton(onClick = { editingMaximum = false }) { Text("取消") } },
            confirmButton = {
                TextButton(enabled = error == null, onClick = {
                    parsed?.getOrNull()?.let(onChange)
                    editingMaximum = false
                }) { Text("保存") }
            },
        )
    }
}

private fun formatLimitDb(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
