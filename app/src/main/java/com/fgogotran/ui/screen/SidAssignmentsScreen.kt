package com.fgogotran.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fgogotran.data.SettingsRepository
import com.fgogotran.voice.AiVoiceService
import com.fgogotran.voice.SherpaOnnxModelRegistry
import com.fgogotran.voice.SherpaSpeakerMappings
import com.fgogotran.voice.SidRoleGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 全屏查看当前模型的内置角色分配：每个音色编号下的完整角色列表，可逐个试听。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidAssignmentsScreen(
    settingsRepository: SettingsRepository,
    sherpaOnnxModelRegistry: SherpaOnnxModelRegistry,
    sherpaSpeakerMappings: SherpaSpeakerMappings,
    aiVoiceService: AiVoiceService,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var modelName by remember { mutableStateOf("") }
    var sidGroups by remember { mutableStateOf<List<SidRoleGroup>>(emptyList()) }
    var previewingSid by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val inst = sherpaOnnxModelRegistry.selectedInstalled()
        if (inst != null) {
            modelName = inst.manifest.displayName
            sidGroups = sherpaSpeakerMappings.builtinAssignmentsBySid(inst.manifest.modelId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("内置角色分配") },
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
                .padding(16.dp)
        ) {
            Text(
                "当前模型：$modelName",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
            if (sidGroups.isEmpty()) {
                Text(
                    "当前模型没有内置角色分配表（仅 fanchen-C / zh-ll / Kokoro 提供），角色音色由 AI 自动分配。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sidGroups, key = { it.sid }) { group ->
                        SidGroupRow(
                            group = group,
                            previewing = previewingSid == group.sid,
                            onPreview = {
                                if (previewingSid != null) return@SidGroupRow
                                scope.launch {
                                    previewingSid = group.sid
                                    message = ""
                                    try {
                                        aiVoiceService.playSherpaSidPreview(
                                            group.sid,
                                            "你好，我是${group.names.first()}。"
                                        )
                                        message = "已播放音色 #${group.sid}"
                                        messageIsError = false
                                    } catch (e: Throwable) {
                                        message = "试听失败：${e.message.orEmpty().take(96)}"
                                        messageIsError = true
                                    } finally {
                                        previewingSid = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidGroupRow(
    group: SidRoleGroup,
    previewing: Boolean,
    onPreview: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "音色 #${group.sid}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onPreview, enabled = !previewing) {
                    Text(if (previewing) "合成中…" else "试听")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                group.names.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
        }
    }
}
