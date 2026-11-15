package com.weich.daptune.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.weich.daptune.core.eq.CurveExportDocument
import com.weich.daptune.core.eq.CurveExportFormat
import com.weich.daptune.core.eq.CurveFileCodec
import com.weich.daptune.core.model.EqCurve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Retains the chosen curve snapshot while the system file picker owns the Activity. */
@Composable
fun rememberCurveExporter(onMessage: (String) -> Unit): (String, EqCurve, CurveExportFormat) -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val report by rememberUpdatedState(onMessage)
    var pendingText by rememberSaveable { mutableStateOf<String?>(null) }
    var writing by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(remember { CreateCurveDocument() }) { uri ->
        val text = pendingText
        pendingText = null
        if (uri != null && text != null) {
            writing = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val stream = resolver.openOutputStream(uri, "wt") ?: error("无法写入文件")
                        stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    }
                    report("已导出")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    report("导出失败：${error.message ?: "无法写入文件"}")
                } finally {
                    writing = false
                }
            }
        }
    }
    return export@{ name, curve, format ->
        if (pendingText != null || writing) return@export
        try {
            val document = CurveFileCodec.exportDocument(name, curve, format)
            pendingText = document.text
            launcher.launch(document)
        } catch (error: Exception) {
            pendingText = null
            report("无法导出：${error.message ?: "无法打开文件选择器"}")
        }
    }
}

@Composable
fun CurveExportDialog(onDismiss: () -> Unit, onSelect: (CurveExportFormat) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出曲线") },
        text = {
            Column {
                CurveExportFormat.entries.forEach { format ->
                    ListItem(
                        headlineContent = {
                            Text(when (format) {
                                CurveExportFormat.DAPTUNE_JSON -> "DapTune JSON"
                                CurveExportFormat.GRAPHIC_EQ -> "GraphicEQ 文本"
                            })
                        },
                        modifier = Modifier.clickable { onSelect(format) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private class CreateCurveDocument : ActivityResultContract<CurveExportDocument, Uri?>() {
    override fun createIntent(context: Context, input: CurveExportDocument): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}
