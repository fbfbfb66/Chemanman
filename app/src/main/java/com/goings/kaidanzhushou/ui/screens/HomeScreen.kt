package com.goings.kaidanzhushou.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.goings.kaidanzhushou.ui.theme.PrimaryBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(viewModel: MainViewModel, outerPadding: PaddingValues, onBatch: (String) -> Unit, onSettings: () -> Unit) {
    val batches by viewModel.batches.collectAsStateWithLifecycle(emptyList())
    var creating by remember { mutableStateOf(false) }
    androidx.compose.material3.Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(top = outerPadding.calculateTopPadding() + 14.dp, start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("开单助手", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("照片识别 · 人工核对 · 标准导出", color = Color.Gray)
                }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "设置") }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }, containerColor = PrimaryBlue, contentColor = Color.White) {
                Icon(Icons.Rounded.Add, "新建照片集")
            }
        },
    ) { padding ->
        if (batches.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.PhotoLibrary, null, tint = PrimaryBlue, modifier = Modifier.height(64.dp))
                Spacer(Modifier.height(18.dp))
                Text("还没有照片集", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("新建照片集后，可连续拍摄或批量导入最多 100 张托运单", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(batches, key = { it.id }) { BatchCard(it) { onBatch(it.id) } }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    if (creating) BatchNameDialog("新建照片集", "例如：8月14日托运单", onDismiss = { creating = false }) { name ->
        creating = false
        viewModel.createBatch(name, onBatch)
    }
}

@Composable
private fun BatchCard(batch: BatchWithStats, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(batch.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${batch.recordCount}/100", color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
            }
            Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(batch.updatedAt)), color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("待核对", batch.needsReviewCount, Color(0xFFFFF3E5), Color(0xFFF2921A), Modifier.weight(1f))
                Stat("已确认", batch.confirmedCount, Color(0xFFE9F7F0), Color(0xFF159E5E), Modifier.weight(1f))
                Stat("失败", batch.failedCount, Color(0xFFFFECEC), Color(0xFFD92E2E), Modifier.weight(1f))
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

@Composable
fun BatchNameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("照片集名称") }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("确定") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
