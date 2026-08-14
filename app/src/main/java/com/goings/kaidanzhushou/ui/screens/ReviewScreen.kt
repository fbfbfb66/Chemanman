package com.goings.kaidanzhushou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goings.kaidanzhushou.data.local.RecordEntity
import com.goings.kaidanzhushou.domain.EditableFields
import com.goings.kaidanzhushou.ui.screens.AppTopBar
import com.goings.kaidanzhushou.ui.theme.PrimaryBlue
import com.goings.kaidanzhushou.ui.theme.Warning
import java.io.File

@Composable
fun ReviewScreen(viewModel: MainViewModel, batchId: String, recordId: String, outerPadding: PaddingValues, onBack: () -> Unit) {
    val record by viewModel.record(recordId).collectAsStateWithLifecycle(null)
    val records by viewModel.records(batchId).collectAsStateWithLifecycle(emptyList())
    val index = records.indexOfFirst { it.id == recordId }
    var fields by remember { mutableStateOf(EditableFields()) }
    var initializedId by remember { mutableStateOf<String?>(null) }
    var split by remember { mutableFloatStateOf(.56f) }
    LaunchedEffect(record?.id) {
        record?.let { if (initializedId != it.id) { fields = it.editable(); initializedId = it.id } }
    }
    androidx.compose.material3.Scaffold(
        topBar = { AppTopBar(if (index >= 0) "核对 ${index + 1}/${records.size}" else "人工核对", onBack) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { record?.let { viewModel.updateRecord(it.id, fields, changedFields(it, fields)) } }, modifier = Modifier.weight(1f)) { Text("保存草稿") }
                Button(onClick = { record?.let { current -> viewModel.saveAndConfirm(current.id, fields, changedFields(current, fields), onBack) } }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Check, null); Text("确认无误") }
            }
        },
    ) { padding ->
        record?.let { current ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(Modifier.fillMaxWidth().weight(split), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (current.uncertainFields.isNotBlank() || current.errorMessage != null || current.blurWarning || current.darknessWarning) item {
                        Card(colors = CardDefaults.cardColors(Color(0xFFFFF4E8)), shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.WarningAmber, null, tint = Warning)
                                Text(current.errorMessage ?: listOfNotNull(
                                    current.uncertainFields.takeIf(String::isNotBlank)?.let { "AI 不确定：$it" },
                                    if (current.blurWarning) "照片可能模糊" else null,
                                    if (current.darknessWarning) "照片可能过暗" else null,
                                ).joinToString("；"), modifier = Modifier.padding(start = 8.dp), color = Warning)
                            }
                        }
                    }
                    item { Field("目的地 *", fields.destinationText.orEmpty(), onValue = { fields = fields.copy(destinationText = it) }) }
                    item {
                        Text("配送方式 *", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(fields.deliveryType == "delivery", { fields = fields.copy(deliveryType = "delivery") }, { Text("送货") })
                            FilterChip(fields.deliveryType == "pickup", { fields = fields.copy(deliveryType = "pickup") }, { Text("自提") })
                        }
                    }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("发货人 *", fields.senderName.orEmpty(), { fields = fields.copy(senderName = it) }, Modifier.weight(1f)); Field("收货人 *", fields.receiverName.orEmpty(), { fields = fields.copy(receiverName = it) }, Modifier.weight(1f)) } }
                    item { Field("收货手机号", fields.receiverMobile.orEmpty(), { fields = fields.copy(receiverMobile = it) }, keyboard = KeyboardType.Phone) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("货物名称 *", fields.goodsName.orEmpty(), { fields = fields.copy(goodsName = it) }, Modifier.weight(1f)); Field("包装", fields.packageName.orEmpty(), { fields = fields.copy(packageName = it) }, Modifier.weight(1f)) } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("件数 *", fields.quantity?.toString().orEmpty(), { fields = fields.copy(quantity = it.toIntOrNull()) }, Modifier.weight(1f), KeyboardType.Number)
                        Field("重量", fields.weight?.plain().orEmpty(), { fields = fields.copy(weight = it.toDoubleOrNull()) }, Modifier.weight(1f), KeyboardType.Decimal)
                        Field("体积", fields.volume?.plain().orEmpty(), { fields = fields.copy(volume = it.toDoubleOrNull()) }, Modifier.weight(1f), KeyboardType.Decimal)
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("运费", fields.freight?.plain().orEmpty(), { fields = fields.copy(freight = it.toDoubleOrNull()) }, Modifier.weight(1f), KeyboardType.Decimal)
                        OutlinedTextField(value = "现付", onValueChange = {}, readOnly = true, label = { Text("付款方式") }, modifier = Modifier.weight(1f))
                    } }
                }
                Box(Modifier.fillMaxWidth().height(18.dp).background(Color(0xFFE7EAF0)).pointerInput(Unit) {
                    detectVerticalDragGestures { _, amount -> split = (split + amount / 1200f).coerceIn(.35f, .75f) }
                }) { Box(Modifier.align(Alignment.Center).fillMaxWidth(.18f).height(4.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF9AA3B2))) }
                ZoomableImage(File(current.originalPath), Modifier.fillMaxWidth().weight(1f - split))
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = modifier.fillMaxWidth())
}

@Composable
private fun ZoomableImage(file: File, modifier: Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    Box(modifier.background(Color(0xFF161A20)).clipToBounds().pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); x += pan.x; y += pan.y }
    }) {
        AsyncImage(model = file, contentDescription = "托运单原图", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = x, translationY = y))
        Text("双指缩放 · 拖动中间横条调整比例", color = Color.White.copy(.75f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(.55f)).padding(5.dp))
    }
}

private fun Double.plain() = if (this % 1.0 == 0.0) toLong().toString() else toString()

private fun changedFields(old: RecordEntity, now: EditableFields): Set<String> = buildSet {
    if (old.destinationText != now.destinationText) add("destination_text")
    if (old.deliveryType != now.deliveryType) add("delivery_type")
    if (old.senderName != now.senderName) add("sender_name")
    if (old.receiverName != now.receiverName) add("receiver_name")
    if (old.receiverMobile != now.receiverMobile) add("receiver_mobile")
    if (old.goodsName != now.goodsName) add("goods_name")
    if (old.packageName != now.packageName) add("package")
    if (old.quantity != now.quantity) add("quantity")
    if (old.weight != now.weight) add("weight")
    if (old.volume != now.volume) add("volume")
    if (old.freight != now.freight) add("freight")
}
