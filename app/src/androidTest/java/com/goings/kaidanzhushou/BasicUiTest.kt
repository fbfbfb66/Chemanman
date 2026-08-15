package com.goings.kaidanzhushou

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.goings.kaidanzhushou.data.local.GoodsProfileEntity
import com.goings.kaidanzhushou.data.local.ReceiverProfileEntity
import com.goings.kaidanzhushou.domain.PaymentType
import com.goings.kaidanzhushou.ui.GoodsDropdownField
import com.goings.kaidanzhushou.ui.IosSegments
import com.goings.kaidanzhushou.ui.ReceiverDropdownField
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

    @Test fun receiverDropdownShowsMaskedPhoneAndSelectsWholeProfile() {
        val profile = ReceiverProfileEntity("receiver", "任云飞", "任云飞", "15087192190", 3, 3, 1, 3)
        var selected: ReceiverProfileEntity? = null
        compose.setContent {
            KaidanTheme {
                ReceiverDropdownField("任运飞", listOf(profile), true, false, 0, {}, { selected = it }, {})
            }
        }
        compose.onNodeWithTag("receiver_dropdown_input").performClick()
        compose.onNodeWithText("150 **** 2190").assertIsDisplayed()
        compose.onNodeWithTag("receiver_option_receiver").performClick()
        compose.runOnIdle { assertEquals(profile, selected) }
    }

    @Test fun goodsDropdownSelectsBoundPackage() {
        val profile = GoodsProfileEntity("goods", "硫酸铜", "硫酸铜", "25公斤/袋", 2, 2, 1, 2)
        var selected: GoodsProfileEntity? = null
        compose.setContent {
            KaidanTheme {
                GoodsDropdownField("硫酸同", listOf(profile), true, false, 0, {}, { selected = it }, {})
            }
        }
        compose.onNodeWithTag("goods_dropdown_input").performClick()
        compose.onNodeWithText("25公斤/袋").assertIsDisplayed()
        compose.onNodeWithTag("goods_option_goods").performClick()
        compose.runOnIdle { assertEquals(profile, selected) }
    }
}
