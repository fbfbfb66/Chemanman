package com.goings.kaidanzhushou.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goings.kaidanzhushou.data.local.RecordEntity
import com.goings.kaidanzhushou.domain.RecognitionStatus
import com.goings.kaidanzhushou.domain.ReviewStatus
import com.goings.kaidanzhushou.ui.screens.AppTopBar
import com.goings.kaidanzhushou.ui.theme.AppBackground
import com.goings.kaidanzhushou.ui.theme.ErrorRed
import com.goings.kaidanzhushou.ui.theme.PrimaryBlue
import com.goings.kaidanzhushou.ui.theme.Secondary
import com.goings.kaidanzhushou.ui.theme.Success
import com.goings.kaidanzhushou.ui.theme.Warning
import java.io.File

private enum class RecordFilter(val label: String) { ALL("全部"), UNRECOGNIZED("未识别"), REVIEW("待核对"), CONFIRMED("已确认"), FAILED("失败") }

@Composable
fun BatchScreen(
    viewModel: MainViewModel, batchId: String, outerPadding: PaddingValues, onBack: () -> Unit,
    onCamera: () -> Unit, onReview: (String) -> Unit, onExport: () -> Unit,
) {
    val batch by viewModel.batch(batchId).collectAsStateWithLifecycle(null)
    val records by viewModel.records(batchId).collectAsStateWithLifecycle(emptyList())
    var filter by remember { mutableStateOf(RecordFilter.ALL) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val activeStatuses = setOf(RecognitionStatus.QUEUED, RecognitionStatus.PREPARING, RecognitionStatus.IN_FLIGHT, RecognitionStatus.RETRY_WAIT)
    val recognizing = records.any { it.recognitionStatus in activeStatuses }
    val paused = batch?.recognitionPaused == true
    val completedCount = records.count { it.recognitionStatus == RecognitionStatus.PARSED || it.recognitionStatus == RecognitionStatus.FAILED }
    val canStartRecognition = records.any { it.recognitionStatus == RecognitionStatus.UNRECOGNIZED || it.recognitionStatus == RecognitionStatus.FAILED }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris -> if (uris.isNotEmpty()) viewModel.importPhotos(batchId, uris) }
    val shown = records.filter {
        when (filter) {
            RecordFilter.ALL -> true
            RecordFilter.UNRECOGNIZED -> it.recognitionStatus == RecognitionStatus.UNRECOGNIZED
            RecordFilter.REVIEW -> it.reviewStatus == ReviewStatus.NEEDS_REVIEW
            RecordFilter.CONFIRMED -> it.reviewStatus == ReviewStatus.CONFIRMED
            RecordFilter.FAILED -> it.recognitionStatus == RecognitionStatus.FAILED
        }
    }
    androidx.compose.material3.Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppTopBar(if (selecting) "已选 ${selected.size}" else batch?.name.orEmpty(), onBack = if (selecting) ({ selecting = false; selected = emptySet() }) else onBack) {
                TextButton(onClick = { selecting = !selecting; selected = emptySet() }) { Text(if (selecting) "完成" else "选择") }
            }
        },
        bottomBar = {
            if (selecting) Row(Modifier.fillMaxWidth().background(Color.White).padding(14.dp), horizontalArrangement = Arrangement.Center) {
                Button(onClick = { confirmDelete = true }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.Delete, null); Text("删除 ${selected.size} 张") }
            } else Column(Modifier.background(Color.White).padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCamera, enabled = records.size < 100, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.CameraAlt, null); Text("连续拍照") }
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = records.size < 100, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Image, null); Text("相册导入") }
                }
                Button(
                    onClick = {
                        if (recognizing && !paused) viewModel.pauseRecognition(batchId)
                        else viewModel.startRecognition(batchId, retryFailed = true)
                    },
                    enabled = recognizing || canStartRecognition,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Icon(if (recognizing && !paused) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    Text(
                        when {
                            recognizing && !paused -> "AI 识别中  $completedCount/${records.size}"
                            recognizing && paused -> "继续 AI 识别"
                            records.any { it.recognitionStatus == RecognitionStatus.FAILED } && records.none { it.recognitionStatus == RecognitionStatus.UNRECOGNIZED } -> "重试失败照片"
                            else -> "开始 AI 识别"
                        },
                    )
                }
                if (recognizing) {
                    LinearProgressIndicator(
                        progress = { if (records.isEmpty()) 0f else completedCount.toFloat() / records.size },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth(.62f)) {
                        Icon(Icons.Rounded.FileUpload, null)
                        Text("检查并导出")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), colors = CardDefaults.cardColors(PrimaryBlue), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Summary("照片", records.size, Color.White)
                    Summary("待核对", records.count { it.reviewStatus == ReviewStatus.NEEDS_REVIEW }, Color.White)
                    Summary("已确认", records.count { it.reviewStatus == ReviewStatus.CONFIRMED }, Color.White)
                    Summary("上限", 100, Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                RecordFilter.entries.forEach { item -> FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item.label) }) }
            }
            if (records.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.CameraAlt, null, tint = PrimaryBlue, modifier = Modifier.size(56.dp))
                    Text("拍摄或导入托运单", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text("照片会先保存在本机，不会自动发送给 AI", color = Color.Gray)
                }
            } else LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { record ->
                    PhotoTile(record, selecting, record.id in selected) {
                        if (selecting) selected = if (record.id in selected) selected - record.id else selected + record.id else onReview(record.id)
                    }
                }
                item { Spacer(Modifier.height(150.dp)) }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除 ${selected.size} 张照片？") }, confirmButton = {
        TextButton(onClick = { confirmDelete = false; viewModel.deleteRecords(batchId, selected) { selecting = false; selected = emptySet() } }) { Text("删除", color = ErrorRed) }
    }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@Composable
