package com.fgogotran.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * App 内置调试日志查看器。
 *
 * 日志由 [FgoLogger] 始终写入内存环形缓冲（不依赖 logcat 开关），
 * 这里每秒刷新展示，支持一键复制全部、导出 TXT 和清空，
 * 方便没有 adb 环境的用户把日志直接发给开发者排查问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var lines by remember { mutableStateOf(FgoLogger.dumpBuffer()) }
    var actionMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            lines = FgoLogger.dumpBuffer()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调试日志") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("返回", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "日志始终在 App 内记录（与 logcat 开关无关）。进入剧情/触发朗读后回到本页查看，或直接复制全部发给我。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        val text = FgoLogger.dumpBuffer().joinToString("\n")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("FgoGotran 调试日志", text))
                        actionMessage = if (text.isBlank()) "日志为空" else "已复制 ${text.lineCount()} 行"
                    }
                ) {
                    Text("复制全部")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val file = writeLogFile(context, FgoLogger.dumpBuffer())
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "FgoGotran 调试日志")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "分享调试日志"))
                            }.onSuccess {
                                actionMessage = "已生成 TXT"
                            }.onFailure {
                                actionMessage = "导出失败"
                            }
                        }
                    },
                    enabled = lines.isNotEmpty()
                ) {
                    Text("导出 TXT")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        FgoLogger.clearBuffer()
                        lines = emptyList()
                        actionMessage = "已清空"
                    },
                    enabled = lines.isNotEmpty()
                ) {
                    Text("清空")
                }
            }

            if (actionMessage.isNotBlank()) {
                Text(
                    actionMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (lines.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        "暂无日志。请先触发操作（如测试语音、进入剧情）后再回来看。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                    )
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        text = lines.joinToString("\n"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

private fun writeLogFile(context: Context, lines: List<String>): File {
    val dir = File(context.cacheDir, "logs").apply { mkdirs() }
    val file = File(dir, "fgogotran_debug_${System.currentTimeMillis()}.txt")
    file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    return file
}
