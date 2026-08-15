package com.goings.kaidanzhushou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goings.kaidanzhushou.data.local.BatchWithStats
import com.goings.kaidanzhushou.ui.theme.AppBackground
import com.goings.kaidanzhushou.ui.theme.ErrorRed
import com.goings.kaidanzhushou.ui.theme.PrimaryBlue
import com.goings.kaidanzhushou.ui.theme.Secondary
import com.goings.kaidanzhushou.ui.theme.Success as SuccessColor
import com.goings.kaidanzhushou.ui.theme.Warning as WarningColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(viewModel: MainViewModel, outerPadding: PaddingValues, onBatch: (String) -> Unit, onSettings: () -> Unit) {
    val batches by viewModel.batches.collectAsStateWithLifecycle(emptyList())
    var deleteTarget by remember { mutableStateOf<BatchWithStats?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmSelectedDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    androidx.compose.material3.Scaffold(
        containerColor = AppBackground,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 4.dp, bottom = 6.dp, start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    Text("已选 ${selected.size} 个", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { confirmSelectedDelete = true }) { Icon(Icons.Rounded.Delete, "删除所选照片集", tint = ErrorRed) }
                    TextButton(onClick = { selected = emptySet() }) { Text("完成") }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text("开单助手", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("照片识别 · 人工核对 · 标准导出", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "设置") }
                }
            }
        },
        floatingActionButton = {
            if (!selecting) {
                FloatingActionButton(onClick = { viewModel.createDatedBatch(onBatch) }, containerColor = PrimaryBlue, contentColor = Color.White) {
                    Icon(Icons.Rounded.Add, "新建照片集")
                }
            }
        },
    ) { padding ->
        if (batches.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.PhotoLibrary, null, tint = PrimaryBlue, modifier = Modifier.height(64.dp))
                Spacer(Modifier.height(18.dp))
                Text("还没有照片集", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("点击右下角新建照片集", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(batches, key = { it.id }) { batch ->
                SwipeBatchCard(
                    batch = batch,
                    selecting = selecting,
                    selected = batch.id in selected,
                    onClick = {
                        if (selecting) selected = if (batch.id in selected) selected - batch.id else selected + batch.id
                        else onBatch(batch.id)
                    },
                    onLongClick = { selected = selected + batch.id },
                    onDelete = { deleteTarget = batch },
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    deleteTarget?.let { batch ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除 ${batch.name}？") }, text = { Text("将删除其中 ${batch.recordCount} 张照片。") }, confirmButton = {
            TextButton(onClick = { deleteTarget = null; viewModel.deleteBatch(batch.id) {} }) { Text("删除", color = ErrorRed) }
        }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    }
    if (confirmSelectedDelete) {
        val targets = batches.filter { it.id in selected }
        AlertDialog(
            onDismissRequest = { confirmSelectedDelete = false },
            title = { Text("删除 ${targets.size} 个照片集？") },
            text = { Text("将同时删除其中 ${targets.sumOf { it.recordCount }} 张照片。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSelectedDelete = false
                    viewModel.deleteBatches(selected) { selected = emptySet() }
                }) { Text("确认删除", color = ErrorRed) }
            },
            dismissButton = { TextButton(onClick = { confirmSelectedDelete = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeBatchCard(
    batch: BatchWithStats,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (!selecting && value == SwipeToDismissBoxValue.EndToStart) onDelete()
        false
    })
    SwipeToDismissBox(state = state, enableDismissFromStartToEnd = false, enableDismissFromEndToStart = !selecting, backgroundContent = {
        Box(Modifier.fillMaxSize().background(ErrorRed, RoundedCornerShape(20.dp)).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
            Icon(Icons.Rounded.Delete, "删除", tint = Color.White)
        }
    }) {
        Card(
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(if (selected) Color(0xFFEAF1FF) else Color.White),
            elevation = CardDefaults.cardElevation(1.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(batch.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (selecting) Icon(Icons.Rounded.CheckCircle, null, tint = if (selected) PrimaryBlue else Secondary, modifier = Modifier.padding(end = 8.dp))
                    Text("${batch.recordCount}/100", color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
                }
                Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(batch.updatedAt)), color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("待核对", batch.needsReviewCount, Color(0xFFFFF3E5), WarningColor, Modifier.weight(1f))
                    Stat("已确认", batch.confirmedCount, Color(0xFFE9F7F0), SuccessColor, Modifier.weight(1f))
                    Stat("失败", batch.failedCount, Color(0xFFFFECEC), ErrorRed, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, count: Int, bg: Color, fg: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(bg), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), fontWeight = FontWeight.Bold, color = fg)
            Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
        }
    }
}