private fun Summary(label: String, value: Int, color: Color) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value.toString(), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    Text(label, color = color.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall)
}

@Composable
fun PhotoTile(record: RecordEntity, selecting: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    val isProcessing = record.recognitionStatus == RecognitionStatus.PREPARING || record.recognitionStatus == RecognitionStatus.IN_FLIGHT
    val isWaiting = record.recognitionStatus == RecognitionStatus.QUEUED || record.recognitionStatus == RecognitionStatus.RETRY_WAIT
    val statusColor = when {
        record.recognitionStatus == RecognitionStatus.FAILED -> ErrorRed
        isProcessing || isWaiting -> PrimaryBlue
        record.reviewStatus == ReviewStatus.CONFIRMED -> Success
        record.reviewStatus == ReviewStatus.NEEDS_REVIEW -> Warning
        else -> Color(0xFF9AA3B2)
    }
    Box(Modifier.aspectRatio(.78f).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE5E5EA)).clickable(onClick = onClick)) {
        RecognitionThumbnail(record, isProcessing, isWaiting)
        Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(12.dp).clip(CircleShape).background(statusColor))
        Text(record.sourceLabel, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .58f)).padding(7.dp))
        if (record.blurWarning || record.darknessWarning) Text("画质警告", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopStart).background(Warning).padding(horizontal = 5.dp, vertical = 2.dp))
        if (selecting) Box(Modifier.fillMaxSize().background(if (selected) PrimaryBlue.copy(alpha = .24f) else Color.Black.copy(alpha = .12f))) {
            Icon(Icons.Rounded.CheckCircle, null, tint = if (selected) PrimaryBlue else Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
    }
}

@Composable
private fun RecognitionThumbnail(record: RecordEntity, isProcessing: Boolean, isWaiting: Boolean) {
    if (!isProcessing) {
        AsyncImage(
            model = File(record.thumbnailPath ?: record.originalPath),
            contentDescription = record.sourceLabel,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isWaiting) Box(Modifier.fillMaxSize().background(PrimaryBlue.copy(alpha = .10f)))
        return
    }

    val transition = rememberInfiniteTransition(label = "AI 识别呼吸")
    val blurRadius by transition.animateFloat(
        initialValue = 1.5f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(animation = tween(1_050), repeatMode = RepeatMode.Reverse),
        label = "模糊",
    )
    val scale by transition.animateFloat(
        initialValue = 1.01f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(animation = tween(1_050), repeatMode = RepeatMode.Reverse),
        label = "缩放",
    )
    val glow by transition.animateFloat(
        initialValue = .08f,
        targetValue = .28f,
        animationSpec = infiniteRepeatable(animation = tween(1_050), repeatMode = RepeatMode.Reverse),
        label = "蓝光",
    )
    AsyncImage(
        model = File(record.thumbnailPath ?: record.originalPath),
        contentDescription = record.sourceLabel,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale).blur(blurRadius.dp),
    )
    Box(Modifier.fillMaxSize().background(PrimaryBlue.copy(alpha = glow)))
}
