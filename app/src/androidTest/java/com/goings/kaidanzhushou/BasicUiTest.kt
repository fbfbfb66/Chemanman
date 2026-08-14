package com.goings.kaidanzhushou

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.goings.kaidanzhushou.ui.BatchNameDialog
import com.goings.kaidanzhushou.ui.theme.KaidanTheme
import org.junit.Rule
import org.junit.Test

class BasicUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newBatchRequiresAName() {
        compose.setContent { KaidanTheme { BatchNameDialog("新建照片集", onDismiss = {}, onConfirm = {}) } }
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.onNodeWithText("照片集名称").performTextInput("测试批次")
        compose.onNodeWithText("确定").assertIsEnabled()
    }
}
