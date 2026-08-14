package com.goings.kaidanzhushou.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goings.kaidanzhushou.data.local.RecordEntity
import com.goings.kaidanzhushou.domain.RecognitionStatus
import com.goings.kaidanzhushou.domain.ReviewStatus
import com.goings.kaidanzhushou.ui.screens.AppTopBar
import com.goings.kaidanzhushou.ui.theme.ErrorRed
import com.goings.kaidanzhushou.ui.theme.PrimaryBlue
import com.goings.kaidanzhushou.ui.theme.Success
import com.goings.kaidanzhushou.ui.theme.Warning
import java.io.File

private enum class RecordFilter(val label: String) { ALL("全部"), UNRECOGNIZED("未识别"), REVIEW("待核对"), CONFIRMED("已确认"), FAILED("失败") }

@Composable
fun BatchScreen(
    viewModel: MainViewModel, batchId: String, outerPadding: PaddingValues, onBack: () -> Unit,
    onCamera: () -> Unit, onProgress: () -> Unit, onReview: (String) -> Unit, onIssues: () -> Unit, onExport: () -> Unit,
) {
    val batch by viewModel.batch(batchId).collectAsStateWithLifecycle(null)
    val records by viewModel.records(batchId).collectAsStateWithLifecycle(emptyList())
    var filter by remember { mutableStateOf(RecordFilter.ALL) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) viewModel.importPhotos(batchId, uris)
    }
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
        topBar = {
            AppTopBar(batch?.name ?: "照片集", onBack) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "重命名") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.DeleteOutline, "删除照片集", tint = ErrorRed) }
            }
        },
        bottomBar = {
            Column(Modifier.background(Color.White).padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCamera, enabled = records.size < 100, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.CameraAlt, null); Text("连续拍照") }
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = records.size < 100, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Image, null); Text("相册导入") }
                }
                Button(onClick = { viewModel.startRecognition(batchId); onProgress() }, enabled = records.any { it.recognitionStatus == RecognitionStatus.UNRECOGNIZED }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null); Text("开始 AI 识别")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onIssues) { Text("问题汇总") }
                    TextButton(onClick = onExport) { Icon(Icons.Rounded.FileUpload, null); Text("检查并导出") }
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
                RecordFilter.entries.forEach { item ->
                    FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item.label, style = MaterialTheme.typography.labelSmall) })
                }
            }
            if (records.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.CameraAlt, null, tint = PrimaryBlue, modifier = Modifier.size(56.dp))
                    Text("拍摄或导入托运单", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text("照片会先保存在本机，不会自动发送给 AI", color = Color.Gray)
                }
            } else LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { record -> PhotoTile(record) { onReview(record.id) } }
                item { Spacer(Modifier.height(170.dp)) }
            }
        }
    }
    if (renaming) BatchNameDialog("重命名照片集", batch?.name.orEmpty(), { renaming = false }) { viewModel.renameBatch(batchId, it); renaming = false }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除照片集？") }, text = { Text("照片、识别结果和本地导出记录将一并删除，此操作无法撤销。") }, confirmButton = {
        TextButton(onClick = { deleting = false; viewModel.deleteBatch(batchId, onBack) }) { Text("确认删除", color = ErrorRed) }
    }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

@Composable
private fun Summary(label: String, value: Int, color: Color) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value.toString(), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    Text(label, color = color.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall)
}

@Composable
fun PhotoTile(record: RecordEntity, onClick: () -> Unit) {
    val statusColor = when {
        record.recognitionStatus == RecognitionStatus.FAILED -> ErrorRed
        record.reviewStatus == ReviewStatus.CONFIRMED -> Success
        record.reviewStatus == ReviewStatus.NEEDS_REVIEW -> Warning
        else -> Color(0xFF9AA3B2)
    }
    Box(Modifier.aspectRatio(.78f).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE9ECF2)).clickable(onClick = onClick)) {
        AsyncImage(model = File(record.thumbnailPath ?: record.originalPath), contentDescription = record.sourceLabel, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(12.dp).clip(CircleShape).background(statusColor))
        Text(record.sourceLabel, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .58f)).padding(7.dp))
        if (record.blurWarning || record.darknessWarning) Text("画质警告", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopStart).background(Warning).padding(horizontal = 5.dp, vertical = 2.dp))
    }
}
