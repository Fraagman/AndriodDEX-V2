package com.example.androidhost.input

import android.view.KeyEvent
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeActionMultipleTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testActionMultiple() {
        val text = mutableStateOf("")
        val focusRequester = FocusRequester()
        var composeView: android.view.View? = null

        composeTestRule.setContent {
            composeView = LocalView.current
            BasicTextField(
                value = text.value,
                onValueChange = { text.value = it },
                modifier = Modifier.focusRequester(focusRequester)
            )
        }

        composeTestRule.runOnIdle {
            focusRequester.requestFocus()
        }

        composeTestRule.runOnIdle {
            val event = KeyEvent(0L, "é", 0, 0)
            composeView?.dispatchKeyEvent(event)
        }

        composeTestRule.runOnIdle {
            assertEquals("é", text.value)
        }
    }
}
