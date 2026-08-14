package com.goings.kaidanzhushou.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goings.kaidanzhushou.data.local.RecordEntity
import com.goings.kaidanzhushou.domain.RecognitionStatus
import com.goings.kaidanzhushou.ui.screens.AppTopBar
import com.goings.kaidanzhushou.ui.theme.AppBackground
import com.goings.kaidanzhushou.ui.theme.ErrorRed
import com.goings.kaidanzhushou.ui.theme.PrimaryBlue
import com.goings.kaidanzhushou.ui.theme.Secondary
import com.goings.kaidanzhushou.ui.theme.Warning

@Composable
fun ProgressScreen(viewModel: MainViewModel, batchId: String, outerPadding: PaddingValues, onBack: () -> Unit, onReview: (String) -> Unit) {
    val batch by viewModel.batch(batchId).collectAsStateWithLifecycle(null)
    val records by viewModel.records(batchId).collectAsStateWithLifecycle(emptyList())
    val completed = records.count { it.recognitionStatus in setOf(RecognitionStatus.PARSED, RecognitionStatus.FAILED) }
    val active = records.any { it.recognitionStatus in setOf(RecognitionStatus.QUEUED, RecognitionStatus.PREPARING, RecognitionStatus.IN_FLIGHT, RecognitionStatus.RETRY_WAIT) }
    androidx.compose.material3.Scaffold(containerColor = AppBackground, topBar = { AppTopBar("AI 识别进度", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(PrimaryBlue), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(batch?.name.orEmpty(), color = Color.White, fontWeight = FontWeight.Bold)
                                Text("已完成 $completed / ${records.size}", color = Color.White.copy(.8f))
                            }
                            Text(if (batch?.recognitionPaused == true) "已暂停" else if (active) "识别中" else "已结束", color = Color.White)
                        }
                        LinearProgressIndicator(progress = { if (records.isEmpty()) 0f else completed.toFloat() / records.size }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), color = Color.White, trackColor = Color.White.copy(.25f))
                    }
                }
            }
            item {
                Button(onClick = { if (active && batch?.recognitionPaused != true) viewModel.pauseRecognition(batchId) else viewModel.startRecognition(batchId, retryFailed = true) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(if (active && batch?.recognitionPaused != true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    Text(if (active && batch?.recognitionPaused != true) "暂停新请求" else "继续/重试")
                }
                Text("暂停后不会提交新请求，已经发出的请求会等待完成。", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            }
            items(records, key = { it.id }) { record -> ProgressRow(record) { onReview(record.id) } }
        }
    }
}

@Composable
private fun ProgressRow(record: RecordEntity, onClick: () -> Unit) {
    val (label, color) = when (record.recognitionStatus) {
        RecognitionStatus.UNRECOGNIZED -> "未识别" to Color.Gray
        RecognitionStatus.QUEUED -> "等待中" to PrimaryBlue
        RecognitionStatus.PREPARING -> "准备图片" to PrimaryBlue
        RecognitionStatus.IN_FLIGHT -> "AI 识别中" to PrimaryBlue
        RecognitionStatus.PARSED -> "待人工核对" to Warning
        RecognitionStatus.RETRY_WAIT -> "等待重试" to Warning
        RecognitionStatus.FAILED -> "识别失败" to ErrorRed
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(record.sourceLabel, fontWeight = FontWeight.SemiBold)
                record.errorMessage?.let { Text(it, color = ErrorRed) }
            }
            Text(label, color = color)
        }
    }
}
