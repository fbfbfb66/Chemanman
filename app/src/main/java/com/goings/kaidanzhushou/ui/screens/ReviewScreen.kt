package com.goings.kaidanzhushou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goings.kaidanzhushou.data.local.RecordEntity
import com.goings.kaidanzhushou.domain.EditableFields
import com.goings.kaidanzhushou.domain.PaymentType
import com.goings.kaidanzhushou.domain.RecordValidator
import com.goings.kaidanzhushou.ui.screens.AppTopBar
import com.goings.kaidanzhushou.ui.theme.PrimaryBlue
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun ReviewScreen(viewModel: MainViewModel, batchId: String, recordId: String, outerPadding: PaddingValues, onBack: () -> Unit) {
    val records by viewModel.records(batchId).collectAsStateWithLifecycle(emptyList())
    var activeRecordId by remember(recordId) { mutableStateOf(recordId) }
    val record = records.firstOrNull { it.id == activeRecordId }
    val index = records.indexOfFirst { it.id == activeRecordId }
    var fields by remember { mutableStateOf(EditableFields()) }
    var initializedId by remember { mutableStateOf<String?>(null) }
    var split by remember { mutableFloatStateOf(.56f) }
    val formState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var invalidField by remember(activeRecordId) { mutableStateOf<String?>(null) }
    var focusAttempt by remember(activeRecordId) { mutableIntStateOf(0) }
    LaunchedEffect(record?.id) {
        record?.let { if (initializedId != it.id) { fields = it.editable(); initializedId = it.id } }
    }
    fun switchRecord(offset: Int): Boolean {
        val nextIndex = index + offset
        if (index < 0 || nextIndex !in records.indices) return false
        record?.let { viewModel.updateRecord(it.id, fields, changedFields(it, fields)) }
        activeRecordId = records[nextIndex].id
        return true
    }
    androidx.compose.material3.Scaffold(
        topBar = { AppTopBar(if (index >= 0) "核对 ${index + 1}/${records.size}" else "人工核对", onBack) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { record?.let { viewModel.updateRecord(it.id, fields, changedFields(it, fields)) } }, modifier = Modifier.weight(1f)) { Text("保存草稿") }
                Button(onClick = {
                    record?.let { current ->
                        val firstIssue = RecordValidator.validate(fields).firstOrNull()
                        if (firstIssue == null) {
                            viewModel.saveAndConfirm(current.id, fields, changedFields(current, fields), onBack)
                        } else {
                            viewModel.updateRecord(current.id, fields, changedFields(current, fields))
                            invalidField = firstIssue.field
                            focusAttempt += 1
                            scope.launch { formState.animateScrollToItem(formItemIndex(firstIssue.field)) }
                        }
                    }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Check, null)
                    Text("确认无误")
                }
            }
        },
    ) { padding ->
        record?.let { current ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(state = formState, modifier = Modifier.fillMaxWidth().weight(split), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Field(
                            "到站 *", fields.destinationText.orEmpty(),
                            onValue = { fields = fields.copy(destinationText = it); if (invalidField == "destination_text") invalidField = null },
                            isError = invalidField == "destination_text",
                            selectRequest = if (invalidField == "destination_text") focusAttempt else 0,
                        )
                    }
                    item {
                        Text("配送方式 *", fontWeight = FontWeight.SemiBold, color = if (invalidField == "delivery_type") com.goings.kaidanzhushou.ui.theme.ErrorRed else Color.Unspecified)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(fields.deliveryType == "delivery", { fields = fields.copy(deliveryType = "delivery"); invalidField = null }, { Text("送货") })
                            FilterChip(fields.deliveryType == "pickup", { fields = fields.copy(deliveryType = "pickup"); invalidField = null }, { Text("自提") })
                        }
                    }
                    item {
                        Text("付款方式 *", fontWeight = FontWeight.SemiBold, color = if (invalidField == "payment_type") com.goings.kaidanzhushou.ui.theme.ErrorRed else Color.Unspecified)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaymentType.entries.forEach { payment ->
                                FilterChip(fields.paymentType == payment.code, { fields = fields.copy(paymentType = payment.code); invalidField = null }, { Text(payment.label) })
                            }
                        }
                    }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("发货人 *", fields.senderName.orEmpty(), { fields = fields.copy(senderName = it); if (invalidField == "sender_name") invalidField = null }, Modifier.weight(1f), isError = invalidField == "sender_name", selectRequest = if (invalidField == "sender_name") focusAttempt else 0)
                        Field("收货人 *", fields.receiverName.orEmpty(), { fields = fields.copy(receiverName = it); if (invalidField == "receiver_name") invalidField = null }, Modifier.weight(1f), isError = invalidField == "receiver_name", selectRequest = if (invalidField == "receiver_name") focusAttempt else 0)
                    } }
                    item { Field("收货手机号", fields.receiverMobile.orEmpty(), { fields = fields.copy(receiverMobile = it) }, keyboard = KeyboardType.Phone) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("货物名称 *", fields.goodsName.orEmpty(), { fields = fields.copy(goodsName = it); if (invalidField == "goods_name") invalidField = null }, Modifier.weight(1f), isError = invalidField == "goods_name", selectRequest = if (invalidField == "goods_name") focusAttempt else 0)
                        Field("包装", fields.packageName.orEmpty(), { fields = fields.copy(packageName = it) }, Modifier.weight(1f))
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("件数 *", fields.quantity?.toString().orEmpty(), { fields = fields.copy(quantity = it.toIntOrNull()); if (invalidField == "quantity") invalidField = null }, Modifier.weight(1f), KeyboardType.Number, invalidField == "quantity", if (invalidField == "quantity") focusAttempt else 0)
                        Field("重量", fields.weight?.plain().orEmpty(), { fields = fields.copy(weight = it.toDoubleOrNull()); if (invalidField == "weight") invalidField = null }, Modifier.weight(1f), KeyboardType.Decimal, invalidField == "weight", if (invalidField == "weight") focusAttempt else 0)
                        Field("体积", fields.volume?.plain().orEmpty(), { fields = fields.copy(volume = it.toDoubleOrNull()); if (invalidField == "volume") invalidField = null }, Modifier.weight(1f), KeyboardType.Decimal, invalidField == "volume", if (invalidField == "volume") focusAttempt else 0)
                    } }
                    item { Field("运费", fields.freight?.plain().orEmpty(), { fields = fields.copy(freight = it.toDoubleOrNull()); if (invalidField == "freight") invalidField = null }, keyboard = KeyboardType.Decimal, isError = invalidField == "freight", selectRequest = if (invalidField == "freight") focusAttempt else 0) }
                }
                Box(Modifier.fillMaxWidth().height(18.dp).background(Color(0xFFE7EAF0)).pointerInput(Unit) {
                    detectVerticalDragGestures { _, amount -> split = (split + amount / 1200f).coerceIn(.35f, .75f) }
                }) { Box(Modifier.align(Alignment.Center).fillMaxWidth(.18f).height(4.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF9AA3B2))) }
                Box(Modifier.fillMaxWidth().weight(1f - split)) {
                    ZoomableImage(
                        file = File(current.originalPath),
                        position = if (index >= 0) "${index + 1}/${records.size}" else "",
                        onSwipeLeft = { switchRecord(1) },
                        onSwipeRight = { switchRecord(-1) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun IosSegments(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFE5E5EA)).padding(2.dp)) {
        options.forEach { (value, label) ->
            Surface(modifier = Modifier.weight(1f).clickable { onSelect(value) }, shape = RoundedCornerShape(8.dp), color = if (selected == value) Color.White else Color.Transparent, shadowElevation = if (selected == value) 1.dp else 0.dp) {
                Text(label, modifier = Modifier.padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (selected == value) Color.Black else Color.DarkGray)
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    selectRequest: Int = 0,
) {
    val focusRequester = remember { FocusRequester() }
    var editor by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (value != editor.text) editor = TextFieldValue(value, TextRange(value.length))
    }
    LaunchedEffect(selectRequest) {
        if (selectRequest > 0) {
            focusRequester.requestFocus()
            editor = editor.copy(selection = TextRange(0, editor.text.length))
        }
    }
    OutlinedTextField(
        value = editor,
        onValueChange = { next -> editor = next; onValue(next.text) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}

@Composable
private fun ZoomableImage(
    file: File,
    position: String,
    onSwipeLeft: () -> Boolean,
    onSwipeRight: () -> Boolean,
    modifier: Modifier,
) {
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var x by remember(file) { mutableFloatStateOf(0f) }
    var y by remember(file) { mutableFloatStateOf(0f) }
    var swipeDistance by remember(file) { mutableFloatStateOf(0f) }
    var swipeLocked by remember(file) { mutableStateOf(false) }
    var quarterTurns by remember(file) { mutableIntStateOf(0) }
    val rotation by animateFloatAsState(targetValue = quarterTurns * 90f, label = "照片旋转")
    Box(modifier.background(Color(0xFF161618)).clipToBounds().pointerInput(file) {
        detectTransformGestures { _, pan, zoom, _ ->
            val nextScale = (scale * zoom).coerceIn(1f, 5f)
            if (scale <= 1.02f && nextScale <= 1.02f && zoom in .98f..1.02f) {
                swipeDistance += pan.x
                if (!swipeLocked && abs(swipeDistance) >= 110f) {
                    val switched = if (swipeDistance < 0) onSwipeLeft() else onSwipeRight()
                    if (switched) { swipeLocked = true; swipeDistance = 0f }
                    else swipeDistance = swipeDistance.coerceIn(-109f, 109f)
                }
            } else {
                scale = nextScale
                x += pan.x
                y += pan.y
                swipeDistance = 0f
                if (scale == 1f) { x = 0f; y = 0f }
            }
        }
    }) {
        AsyncImage(
            model = file,
            contentDescription = "托运单照片",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = x,
                translationY = y,
                rotationZ = rotation,
            ),
        )
        IconButton(
            onClick = {
                quarterTurns += 1
                scale = 1f
                x = 0f
                y = 0f
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = .62f)),
        ) {
            Icon(Icons.Rounded.RotateRight, contentDescription = "顺时针旋转照片", tint = Color.White)
        }
        Text("左右滑动切换  $position", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(.62f), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

private fun formItemIndex(field: String): Int = when (field) {
    "destination_text" -> 0
    "delivery_type" -> 1
    "payment_type" -> 2
    "sender_name", "receiver_name" -> 3
    "receiver_mobile" -> 4
    "goods_name", "package" -> 5
    "quantity", "weight", "volume" -> 6
    "freight" -> 7
    else -> 0
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
    if (old.paymentType != now.paymentType) add("payment_type")
}
