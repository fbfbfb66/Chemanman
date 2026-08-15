package com.goings.kaidanzhushou.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppTopBar(
                title = if (selecting) "已选 ${selected.size}" else batch?.name.orEmpty(),
                onBack = if (selecting) ({ selecting = false; selected = emptySet() }) else onBack
            ) {
                if (selecting) {
                    TextButton(onClick = { selecting = false; selected = emptySet() }) {
                        Text("完成", fontWeight = FontWeight.Bold, color = PrimaryBlue)
                    }
                } else {
                    IconButton(
                        onClick = { selecting = true; selected = emptySet() },
                        enabled = records.isNotEmpty()
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "选择照片并删除")
                    }
                }
            }
        },
        bottomBar = {
            if (selecting) {
                Row(Modifier.fillMaxWidth().background(Color.White).padding(14.dp), horizontalArrangement = Arrangement.Center) {
                    Button(
                        onClick = { confirmDelete = true },
                        enabled = selected.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    ) {
                        Icon(Icons.Rounded.Delete, null)
                        Spacer(Modifier.size(6.dp))
                        Text("删除 ${selected.size} 张")
                    }
                }
            } else {
                Column(Modifier.background(Color.White).padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onCamera, enabled = records.size < 100, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.CameraAlt, null)
                            Spacer(Modifier.size(6.dp))
                            Text("连续拍照")
                        }
                        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = records.size < 100, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Image, null)
                            Spacer(Modifier.size(6.dp))
                            Text("相册导入")
                        }
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
                        Spacer(Modifier.size(6.dp))
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
                        AiRecognitionProgressBar(
                            completedCount = completedCount,
                            totalCount = records.size,
                            isPaused = paused,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = onExport,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Icon(Icons.Rounded.FileUpload, null)
                        Spacer(Modifier.size(6.dp))
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
    Box(
        Modifier
            .aspectRatio(.78f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFE5E5EA))
            .clickable(onClick = onClick)
    ) {
        RecognitionThumbnail(record, isProcessing, isWaiting)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(7.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Text(
            record.sourceLabel,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = .58f))
                .padding(horizontal = 7.dp, vertical = 5.dp)
        )
        if (record.blurWarning || record.darknessWarning) {
            Text(
                "画质警告",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Warning)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        if (selecting) {
            Box(Modifier.fillMaxSize().background(if (selected) PrimaryBlue.copy(alpha = .24f) else Color.Black.copy(alpha = .12f))) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = if (selected) PrimaryBlue else Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun RecognitionThumbnail(record: RecordEntity, isProcessing: Boolean, isWaiting: Boolean) {
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = File(record.thumbnailPath ?: record.originalPath),
            contentDescription = record.sourceLabel,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(if (record.rotationDegrees != 0) Modifier.graphicsLayer(rotationZ = record.rotationDegrees.toFloat()) else Modifier)
                .then(if (isProcessing) Modifier.blur(16.dp) else Modifier),
        )

        if (isProcessing) {
            val transition = rememberInfiniteTransition(label = "FrostedGlassShimmer")
            val shimmerProgress by transition.animateFloat(
                initialValue = -1f,
                targetValue = 2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "shimmer"
            )

            // Frosted glass overlay: semi-transparent frost tint with specular edge border
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.32f),
                                Color(0xFFEBF2FF).copy(alpha = 0.18f),
                                Color.White.copy(alpha = 0.28f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.7f),
                                Color.White.copy(alpha = 0.2f),
                                Color.White.copy(alpha = 0.5f)
                            )
                        ),
                        RoundedCornerShape(14.dp)
                    )
            )

            // Dynamic diagonal glass reflection shimmer sweep
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val startX = width * shimmerProgress
                val brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.40f),
                        Color.White.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    start = Offset(startX, 0f),
                    end = Offset(startX + width * 0.75f, height)
                )
                drawRect(brush = brush)
            }

            // Center Frosted Glass AI badge (VisionOS / Liquid Glass capsule)
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x99182230))
                    .border(0.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.8.dp,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
                Text(
                    "识别中",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
            }
        } else if (isWaiting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x80222B3A))
                        .border(0.5.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "排队中",
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun AiRecognitionProgressBar(
    completedCount: Int,
    totalCount: Int,
    isPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    val targetProgress = if (totalCount <= 0) 0f else (completedCount.toFloat() / totalCount).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "smoothProgress"
    )

    val shimmerTransition = rememberInfiniteTransition(label = "ProgressShimmer")
    val shimmerPhase by shimmerTransition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progressShimmer"
    )
    val pulseAlpha by shimmerTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isPaused) Warning else PrimaryBlue.copy(alpha = pulseAlpha))
                )
                Text(
                    text = if (isPaused) "AI 识别已暂停 ($completedCount/$totalCount)"
                    else "AI 正在识别 · 已完成 $completedCount/$totalCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isPaused) Warning else PrimaryBlue,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = PrimaryBlue
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(Color(0xFFE2EAF8))
        ) {
            if (animatedProgress > 0.001f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF4C93FF),
                                    PrimaryBlue,
                                    Color(0xFF1351D8)
                                )
                            )
                        )
                ) {
                    if (!isPaused) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val lightX = w * shimmerPhase
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.65f),
                                        Color.Transparent
                                    ),
                                    startX = lightX,
                                    endX = lightX + w * 0.45f
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
