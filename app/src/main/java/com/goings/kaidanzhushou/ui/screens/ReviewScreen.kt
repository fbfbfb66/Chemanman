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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.HorizontalDivider
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
import com.goings.kaidanzhushou.data.local.SenderProfileEntity
import com.goings.kaidanzhushou.domain.AdvanceReturnType
import com.goings.kaidanzhushou.domain.AssociationFields
import com.goings.kaidanzhushou.domain.DestinationDictionary
import com.goings.kaidanzhushou.domain.AssociationMatcher
import com.goings.kaidanzhushou.domain.EditableFields
import com.goings.kaidanzhushou.domain.FeeReconciler
import com.goings.kaidanzhushou.domain.PaymentType
import com.goings.kaidanzhushou.domain.RecordValidator
import com.goings.kaidanzhushou.domain.ReviewStatus
import com.goings.kaidanzhushou.ui.screens.AppTopBar
import com.goings.kaidanzhushou.ui.theme.PrimaryBlue
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun ReviewScreen(viewModel: MainViewModel, batchId: String, recordId: String, outerPadding: PaddingValues, onBack: () -> Unit) {
    val records by viewModel.records(batchId).collectAsStateWithLifecycle(emptyList())
    val senderProfiles by viewModel.senderProfiles.collectAsStateWithLifecycle(emptyList())
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
    // 已确认的记录默认只读，点「重新编辑」才解锁；解锁状态跟着记录走，翻页回来仍然有效。
    var unlockedIds by remember { mutableStateOf(emptySet<String>()) }
    // 用户在下拉里选了「就是这个」但值没变时，changedFields 看不出差异，靠这个集合把确认动作记下来。
    var acceptedFields by remember(activeRecordId) { mutableStateOf(emptySet<String>()) }
    val locked = record != null && record.reviewStatus == ReviewStatus.CONFIRMED && record.id !in unlockedIds
    // 照片缩放状态提到这一层：放大时要压住屏幕级的左右滑动切换。
    var imageScale by remember { mutableFloatStateOf(1f) }
    var imageOffsetX by remember { mutableFloatStateOf(0f) }
    var imageOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(record?.id) {
        record?.let { if (initializedId != it.id) { fields = it.editable(); initializedId = it.id } }
    }

    LaunchedEffect(activeRecordId) {
        imageScale = 1f
        imageOffsetX = 0f
        imageOffsetY = 0f
    }

    LaunchedEffect(initializedId, senderProfiles, receiverProfiles, goodsProfiles) {
        val current = record ?: return@LaunchedEffect
        if (initializedId != current.id) return@LaunchedEffect
        fields = resolveExactAssociations(fields, current, senderProfiles, receiverProfiles, goodsProfiles)
    }

    fun changesFor(entity: RecordEntity) = changedFields(entity, fields) + acceptedFields

    // 确认后直接翻到下一张，最后一张才退回列表——连续核对不用反复进出。
    fun advanceAfterConfirm() {
        val nextIndex = index + 1
        if (index >= 0 && nextIndex in records.indices) {
            focusManager.clearFocus()
            keyboardController?.hide()
            scope.launch { formState.scrollToItem(0) }
            activeRecordId = records[nextIndex].id
        } else {
            onBack()
        }
    }

    fun switchRecord(offset: Int): Boolean {
        val nextIndex = index + offset
        if (index < 0 || nextIndex !in records.indices) return false
        focusManager.clearFocus()
        keyboardController?.hide()
        // 只读状态下纯浏览不写库，免得把已确认的记录打回待核对。
        if (!locked) record?.let { viewModel.updateRecord(it.id, fields, changesFor(it)) }
        activeRecordId = records[nextIndex].id
        return true
    }

    Scaffold(
        topBar = { AppTopBar(if (index >= 0) "核对 ${index + 1}/${records.size}" else "人工核对", onBack) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (locked) {
                    OutlinedButton(
                        onClick = { record?.let { unlockedIds = unlockedIds + it.id } },
                        modifier = Modifier.weight(1f),
                    ) { Text("重新编辑") }
                    // 已确认的记录不再重复写库：重复确认会重复累加常用度、扭曲自动匹配。
                    Button(onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        advanceAfterConfirm()
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Check, null)
                        Text("已确认")
                    }
                    return@Row
                }
                OutlinedButton(onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    record?.let { viewModel.updateRecord(it.id, fields, changesFor(it)) }
                }, modifier = Modifier.weight(1f)) { Text("保存草稿") }
                Button(onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    record?.let { current ->
                        val associationIssue = when {
                            !fields.senderAssociationResolved -> AssociationFields.SENDER
                            !fields.receiverAssociationResolved -> AssociationFields.RECEIVER
                            else -> null
                        }
                        val firstIssue = RecordValidator.validate(fields).firstOrNull()
                        if (associationIssue == null && firstIssue == null) {
                            viewModel.saveAndConfirm(current.id, fields, changesFor(current)) { advanceAfterConfirm() }
                        } else {
                            viewModel.updateRecord(current.id, fields, changesFor(current))
                            invalidField = associationIssue ?: firstIssue?.field
                            focusAttempt += 1
                            val issueField = associationIssue ?: firstIssue?.field ?: return@let
                            viewModel.showWarning(when (issueField) {
                                AssociationFields.SENDER -> "请选择正确的发货人"
                                AssociationFields.RECEIVER -> "请选择正确的收货人"
                                else -> firstIssue?.message.orEmpty()
                            })
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
            // 黄色提醒读的是落库时的「待核对」标记，但人一改就该消：这里按本次已改过的字段先行扣除，
            // 判据与保存时 updatedUncertainFields 的清除规则一致，不会出现界面已消、存回去又冒出来。
            val pendingReview = current.uncertainFieldSet() - changesFor(current)
                .flatMap { field ->
                    when (field) {
                        "destination_text" -> listOf("destination_text")
                        "freight", "advance_payment" -> listOf(FeeReconciler.UNCERTAIN_TOKEN)
                        else -> emptyList()
                    }
                }.toSet()
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
                        // 放大看图时左右轻移是在平移照片，不该切走这一张。
                        isZoomed = { imageScale > 1.05f },
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
                            needsReview = "destination_text" in pendingReview,
                            isError = invalidField == "destination_text",
                            openRequest = if (invalidField == "destination_text") focusAttempt else 0,
                            enabled = !locked,
                            // 手打输入必须同时置 uniqueKey=null：名/键错配的行不允许流向导出。
                            onValue = {
                                fields = fields.copy(destinationText = it, destinationUniqueKey = null)
                                if (invalidField == "destination_text") invalidField = null
                            },
                            // 选中即人工确认：哪怕选的就是 AI 推定的那个站，黄色提醒也该消失。
                            onSelect = { station ->
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                fields = fields.copy(destinationText = station.name, destinationUniqueKey = station.unique_key)
                                acceptedFields = acceptedFields + "destination_text"
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
                            }, { Text("送货") }, enabled = !locked)
                            FilterChip(fields.deliveryType == "pickup", {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                fields = fields.copy(deliveryType = "pickup")
                                invalidField = null
                            }, { Text("自提") }, enabled = !locked)
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
                                }, { Text(payment.label) }, enabled = !locked)
                            }
                        }
                    }
                    item {
                        SenderDropdownField(
                            value = fields.senderName.orEmpty(),
                            profiles = senderProfiles,
                            unresolved = !fields.senderAssociationResolved,
                            isError = invalidField == "sender_name",
                            openRequest = if (invalidField == AssociationFields.SENDER || invalidField == "sender_name") focusAttempt else 0,
                            enabled = !locked,
                            onValue = { next ->
                                fields = senderInput(fields, next, senderProfiles, manual = true)
                                if (invalidField == "sender_name" || invalidField == AssociationFields.SENDER) invalidField = null
                            },
                            onSelect = { profile ->
                                fields = fields.copy(
                                    senderName = profile.name,
                                    senderProfileId = profile.id,
                                    senderAssociationResolved = true,
                                    senderAssociationAccepted = true,
                                )
                                invalidField = null
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                            onUseCurrent = {
                                fields = fields.copy(
                                    senderProfileId = null,
                                    senderAssociationResolved = true,
                                    senderAssociationAccepted = true,
                                )
                                invalidField = null
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                        )
                    }
                    item {
                        ReceiverDropdownField(
                            value = fields.receiverName.orEmpty(),
                            profiles = receiverProfiles,
                            unresolved = !fields.receiverAssociationResolved,
                            isError = invalidField == "receiver_name",
                            openRequest = if (invalidField == AssociationFields.RECEIVER || invalidField == "receiver_name") focusAttempt else 0,
                            enabled = !locked,
                            onValue = { next ->
                                fields = receiverInput(fields, next, receiverProfiles, manual = true)
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
                    item { Field("收货手机号", fields.receiverMobile.orEmpty(), { fields = fields.copy(receiverMobile = it) }, keyboard = KeyboardType.Phone, enabled = !locked) }
                    item {
                        // 货物只做下拉辅助（顺带带出包装），不再要求人工确认「是不是这个」。
                        GoodsDropdownField(
                            value = fields.goodsName.orEmpty(),
                            profiles = goodsProfiles,
                            isError = invalidField == "goods_name",
                            openRequest = if (invalidField == "goods_name") focusAttempt else 0,
                            enabled = !locked,
                            onValue = { next ->
                                fields = goodsInput(fields, next, goodsProfiles)
                                if (invalidField == "goods_name") invalidField = null
                            },
                            onSelect = { profile ->
                                fields = fields.copy(
                                    goodsName = profile.name,
                                    packageName = profile.packageName,
                                    goodsProfileId = profile.id,
                                )
                                invalidField = null
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                        )
                    }
                    item { Field("包装", fields.packageName.orEmpty(), { fields = fields.copy(packageName = it) }, enabled = !locked) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("件数 *", fields.quantity?.toString().orEmpty(), { fields = fields.copy(quantity = it.toIntOrNull()); if (invalidField == "quantity") invalidField = null }, Modifier.weight(1f), KeyboardType.Number, invalidField == "quantity", if (invalidField == "quantity") focusAttempt else 0, enabled = !locked)
                        Field("重量", fields.weight?.plain().orEmpty(), { fields = fields.copy(weight = it.toDoubleOrNull()); if (invalidField == "weight") invalidField = null }, Modifier.weight(1f), KeyboardType.Decimal, invalidField == "weight", if (invalidField == "weight") focusAttempt else 0, enabled = !locked)
                        Field("体积", fields.volume?.plain().orEmpty(), { fields = fields.copy(volume = it.toDoubleOrNull()); if (invalidField == "volume") invalidField = null }, Modifier.weight(1f), KeyboardType.Decimal, invalidField == "volume", if (invalidField == "volume") focusAttempt else 0, enabled = !locked)
                    } }
                    // 单据上的「总运费」= 运费 + 垫付款，网站开单页的运费框收的也是这个数。
                    // AI 照抄的三栏对不上时两个金额都会留空，只留一句提示让人对着单据补。
                    val feeWarning = if (FeeReconciler.UNCERTAIN_TOKEN in pendingReview) {
                        "费用栏拿不准，请对照单据核实"
                    } else null
                    item { Field("总运费", fields.freight?.plain().orEmpty(), { fields = fields.copy(freight = it.toDoubleOrNull()); if (invalidField == "freight") invalidField = null }, keyboard = KeyboardType.Decimal, isError = invalidField == "freight", selectRequest = if (invalidField == "freight") focusAttempt else 0, enabled = !locked, warningText = feeWarning) }
                    item {
                        Field("垫付款", fields.advancePayment?.plain().orEmpty(), {
                            fields = fields.withAdvancePayment(it.toDoubleOrNull())
                            if (invalidField == "advance_payment" || invalidField == "advance_return_type") invalidField = null
                        }, keyboard = KeyboardType.Decimal, isError = invalidField == "advance_payment", selectRequest = if (invalidField == "advance_payment") focusAttempt else 0, enabled = !locked, warningText = feeWarning)
                        // 有垫付款才需要决定去向；AI 不猜，由录单人按实际情况选。
                        val advance = fields.advancePayment
                        if (advance != null) {
                            Text(
                                "垫付款去向 *",
                                fontWeight = FontWeight.SemiBold,
                                color = if (invalidField == "advance_return_type") com.goings.kaidanzhushou.ui.theme.ErrorRed else Color.Unspecified,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AdvanceReturnType.entries.forEach { type ->
                                    FilterChip(fields.advanceReturnType == type.code, {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        fields = fields.copy(advanceReturnType = type.code)
                                        invalidField = null
                                    }, { Text(type.label) }, enabled = !locked)
                                }
                            }
                            // 三个数一起摆出来，省得只看到一个减法结果、不知道跟单据哪一栏对。
                            val freight = fields.freight
                            Text(
                                when {
                                    freight == null -> "垫付款 ${advance.plain()}，总运费未填"
                                    freight >= advance -> "运费 ${(freight - advance).plain()} + 垫付款 ${advance.plain()} = 总运费 ${freight.plain()}"
                                    else -> "总运费 ${freight.plain()} 小于垫付款 ${advance.plain()}，请对照单据核实"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (freight != null && freight < advance) com.goings.kaidanzhushou.ui.theme.Warning else Color.Unspecified,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
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
                        scale = imageScale,
                        offsetX = imageOffsetX,
                        offsetY = imageOffsetY,
                        onTransform = { nextScale, nextX, nextY ->
                            imageScale = nextScale
                            imageOffsetX = nextX
                            imageOffsetY = nextY
                        },
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
 * while protecting system edge navigation gestures and photo panning ([isZoomed]).
 */
@Composable
private fun Modifier.screenSwipeToSwitch(
    edgeMarginPx: Float,
    dragThresholdPx: Float,
    minFlingVelocityPx: Float,
    minFlingDistancePx: Float,
    // 必须是 lambda：pointerInput(Unit) 的挂起块只跑一次，普通 Boolean 会被捕获成陈旧值。
    isZoomed: () -> Boolean,
    onSwipeLeft: () -> Boolean,
    onSwipeRight: () -> Boolean,
    onTapOutside: () -> Unit,
): Modifier {
    val currentIsZoomed by rememberUpdatedState(isZoomed)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)
    val currentOnTapOutside by rememberUpdatedState(onTapOutside)

    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val startX = down.position.x
            val screenWidth = size.width

            // 手指落下时定调：贴边（系统返回手势）或照片已放大（正在平移）就整段不切换。
            val swipeBlocked = startX <= edgeMarginPx || startX >= (screenWidth - edgeMarginPx) || currentIsZoomed()
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
                    if (!switched && !swipeBlocked) {
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

                if (!swipeBlocked && isHorizontalSwipe == null) {
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
                if (isHorizontalSwipe == true && !switched && !swipeBlocked) {
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
    enabled: Boolean = true,
    onValue: (String) -> Unit,
    onSelect: (DestinationDictionary.Station) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var editor by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
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
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = editor,
            onValueChange = { next -> editor = next; onValue(next.text); expanded = true },
            label = { Text("到站 *") },
            singleLine = true,
            enabled = enabled,
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
                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = enabled)
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

// 候选区最多露四行；再多就在自己的滚动区里滚，不挤掉底下钉住的「使用当前填写内容」。
private val OPTION_LIST_MAX_HEIGHT = 192.dp

// 发货人没有手机号可展示，下拉里只列名字；其余交互与收货人一致。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SenderDropdownField(
    value: String,
    profiles: List<SenderProfileEntity>,
    unresolved: Boolean,
    isError: Boolean,
    openRequest: Int,
    enabled: Boolean = true,
    onValue: (String) -> Unit,
    onSelect: (SenderProfileEntity) -> Unit,
    onUseCurrent: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var editor by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // 警告模式（unresolved）列的是纠错候选——AI 填的这个名字可能该换成哪几个；
    // 人一动手改字，parent 就把 unresolved 抹掉，下拉随即切成高级搜索。
    val choices = remember(value, profiles, unresolved) {
        if (unresolved) {
            AssociationMatcher.resolve(
                value, profiles, SenderProfileEntity::normalizedName,
                SenderProfileEntity::useCount, SenderProfileEntity::lastUsedAt,
            ).candidates.map { it.value }
        } else {
            AssociationMatcher.search(
                value, profiles, SenderProfileEntity::normalizedName,
                SenderProfileEntity::useCount, SenderProfileEntity::lastUsedAt,
            )
        }
    }
    // 警告模式点一下就展示可换的名字；普通模式只有打字搜到了才弹，免得一聚焦就糊住半张表单。
    val showMenu = expanded && enabled && choices.isNotEmpty() && (unresolved || value.isNotBlank())
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
    ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { if (!it) expanded = false }) {
        OutlinedTextField(
            value = editor,
            onValueChange = { next -> editor = next; onValue(next.text); expanded = true },
            label = { Text("发货人 *") },
            singleLine = true,
            enabled = enabled,
            isError = isError,
            supportingText = if (unresolved) ({ Text("请选择正确的发货人", color = com.goings.kaidanzhushou.ui.theme.Warning) }) else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = if (unresolved) Color(0xFFFFF8E1) else Color.Transparent,
                unfocusedContainerColor = if (unresolved) Color(0xFFFFF8E1) else Color.Transparent,
                focusedBorderColor = if (unresolved) com.goings.kaidanzhushou.ui.theme.Warning else MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = if (unresolved) com.goings.kaidanzhushou.ui.theme.Warning else MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = enabled)
                .testTag("sender_dropdown_input")
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused && unresolved) expanded = true },
        )
        ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
            // 候选自己滚，「使用当前填写内容」钉在下面不参与滚动。
            Column(Modifier.heightIn(max = OPTION_LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                choices.forEachIndexed { index, profile ->
                    DropdownMenuItem(
                        text = {
                            Column(Modifier.fillMaxWidth()) {
                                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (unresolved && index == 0) Text("可能是这家", style = MaterialTheme.typography.labelSmall, color = com.goings.kaidanzhushou.ui.theme.Warning)
                            }
                        },
                        onClick = { expanded = false; onSelect(profile) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("sender_option_${profile.id}"),
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("使用当前填写内容") },
                onClick = { expanded = false; onUseCurrent() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("sender_use_current"),
            )
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
    enabled: Boolean = true,
    onValue: (String) -> Unit,
    onSelect: (ReceiverProfileEntity) -> Unit,
    onUseCurrent: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var editor by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val choices = remember(value, profiles, unresolved) {
        if (unresolved) {
            AssociationMatcher.resolve(
                value, profiles, ReceiverProfileEntity::normalizedName,
                ReceiverProfileEntity::useCount, ReceiverProfileEntity::lastUsedAt,
            ).candidates.map { it.value }
        } else {
            AssociationMatcher.search(
                value, profiles, ReceiverProfileEntity::normalizedName,
                ReceiverProfileEntity::useCount, ReceiverProfileEntity::lastUsedAt,
            )
        }
    }
    val showMenu = expanded && enabled && choices.isNotEmpty() && (unresolved || value.isNotBlank())
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
    ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { if (!it) expanded = false }) {
        OutlinedTextField(
            value = editor,
            onValueChange = { next -> editor = next; onValue(next.text); expanded = true },
            label = { Text("收货人 *") },
            singleLine = true,
            enabled = enabled,
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
                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = enabled)
                .testTag("receiver_dropdown_input")
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused && unresolved) expanded = true },
        )
        ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
            Column(Modifier.heightIn(max = OPTION_LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
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
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("使用当前填写内容") },
                onClick = { expanded = false; onUseCurrent() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("receiver_use_current"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoodsDropdownField(
    value: String,
    profiles: List<GoodsProfileEntity>,
    isError: Boolean,
    openRequest: Int,
    enabled: Boolean = true,
    onValue: (String) -> Unit,
    onSelect: (GoodsProfileEntity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var editor by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // 货物不设警告模式，走高级搜索；搜不到时再退回纠错候选，AI 把「硫酸铜」认成「硫酸同」时仍有得选。
    val choices = remember(value, profiles) {
        AssociationMatcher.search(
            value, profiles, GoodsProfileEntity::normalizedName,
            GoodsProfileEntity::useCount, GoodsProfileEntity::lastUsedAt,
        ).ifEmpty {
            AssociationMatcher.resolve(
                value, profiles, GoodsProfileEntity::normalizedName,
                GoodsProfileEntity::useCount, GoodsProfileEntity::lastUsedAt,
            ).candidates.map { it.value }
        }
    }
    val showMenu = expanded && enabled && value.isNotBlank() && choices.isNotEmpty()
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
    ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { if (!it) expanded = false }) {
        OutlinedTextField(
            value = editor,
            onValueChange = { next -> editor = next; onValue(next.text); expanded = true },
            label = { Text("货物名称 *") },
            singleLine = true,
            enabled = enabled,
            isError = isError,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = enabled)
                .testTag("goods_dropdown_input")
                .focusRequester(focusRequester),
        )
        ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
            choices.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                            Text(profile.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                        }
                    },
                    onClick = { expanded = false; onSelect(profile) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("goods_option_${profile.id}"),
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
    enabled: Boolean = true,
    warningText: String? = null,
) {
    val focusRequester = remember { FocusRequester() }
    // 不能用 remember(value)：每次输入都会重建状态并把光标甩到末尾。
    var editor by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (value != editor.text) editor = TextFieldValue(value, TextRange(editor.selection.start.coerceIn(0, value.length)))
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
        enabled = enabled,
        isError = isError,
        supportingText = warningText?.let { text ->
            { Text(text, color = com.goings.kaidanzhushou.ui.theme.Warning) }
        },
        colors = if (warningText == null) OutlinedTextFieldDefaults.colors() else OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color(0xFFFFF8E1),
            unfocusedContainerColor = Color(0xFFFFF8E1),
            disabledContainerColor = Color(0xFFFFF8E1),
            focusedBorderColor = com.goings.kaidanzhushou.ui.theme.Warning,
            unfocusedBorderColor = com.goings.kaidanzhushou.ui.theme.Warning,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}

@Composable
private fun ZoomableImage(
    file: File,
    rotationDegrees: Int,
    // 缩放状态由调用方持有：放大时要压住屏幕级的左右滑动切换。
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransform: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    onRotate: () -> Unit,
    position: String,
    modifier: Modifier,
) {
    val animatedRotation by animateFloatAsState(targetValue = rotationDegrees.toFloat(), label = "照片旋转")
    val currentScale by rememberUpdatedState(scale)
    val currentX by rememberUpdatedState(offsetX)
    val currentY by rememberUpdatedState(offsetY)
    val currentOnTransform by rememberUpdatedState(onTransform)
    Box(
        modifier
            .background(Color(0xFF161618))
            .clipToBounds()
            .pointerInput(file) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (currentScale * zoom).coerceIn(1f, 5f)
                    if (currentScale > 1.05f || nextScale > 1.05f) {
                        if (nextScale == 1f) currentOnTransform(1f, 0f, 0f)
                        else currentOnTransform(nextScale, currentX + pan.x, currentY + pan.y)
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
                translationX = offsetX,
                translationY = offsetY,
                rotationZ = animatedRotation,
            ),
        )
        IconButton(
            onClick = {
                onTransform(1f, 0f, 0f)
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
    "sender_name", AssociationFields.SENDER -> 3
    "receiver_name", AssociationFields.RECEIVER -> 4
    "receiver_mobile" -> 5
    "goods_name" -> 6
    "package" -> 7
    "quantity", "weight", "volume" -> 8
    "freight" -> 9
    "advance_payment", "advance_return_type" -> 10
    else -> 0
}

private fun Double.plain() = if (this % 1.0 == 0.0) toLong().toString() else toString()

private fun changedFields(old: RecordEntity, now: EditableFields): Set<String> = buildSet {
    if (old.destinationText != now.destinationText || old.destinationUniqueKey != now.destinationUniqueKey) add("destination_text")
    if (old.deliveryType != now.deliveryType) add("delivery_type")
    val senderDecisionChanged = now.senderAssociationAccepted || old.senderProfileId != now.senderProfileId ||
        (AssociationFields.SENDER in old.uncertainFieldSet() && now.senderAssociationResolved)
    if (old.senderName != now.senderName || senderDecisionChanged) add("sender_name")
    val receiverDecisionChanged = now.receiverAssociationAccepted || old.receiverProfileId != now.receiverProfileId ||
        (AssociationFields.RECEIVER in old.uncertainFieldSet() && now.receiverAssociationResolved)
    if (old.receiverName != now.receiverName || receiverDecisionChanged) add("receiver_name")
    if (old.receiverMobile != now.receiverMobile || receiverDecisionChanged) add("receiver_mobile")
    val goodsDecisionChanged = old.goodsProfileId != now.goodsProfileId
    if (old.goodsName != now.goodsName || goodsDecisionChanged) add("goods_name")
    if (old.packageName != now.packageName || goodsDecisionChanged) add("package")
    if (old.quantity != now.quantity) add("quantity")
    if (old.weight != now.weight) add("weight")
    if (old.volume != now.volume) add("volume")
    if (old.freight != now.freight) add("freight")
    if (old.advancePayment != now.advancePayment) add("advance_payment")
    if (old.advanceReturnType != now.advanceReturnType) add("advance_return_type")
    if (old.paymentType != now.paymentType) add("payment_type")
}

private fun resolveExactAssociations(
    fields: EditableFields,
    record: RecordEntity,
    senders: List<SenderProfileEntity>,
    receivers: List<ReceiverProfileEntity>,
    goods: List<GoodsProfileEntity>,
): EditableFields {
    var resolved = fields
    val edited = record.editedFieldSet()
    if (senders.isNotEmpty() && "sender_name" !in edited && fields.senderProfileId == null) {
        resolved = senderInput(resolved, fields.senderName.orEmpty(), senders)
    }
    if (receivers.isNotEmpty() && "receiver_name" !in edited && "receiver_mobile" !in edited && fields.receiverProfileId == null) {
        resolved = receiverInput(resolved, fields.receiverName.orEmpty(), receivers)
    }
    if (goods.isNotEmpty() && "goods_name" !in edited && "package" !in edited && fields.goodsProfileId == null) {
        resolved = goodsInput(resolved, fields.goodsName.orEmpty(), goods)
    }
    return resolved
}

// manual=true 表示这是人在键盘上改出来的值：警告只针对 AI 填的内容，人接手了就不再拦。
private fun senderInput(
    fields: EditableFields,
    value: String,
    profiles: List<SenderProfileEntity>,
    manual: Boolean = false,
): EditableFields {
    val resolution = AssociationMatcher.resolve(
        value, profiles, SenderProfileEntity::normalizedName,
        SenderProfileEntity::useCount, SenderProfileEntity::lastUsedAt,
    )
    val automatic = resolution.automatic
    return if (automatic != null) {
        fields.copy(
            senderName = automatic.name,
            senderProfileId = automatic.id,
            senderAssociationResolved = true,
            senderAssociationAccepted = false,
        )
    } else {
        fields.copy(
            senderName = value,
            senderProfileId = null,
            senderAssociationResolved = manual || !resolution.needsChoice,
            senderAssociationAccepted = false,
        )
    }
}

private fun receiverInput(
    fields: EditableFields,
    value: String,
    profiles: List<ReceiverProfileEntity>,
    manual: Boolean = false,
): EditableFields {
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
            receiverAssociationResolved = manual || !resolution.needsChoice,
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
        )
    } else {
        fields.copy(
            goodsName = value,
            packageName = if (fields.goodsProfileId != null) null else fields.packageName,
            goodsProfileId = null,
        )
    }
}
