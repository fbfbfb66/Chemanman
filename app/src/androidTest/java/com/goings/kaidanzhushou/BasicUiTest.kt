package com.goings.kaidanzhushou

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.goings.kaidanzhushou.domain.PaymentType
import com.goings.kaidanzhushou.ui.IosSegments
import com.goings.kaidanzhushou.ui.theme.KaidanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BasicUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paymentSelectorShowsAndSelectsAllThreeTypes() {
        var selected: String? = null
        compose.setContent {
            KaidanTheme {
                IosSegments(PaymentType.entries.map { it.code to it.label }, selected) { selected = it }
            }
        }
        compose.onNodeWithText("现付").assertIsDisplayed()
        compose.onNodeWithText("提付").performClick()
        compose.runOnIdle { assertEquals("pay_arrival", selected) }
        compose.onNodeWithText("回付").assertIsDisplayed()
    }
}
