package com.goings.kaidanzhushou.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goings.kaidanzhushou.domain.ReviewStatus
import com.goings.kaidanzhushou.ui.screens.AppTopBar
import com.goings.kaidanzhushou.ui.theme.PrimaryBlue
import com.goings.kaidanzhushou.ui.theme.Success
import com.goings.kaidanzhushou.ui.theme.Warning
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExportScreen(viewModel: MainViewModel, batchId: String, outerPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val batch by viewModel.batch(batchId).collectAsStateWithLifecycle(null)
    val records by viewModel.records(batchId).collectAsStateWithLifecycle(emptyList())
    val exports by viewModel.exports(batchId).collectAsStateWithLifecycle(emptyList())
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        val file = pendingSave
        if (uri != null && file != null) context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
        pendingSave = null
    }
    val confirmed = records.count { it.reviewStatus == ReviewStatus.CONFIRMED }
    val ready = records.isNotEmpty() && confirmed == records.size
    androidx.compose.material3.Scaffold(topBar = { AppTopBar("检查并导出", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(if (ready) Color(0xFFE9F7F0) else Color(0xFFFFF3E5)), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = if (ready) Success else Warning)
                            Text(if (ready) "可以导出" else "尚不能导出", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                        Text("已人工确认 $confirmed / ${records.size} 条", modifier = Modifier.padding(top = 8.dp))
                        Text(if (ready) "将生成 Schema v1.0、工作表“导入数据”的 16 列标准 XLSX。" else "请返回问题汇总，确认所有纳入记录。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Button(onClick = { viewModel.export(batchId) { file -> pendingSave = file; saveLauncher.launch(file.name) } }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.FileDownload, null); Text("生成并保存 XLSX")
                }
                Text("生成后会保留一份 App 内部副本；系统文件选择器决定另存位置。", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            if (exports.isNotEmpty()) item { Text("导出记录", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(exports, key = { it.id }) { export ->
                Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(export.fileName, fontWeight = FontWeight.SemiBold)
                            Text("${export.recordCount} 条 · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(export.exportedAt))}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { shareFile(context, File(export.localPath)) }) { Icon(Icons.Rounded.Share, "分享"); Text("分享") }
                    }
                }
            }
        }
    }
}

private fun shareFile(context: android.content.Context, file: File) {
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "分享 Excel"))
}

@Composable
fun SettingsScreen(viewModel: MainViewModel, outerPadding: PaddingValues, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(viewModel.hasApiKey()) }
    androidx.compose.material3.Scaffold(topBar = { AppTopBar("设置", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SettingsCard(Icons.Rounded.Key, "Kimi API Key") {
                    Text(if (hasKey) "已在本机加密保存" else "尚未配置", color = if (hasKey) Success else Warning)
                    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(if (hasKey) "输入新 Key 可覆盖" else "sk-…") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.saveApiKey(key); key = ""; hasKey = true }, enabled = key.isNotBlank(), modifier = Modifier.weight(1f)) { Text("安全保存") }
                        OutlinedButton(onClick = { viewModel.clearApiKey(); hasKey = false }, enabled = hasKey, modifier = Modifier.weight(1f)) { Text("清除") }
                    }
                }
            }
            item {
                SettingsCard(Icons.Rounded.Storage, "本地存储") {
                    Text("原图、识别结果和 Excel 永不自动删除。删除照片集时会二次确认并取消识别任务。", color = Color.Gray)
                    Text("照片仅存于 App 私有目录；相册导入和 Excel 另存均使用系统选择器。", color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                SettingsCard(Icons.Rounded.Lock, "隐私与网络") {
                    Text("Key 使用 Android Keystore AES-256-GCM 加密，应用数据备份已关闭。", color = Color.Gray)
                    Text("只有点击“开始 AI 识别”后，最长边约 2200px 的上传副本才会发送到 Kimi 中国区 API。日志不会记录 Key、原图、姓名、手机号或完整 AI 响应。", color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item { Text("开单助手 1.0.0 · Schema v1.0", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
        }
    }
}

@Composable
private fun SettingsCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = PrimaryBlue); Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
