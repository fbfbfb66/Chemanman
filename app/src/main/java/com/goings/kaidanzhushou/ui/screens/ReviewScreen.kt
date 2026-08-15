package com.goings.kaidanzhushou.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goings.kaidanzhushou.data.local.RecordEntity
import com.goings.kaidanzhushou.data.local.GoodsProfileEntity
import com.goings.kaidanzhushou.data.local.ReceiverProfileEntity
import com.goings.kaidanzhushou.domain.AssociationFields
import com.goings.kaidanzhushou.domain.DestinationDictionary
import com.goings.kaidanzhushou.domain.AssociationMatcher
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
    val receiverProfiles by viewModel.receiverProfiles.collectAsStateWithLifecycle(emptyList())
    val goodsProfiles by viewModel.goodsProfiles.collectAsStateWithLifecycle(emptyList())
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
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(record?.id) {
        record?.let { if (initializedId != it.id) { fields = it.editable(); initializedId = it.id } }
    }

    LaunchedEffect(initializedId, receiverProfiles, goodsProfiles) {
        val current = record ?: return@LaunchedEffect
        if (initializedId != current.id) return@LaunchedEffect
        fields = resolveExactAssociations(fields, current, receiverProfiles, goodsProfiles)
    }

    fun switchRecord(offset: Int): Boolean {
        val nextIndex = index + offset
        if (index < 0 || nextIndex !in records.indices) return false
        focusManager.clearFocus()
        keyboardController?.hide()
        record?.let { viewModel.updateRecord(it.id, fields, changedFields(it, fields)) }
        activeRecordId = records[nextIndex].id
        return true
    }

    Scaffold(
        topBar = { AppTopBar(if (index >= 0) "核对 ${index + 1}/${records.size}" else "人工核对", onBack) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    record?.let { viewModel.updateRecord(it.id, fields, changedFields(it, fields)) }
                }, modifier = Modifier.weight(1f)) { Text("保存草稿") }
                Button(onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    record?.let { current ->
                        val associationIssue = when {
                            !fields.receiverAssociationResolved -> AssociationFields.RECEIVER
                            !fields.goodsAssociationResolved -> AssociationFields.GOODS
                            else -> null
                        }
                        val firstIssue = RecordValidator.validate(fields).firstOrNull()
                        if (associationIssue == null && firstIssue == null) {
                            viewModel.saveAndConfirm(current.id, fields, changedFields(current, fields), onBack)
                        } else {
                            viewModel.updateRecord(current.id, fields, changedFields(current, fields))
                            invalidField = associationIssue ?: firstIssue?.field
                            focusAttempt += 1
                            val issueField = associationIssue ?: firstIssue?.field ?: return@let
                            viewModel.showWarning(if (issueField == AssociationFields.RECEIVER) "请选择正确的收货人" else if (issueField == AssociationFields.GOODS) "请选择正确的货物" else firstIssue?.message.orEmpty())
                            scope.launch { formState.animateScrollToItem(formItemIndex(issueField)) }
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                    .screenSwipeToSwitch(
                        edgeMarginPx = with(density) { 32.dp.toPx() },
                        dragThresholdPx = with(density) { 42.dp.toPx() },
                        minFlingVelocityPx = with(density) { 320.dp.toPx() },
                        minFlingDistancePx = with(density) { 15.dp.toPx() },
                        onSwipeLeft = { switchRecord(1) },
                        onSwipeRight = { switchRecord(-1) },
                        onTapOutside = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    )
            ) {
                LazyColumn(
                    state = formState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(split)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        },
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        val dictionary = remember { viewModel.destinationDictionary() }
                        val stations = remember(dictionary) { dictionary.stations.filterNot { it.excluded } }
                        DestinationDropdownField(
                            value = fields.destinationText.orEmpty(),
                            uniqueKey = fields.destinationUniqueKey,
                            stations = stations,
                            suggestedKeys = remember(current.id, current.destinationCandidates) { current.destinationCandidateList() },
                            needsReview = "destination_text" in current.uncertainFieldSet(),
                            isError = invalidField == "destination_text",
                            openRequest = if (invalidField == "destination_text") focusAttempt else 0,
                            // 手打输入必须同时置 uniqueKey=null：名/键错配的行不允许流向导出。
                            onValue = {
                                fields = fields.copy(destinationText = it, destinationUniqueKey = null)
                                if (invalidField == "destination_text") invalidField = null
                            },
                            onSelect = { station ->
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                fields = fields.copy(destinationText = station.name, destinationUniqueKey = station.unique_key)
                                if (invalidField == "destination_text") invalidField = null
                            },
                        )
                    }
                    item {
                        Text("配送方式 *", fontWeight = FontWeight.SemiBold, color = if (invalidField == "delivery_type") com.goings.kaidanzhushou.ui.theme.ErrorRed else Color.Unspecified)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(fields.deliveryType == "delivery", {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                fields = fields.copy(deliveryType = "delivery")
                                invalidField = null
                            }, { Text("送货") })
                            FilterChip(fields.deliveryType == "pickup", {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                fields = fields.copy(deliveryType = "pickup")
                                invalidField = null
                            }, { Text("自提") })
                        }
                    }
                    item {
                        Text("付款方式 *", fontWeight = FontWeight.SemiBold, color = if (invalidField == "payment_type") com.goings.kaidanzhushou.ui.theme.ErrorRed else Color.Unspecified)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaymentType.entries.forEach { payment ->
                                FilterChip(fields.paymentType == payment.code, {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    fields = fields.copy(paymentType = payment.code)
                                    invalidField = null
                                }, { Text(payment.label) })
                            }
                        }
                    }
                    item { Field("发货人 *", fields.senderName.orEmpty(), { fields = fields.copy(senderName = it); if (invalidField == "sender_name") invalidField = null }, isError = invalidField == "sender_name", selectRequest = if (invalidField == "sender_name") focusAttempt else 0) }
                    item {
                        ReceiverDropdownField(
                            value = fields.receiverName.orEmpty(),
                            profiles = receiverProfiles,
                            unresolved = !fields.receiverAssociationResolved,
                            isError = invalidField == "receiver_name",
                            openRequest = if (invalidField == AssociationFields.RECEIVER || invalidField == "receiver_name") focusAttempt else 0,
                            onValue = { next ->
                                fields = receiverInput(fields, next, receiverProfiles)
                                if (invalidField == "receiver_name" || invalidField == AssociationFields.RECEIVER) invalidField = null
                            },
                            onSelect = { profile ->
                                fields = fields.copy(
                                    receiverName = profile.name,
                                    receiverMobile = profile.phone,
                                    receiverProfileId = profile.id,
                                    receiverAssociationResolved = true,
                                    receiverAssociationAccepted = true,
                                )
                                invalidField = null
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                            onUseCurrent = {
                                fields = fields.copy(
                                    receiverProfileId = null,
                                    receiverAssociationResolved = true,
                                    receiverAssociationAccepted = true,
                                )
                                invalidField = null
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                        )
                    }
                    item { Field("收货手机号", fields.receiverMobile.orEmpty(), { fields = fields.copy(receiverMobile = it) }, keyboard = KeyboardType.Phone) }
                    item {
                        GoodsDropdownField(
                            value = fields.goodsName.orEmpty(),
                            profiles = goodsProfiles,
                            unresolved = !fields.goodsAssociationResolved,
                            isError = invalidField == "goods_name",
                            openRequest = if (invalidField == AssociationFields.GOODS || invalidField == "goods_name") focusAttempt else 0,
                            onValue = { next ->
                                fields = goodsInput(fields, next, goodsProfiles)
                                if (invalidField == "goods_name" || invalidField == AssociationFields.GOODS) invalidField = null
                            },
                            onSelect = { profile ->
                                fields = fields.copy(
                                    goodsName = profile.name,
                                    packageName = profile.packageName,
                                    goodsProfileId = profile.id,
                                    goodsAssociationResolved = true,
                                    goodsAssociationAccepted = true,
                                )
                                invalidField = null
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                            onUseCurrent = {
                                fields = fields.copy(
                                    goodsProfileId = null,
                                    goodsAssociationResolved = true,
                                    goodsAssociationAccepted = true,
                                )
                                invalidField = null
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                        )
                    }
                    item { Field("包装", fields.packageName.orEmpty(), { fields = fields.copy(packageName = it) }) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("件数 *", fields.quantity?.toString().orEmpty(), { fields = fields.copy(quantity = it.toIntOrNull()); if (invalidField == "quantity") invalidField = null }, Modifier.weight(1f), KeyboardType.Number, invalidField == "quantity", if (invalidField == "quantity") focusAttempt else 0)
                        Field("重量", fields.weight?.plain().orEmpty(), { fields = fields.copy(weight = it.toDoubleOrNull()); if (invalidField == "weight") invalidField = null }, Modifier.weight(1f), KeyboardType.Decimal, invalidField == "weight", if (invalidField == "weight") focusAttempt else 0)
                        Field("体积", fields.volume?.plain().orEmpty(), { fields = fields.copy(volume = it.toDoubleOrNull()); if (invalidField == "volume") invalidField = null }, Modifier.weight(1f), KeyboardType.Decimal, invalidField == "volume", if (invalidField == "volume") focusAttempt else 0)
                    } }
                    item { Field("运费", fields.freight?.plain().orEmpty(), { fields = fields.copy(freight = it.toDoubleOrNull()); if (invalidField == "freight") invalidField = null }, keyboard = KeyboardType.Decimal, isError = invalidField == "freight", selectRequest = if (invalidField == "freight") focusAttempt else 0) }
                }
                Box(Modifier.fillMaxWidth().height(18.dp).background(Color(0xFFE7EAF0)).pointerInput(Unit) {
                    detectVerticalDragGestures { _, amount ->
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        split = (split + amount / 1200f).coerceIn(.35f, .75f)
                    }
                }) { Box(Modifier.align(Alignment.Center).fillMaxWidth(.18f).height(4.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF9AA3B2))) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f - split)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                ) {
                    ZoomableImage(
                        file = File(current.originalPath),
                        rotationDegrees = current.rotationDegrees,
                        onRotate = {
                            val nextDegrees = (current.rotationDegrees + 90) % 360
                            viewModel.updateRotation(current.id, nextDegrees)
                        },
                        position = if (index >= 0) "${index + 1}/${records.size}" else "",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Screen-wide swipe gesture detector with VelocityTracker support.
 * Supports both:
 * 1) Short & fast flicks (flings) with high velocity and small travel distance
 * 2) Continuous drag exceeding [dragThresholdPx]
 * while protecting system edge navigation gestures.
 */
