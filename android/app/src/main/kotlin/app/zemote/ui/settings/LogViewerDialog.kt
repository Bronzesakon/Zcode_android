package app.zemote.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.zemote.log.ZLog
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「运行日志」查看器：文件列表 + 最近 800 行 + 导出/分享。
 *
 * 用全屏 Dialog 而不是新路由：路由表（`NavState.Route`）不在本轮改动范围，而日志入口
 * 只是设置页的一个临时浮层。导出走 SAF（用户任选位置），分享走 FileProvider +
 * `ACTION_SEND` —— 两条路都不需要电脑，真机上直接能把日志发出去。
 */
@Composable
fun LogViewerDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val colors = LocalZemoteColors.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var files by remember { mutableStateOf(ZLog.files()) }
        var selectedName by remember {
            mutableStateOf(
                files.firstOrNull { it.name == "zemote.log" }?.name ?: files.firstOrNull()?.name,
            )
        }
        var content by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(true) }
        var reload by remember { mutableStateOf(0) }

        LaunchedEffect(selectedName, reload) {
            loading = true
            content = withContext(Dispatchers.IO) {
                val dir = ZLog.logDirectory()
                val file = selectedName?.let { name -> dir?.let { File(it, name) } }
                if (file != null && file.isFile) ZLog.readTail(file, 800) else ZLog.snapshot(800)
            }
            loading = false
        }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { ZLog.exportTo(context, uri) }
                    toast(context, if (ok) "日志已导出" else "导出失败")
                }
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "运行日志",
                        modifier = Modifier.weight(1f),
                        style = ZemoteType.uiLg,
                        fontWeight = FontWeight.Medium,
                        color = colors.foreground,
                    )
                    TextButton(onClick = onDismiss) {
                        Text("关闭", style = ZemoteType.uiSm, color = colors.foregroundSubtle)
                    }
                }
                Text(
                    text = "只保存在本机，用于定位闪退/重载；导出前请自行确认内容。",
                    style = ZemoteType.uiXs,
                    color = colors.foregroundSubtle,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val file = withContext(Dispatchers.IO) { ZLog.prepareShareFile() }
                                if (file == null) {
                                    toast(context, "生成日志包失败")
                                } else {
                                    shareLogFile(context, file)
                                }
                            }
                        },
                    ) { Text("分享", style = ZemoteType.uiSm) }

                    Button(onClick = { exportLauncher.launch("zemote-log.txt") }) {
                        Text("导出", style = ZemoteType.uiSm)
                    }

                    Button(
                        onClick = {
                            files = ZLog.files()
                            if (selectedName == null) selectedName = files.firstOrNull()?.name
                            reload += 1
                        },
                    ) { Text("刷新", style = ZemoteType.uiSm) }
                }
                Spacer(Modifier.height(10.dp))

                if (files.isEmpty()) {
                    Text("暂无日志文件", style = ZemoteType.uiSm, color = colors.foregroundSubtle)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(files, key = { it.name }) { file ->
                            FilterChip(
                                selected = file.name == selectedName,
                                onClick = { selectedName = file.name },
                                label = {
                                    Text(
                                        text = "${file.name} · ${sizeLabel(file.length())}",
                                        style = ZemoteType.uiXs,
                                    )
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = colors.border)
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                } else {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        SelectionContainer {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 8.dp),
                            ) {
                                Text(
                                    text = content.ifBlank { "（空）" },
                                    style = ZemoteType.mono,
                                    color = colors.foreground,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun shareLogFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Zemote 运行日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享运行日志")
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onFailure {
        toast(context, "分享失败：${it.message ?: it.javaClass.simpleName}")
    }
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
