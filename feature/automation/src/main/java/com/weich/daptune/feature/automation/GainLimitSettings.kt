package com.weich.daptune.feature.automation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.weich.daptune.core.designsystem.AppCard
import com.weich.daptune.core.model.GainLimit
import java.math.BigDecimal

@Composable
internal fun GainLimitSettingsCard(
    limit: GainLimit,
    saving: Boolean,
    onChange: (GainLimit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingMaximum by rememberSaveable { mutableStateOf(false) }
    AppCard(modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text("限制最大增益") },
                supportingContent = { Text("编辑、导入和应用时生效") },
                trailingContent = { Switch(checked = limit.enabled, onCheckedChange = null, enabled = !saving) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.toggleable(
                    value = limit.enabled,
                    role = Role.Switch,
                    enabled = !saving,
                    onValueChange = { onChange(limit.copy(enabled = it)) },
                ),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text("最大增益") },
                supportingContent = { Text("${formatLimitDb(limit.maximumDb)} dB") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(enabled = !saving) { editingMaximum = true },
            )
        }
    }
    if (editingMaximum) {
        var input by rememberSaveable { mutableStateOf(formatLimitDb(limit.maximumDb)) }
        val number = input.replace(',', '.').toDoubleOrNull()
        val parsed = number?.let { runCatching { GainLimit.fromDb(limit.enabled, it) } }
        val selectedLimit = parsed?.getOrNull()
        val error = if (number == null) "请输入有效数值" else parsed?.exceptionOrNull()?.message
        AlertDialog(
            onDismissRequest = { editingMaximum = false },
            title = { Text("最大增益") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        if (value.length <= 18 && value.all { it.isDigit() || it in "+-.," }) input = value
                    },
                    suffix = { Text("dB") },
                    supportingText = {
                        Text(error ?: "实际上限 ${formatLimitDb(requireNotNull(selectedLimit).maximumDb)} dB")
                    },
                    isError = error != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = { TextButton(onClick = { editingMaximum = false }) { Text("取消") } },
            confirmButton = {
                TextButton(enabled = selectedLimit != null && !saving, onClick = {
                    selectedLimit?.let(onChange)
                    editingMaximum = false
                }) { Text("保存") }
            },
        )
    }
}

private fun formatLimitDb(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