@Composable
private fun Modifier.screenSwipeToSwitch(
    edgeMarginPx: Float,
    dragThresholdPx: Float,
    minFlingVelocityPx: Float,
    minFlingDistancePx: Float,
    onSwipeLeft: () -> Boolean,
    onSwipeRight: () -> Boolean,
    onTapOutside: () -> Unit,
): Modifier {
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)
    val currentOnTapOutside by rememberUpdatedState(onTapOutside)

    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val startX = down.position.x
            val screenWidth = size.width

            val isNearEdge = startX <= edgeMarginPx || startX >= (screenWidth - edgeMarginPx)
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            var totalDx = 0f
            var totalDy = 0f
            var isHorizontalSwipe: Boolean? = null
            var switched = false
            var moved = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break

                if (!pointer.pressed) {
                    // Finger lifted up: evaluate fast fling / flick velocity!
                    if (!switched && !isNearEdge) {
                        val velocity = velocityTracker.calculateVelocity()
                        val vx = velocity.x
                        val vy = velocity.y
                        val isFastFling = abs(vx) >= minFlingVelocityPx && abs(vx) > abs(vy) * 1.15f && abs(totalDx) >= minFlingDistancePx
                        val isDistancePassed = abs(totalDx) >= dragThresholdPx && abs(totalDx) > abs(totalDy) * 1.15f

                        if (isFastFling || isDistancePassed) {
                            currentOnTapOutside()
                            val toNext = if (isFastFling) vx < 0 else totalDx < 0
                            switched = if (toNext) currentOnSwipeLeft() else currentOnSwipeRight()
                            if (switched) pointer.consume()
                        } else if (!moved) {
                            currentOnTapOutside()
                        }
                    }
                    break
                }

                velocityTracker.addPosition(pointer.uptimeMillis, pointer.position)
                val dx = pointer.position.x - pointer.previousPosition.x
                val dy = pointer.position.y - pointer.previousPosition.y
                totalDx += dx
                totalDy += dy

                if (abs(totalDx) > 8f || abs(totalDy) > 8f) {
                    moved = true
                }

                if (!isNearEdge && isHorizontalSwipe == null) {
                    if (abs(totalDx) > 10f || abs(totalDy) > 10f) {
                        if (abs(totalDx) > abs(totalDy) * 1.15f) {
                            isHorizontalSwipe = true
                        } else if (abs(totalDy) > abs(totalDx) * 1.15f) {
                            isHorizontalSwipe = false
                            break // Vertical scrolling in list
                        }
                    }
                }

                // If user dragged far enough while still holding finger down
                if (isHorizontalSwipe == true && !switched && !isNearEdge) {
                    if (abs(totalDx) >= dragThresholdPx && abs(totalDx) > abs(totalDy) * 1.15f) {
                        currentOnTapOutside()
                        switched = if (totalDx < 0) currentOnSwipeLeft() else currentOnSwipeRight()
                        if (switched) {
                            pointer.consume()
                            break
                        }
                    }
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

/**
 * 到站是封闭集合（当前只有通海县、玉溪市），所以下拉里始终列出全部站点，
 * 归一给出的候选排在前面并标注。选中即同时写入标准名与 unique_key；
 * 手打则把 unique_key 置空——名与编码错配的行不允许流向导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DestinationDropdownField(
    value: String,
    uniqueKey: String?,
    stations: List<DestinationDictionary.Station>,
    suggestedKeys: List<String>,
    needsReview: Boolean,
    isError: Boolean,
    openRequest: Int,
    onValue: (String) -> Unit,
    onSelect: (DestinationDictionary.Station) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var editor by remember(value) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // 归一推荐的排前面，其余按原顺序补齐，保证两个站点始终都能选到。
    val choices = remember(stations, suggestedKeys) {
        stations.sortedBy { station ->
            suggestedKeys.indexOf(station.unique_key).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
    }
    val offList = value.isNotBlank() && stations.none { it.name == value }
    LaunchedEffect(value) {
        if (editor.text != value) editor = TextFieldValue(value, TextRange(value.length))
    }
    LaunchedEffect(openRequest) {
        if (openRequest > 0) {
            focusRequester.requestFocus()
            editor = editor.copy(selection = TextRange(0, editor.text.length))
            expanded = true
        }
    }
    val highlight = needsReview || offList
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = editor,
            onValueChange = { next -> editor = next; onValue(next.text); expanded = true },
            label = { Text("到站 *") },
            singleLine = true,
            isError = isError,
            supportingText = when {
                offList -> ({ Text("不在常用到站里，请确认", color = com.goings.kaidanzhushou.ui.theme.Warning) })
                needsReview -> ({ Text("到站是推定的，请核对", color = com.goings.kaidanzhushou.ui.theme.Warning) })
                else -> null
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = if (highlight) Color(0xFFFFF8E1) else Color.Transparent,
                unfocusedContainerColor = if (highlight) Color(0xFFFFF8E1) else Color.Transparent,
                focusedBorderColor = if (highlight) com.goings.kaidanzhushou.ui.theme.Warning else MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = if (highlight) com.goings.kaidanzhushou.ui.theme.Warning else MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
                .testTag("destination_dropdown_input")
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) expanded = true },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEachIndexed { index, station ->
                val prefix = station.full_name.removeSuffix(station.name)
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (needsReview && index == 0 && station.unique_key in suggestedKeys) {
                                    Text("可能是这个", style = MaterialTheme.typography.labelSmall, color = com.goings.kaidanzhushou.ui.theme.Warning)
                                }
                            }
                            if (prefix.isNotBlank()) {
                                Text(prefix, maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                            }
                            if (station.unique_key == uniqueKey) {
                                Icon(Icons.Rounded.Check, null, tint = PrimaryBlue, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    },
                    onClick = { expanded = false; onSelect(station) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("destination_option_${station.unique_key}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiverDropdownField(
    value: String,
    profiles: List<ReceiverProfileEntity>,
    unresolved: Boolean,
    isError: Boolean,
    openRequest: Int,
    onValue: (String) -> Unit,
    onSelect: (ReceiverProfileEntity) -> Unit,
    onUseCurrent: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var editor by remember(value) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val resolution = remember(value, profiles) {
        AssociationMatcher.resolve(
            value, profiles, ReceiverProfileEntity::normalizedName,
            ReceiverProfileEntity::useCount, ReceiverProfileEntity::lastUsedAt,
        )
    }
    val choices = resolution.candidates.map { it.value }.ifEmpty { profiles.take(5) }
    LaunchedEffect(value) {
        if (editor.text != value) editor = TextFieldValue(value, TextRange(value.length))
    }
    LaunchedEffect(openRequest) {
        if (openRequest > 0) {
            focusRequester.requestFocus()
            editor = editor.copy(selection = TextRange(0, editor.text.length))
            expanded = true
        }
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = editor,
            onValueChange = { next -> editor = next; onValue(next.text); expanded = true },
            label = { Text("收货人 *") },
            singleLine = true,
            isError = isError,
            supportingText = if (unresolved) ({ Text("请选择正确的收货人", color = com.goings.kaidanzhushou.ui.theme.Warning) }) else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = if (unresolved) Color(0xFFFFF8E1) else Color.Transparent,
                unfocusedContainerColor = if (unresolved) Color(0xFFFFF8E1) else Color.Transparent,
                focusedBorderColor = if (unresolved) com.goings.kaidanzhushou.ui.theme.Warning else MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = if (unresolved) com.goings.kaidanzhushou.ui.theme.Warning else MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
                .testTag("receiver_dropdown_input")
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) expanded = true },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEachIndexed { index, profile ->
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (unresolved && index == 0) Text("可能是这位", style = MaterialTheme.typography.labelSmall, color = com.goings.kaidanzhushou.ui.theme.Warning)
                            }
                            Text(AssociationMatcher.maskPhone(profile.phone), maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                        }
                    },
                    onClick = { expanded = false; onSelect(profile) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("receiver_option_${profile.id}"),
                )
            }
            if (value.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("使用当前填写内容") },
                    onClick = { expanded = false; onUseCurrent() },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoodsDropdownField(
    value: String,
    profiles: List<GoodsProfileEntity>,
    unresolved: Boolean,
    isError: Boolean,
    openRequest: Int,
    onValue: (String) -> Unit,
    onSelect: (GoodsProfileEntity) -> Unit,
    onUseCurrent: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var editor by remember(value) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val resolution = remember(value, profiles) {
        AssociationMatcher.resolve(
            value, profiles, GoodsProfileEntity::normalizedName,
            GoodsProfileEntity::useCount, GoodsProfileEntity::lastUsedAt,
        )
    }
    val choices = resolution.candidates.map { it.value }.ifEmpty { profiles.take(5) }
    LaunchedEffect(value) {
        if (editor.text != value) editor = TextFieldValue(value, TextRange(value.length))
    }
    LaunchedEffect(openRequest) {
        if (openRequest > 0) {
            focusRequester.requestFocus()
            editor = editor.copy(selection = TextRange(0, editor.text.length))
            expanded = true
        }
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = editor,
            onValueChange = { next -> editor = next; onValue(next.text); expanded = true },
            label = { Text("货物名称 *") },
            singleLine = true,
            isError = isError,
            supportingText = if (unresolved) ({ Text("请选择正确的货物", color = com.goings.kaidanzhushou.ui.theme.Warning) }) else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = if (unresolved) Color(0xFFFFF8E1) else Color.Transparent,
                unfocusedContainerColor = if (unresolved) Color(0xFFFFF8E1) else Color.Transparent,
                focusedBorderColor = if (unresolved) com.goings.kaidanzhushou.ui.theme.Warning else MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = if (unresolved) com.goings.kaidanzhushou.ui.theme.Warning else MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
                .testTag("goods_dropdown_input")
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) expanded = true },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEachIndexed { index, profile ->
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (unresolved && index == 0) Text("可能是这个", style = MaterialTheme.typography.labelSmall, color = com.goings.kaidanzhushou.ui.theme.Warning)
                            }
                            Text(profile.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                        }
                    },
                    onClick = { expanded = false; onSelect(profile) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("goods_option_${profile.id}"),
                )
            }
            if (value.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("使用当前填写内容") },
                    onClick = { expanded = false; onUseCurrent() },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
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
    var editor by remember(value) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
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
    rotationDegrees: Int,
    onRotate: () -> Unit,
    position: String,
    modifier: Modifier,
) {
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var x by remember(file) { mutableFloatStateOf(0f) }
    var y by remember(file) { mutableFloatStateOf(0f) }
    val animatedRotation by animateFloatAsState(targetValue = rotationDegrees.toFloat(), label = "照片旋转")
    Box(
        modifier
            .background(Color(0xFF161618))
            .clipToBounds()
            .pointerInput(file) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1.05f || nextScale > 1.05f) {
                        scale = nextScale
                        x += pan.x
                        y += pan.y
                        if (scale == 1f) { x = 0f; y = 0f }
                    }
                }
            }
    ) {
        AsyncImage(
            model = file,
            contentDescription = "托运单照片",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = x,
                translationY = y,
                rotationZ = animatedRotation,
            ),
        )
        IconButton(
            onClick = {
                scale = 1f
                x = 0f
                y = 0f
                onRotate()
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = .62f)),
        ) {
            Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = "顺时针旋转照片", tint = Color.White)
        }
        Text("左右滑动切换  $position", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(.62f), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

private fun formItemIndex(field: String): Int = when (field) {
    "destination_text" -> 0
    "delivery_type" -> 1
    "payment_type" -> 2
    "sender_name" -> 3
    "receiver_name", AssociationFields.RECEIVER -> 4
    "receiver_mobile" -> 5
    "goods_name", AssociationFields.GOODS -> 6
    "package" -> 7
    "quantity", "weight", "volume" -> 8
    "freight" -> 9
    else -> 0
}

private fun Double.plain() = if (this % 1.0 == 0.0) toLong().toString() else toString()

private fun changedFields(old: RecordEntity, now: EditableFields): Set<String> = buildSet {
    if (old.destinationText != now.destinationText || old.destinationUniqueKey != now.destinationUniqueKey) add("destination_text")
    if (old.deliveryType != now.deliveryType) add("delivery_type")
    if (old.senderName != now.senderName) add("sender_name")
    val receiverDecisionChanged = now.receiverAssociationAccepted || old.receiverProfileId != now.receiverProfileId ||
        (AssociationFields.RECEIVER in old.uncertainFieldSet() && now.receiverAssociationResolved)
    if (old.receiverName != now.receiverName || receiverDecisionChanged) add("receiver_name")
    if (old.receiverMobile != now.receiverMobile || receiverDecisionChanged) add("receiver_mobile")
    val goodsDecisionChanged = now.goodsAssociationAccepted || old.goodsProfileId != now.goodsProfileId ||
        (AssociationFields.GOODS in old.uncertainFieldSet() && now.goodsAssociationResolved)
    if (old.goodsName != now.goodsName || goodsDecisionChanged) add("goods_name")
    if (old.packageName != now.packageName || goodsDecisionChanged) add("package")
    if (old.quantity != now.quantity) add("quantity")
    if (old.weight != now.weight) add("weight")
    if (old.volume != now.volume) add("volume")
    if (old.freight != now.freight) add("freight")
    if (old.paymentType != now.paymentType) add("payment_type")
}

private fun resolveExactAssociations(
    fields: EditableFields,
    record: RecordEntity,
    receivers: List<ReceiverProfileEntity>,
    goods: List<GoodsProfileEntity>,
): EditableFields {
    var resolved = fields
    val edited = record.editedFieldSet()
    if (receivers.isNotEmpty() && "receiver_name" !in edited && "receiver_mobile" !in edited && fields.receiverProfileId == null) {
        resolved = receiverInput(resolved, fields.receiverName.orEmpty(), receivers)
    }
    if (goods.isNotEmpty() && "goods_name" !in edited && "package" !in edited && fields.goodsProfileId == null) {
        resolved = goodsInput(resolved, fields.goodsName.orEmpty(), goods)
    }
    return resolved
}

private fun receiverInput(fields: EditableFields, value: String, profiles: List<ReceiverProfileEntity>): EditableFields {
    val resolution = AssociationMatcher.resolve(
        value, profiles, ReceiverProfileEntity::normalizedName,
        ReceiverProfileEntity::useCount, ReceiverProfileEntity::lastUsedAt,
    )
    val automatic = resolution.automatic
    return if (automatic != null) {
        fields.copy(
            receiverName = automatic.name,
            receiverMobile = automatic.phone,
            receiverProfileId = automatic.id,
            receiverAssociationResolved = true,
            receiverAssociationAccepted = false,
        )
    } else {
        fields.copy(
            receiverName = value,
            receiverMobile = if (fields.receiverProfileId != null) null else fields.receiverMobile,
            receiverProfileId = null,
            receiverAssociationResolved = !resolution.needsChoice,
            receiverAssociationAccepted = false,
        )
    }
}

private fun goodsInput(fields: EditableFields, value: String, profiles: List<GoodsProfileEntity>): EditableFields {
    val resolution = AssociationMatcher.resolve(
        value, profiles, GoodsProfileEntity::normalizedName,
        GoodsProfileEntity::useCount, GoodsProfileEntity::lastUsedAt,
    )
    val automatic = resolution.automatic
    return if (automatic != null) {
        fields.copy(
            goodsName = automatic.name,
            packageName = automatic.packageName,
            goodsProfileId = automatic.id,
            goodsAssociationResolved = true,
            goodsAssociationAccepted = false,
        )
    } else {
        fields.copy(
            goodsName = value,
            packageName = if (fields.goodsProfileId != null) null else fields.packageName,
            goodsProfileId = null,
            goodsAssociationResolved = !resolution.needsChoice,
            goodsAssociationAccepted = false,
        )
    }
}
