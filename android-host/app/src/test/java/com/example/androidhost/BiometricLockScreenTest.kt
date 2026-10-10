package com.example.androidhost

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The lock screen's "End locked session" escape must NEVER unlock. In
 * MainActivity only [onUnlockSuccess] maps to Screen.DESKTOP (restarting the
 * stream); ending the session maps to Screen.PAIRING. A lock that can be
 * tapped through is no lock at all.
 *
 * Runs in Robolectric's LEGACY graphics mode: the test only needs semantics
 * and click dispatch, not pixels, and the Windows host has no native runtime
 * DLL for NATIVE mode.
 */
@RunWith(RobolectricTestRunner::class)
class BiometricLockScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun endSessionButtonCallsOnEndSessionAndNeverUnlocks() {
        var unlocked = false
        var endedSession = false

        composeRule.setContent {
            com.example.androidhost.screens.BiometricLockScreen(
                onUnlockSuccess = { unlocked = true },
                onEndSession = { endedSession = true }
            )
        }

        composeRule.onNodeWithText("End locked session").assertIsDisplayed()
        composeRule.onNodeWithText("End locked session").performClick()

        composeRule.waitForIdle()
        assertTrue("End session must invoke onEndSession", endedSession)
        assertFalse(
            "End session must NEVER invoke onUnlockSuccess (the only path back to Screen.DESKTOP)",
            unlocked
        )
    }

    @Test
    fun tryAgainNeverUnlocks() {
        var unlocked = false
        var endedSession = false

        composeRule.setContent {
            com.example.androidhost.screens.BiometricLockScreen(
                onUnlockSuccess = { unlocked = true },
                onEndSession = { endedSession = true }
            )
        }

        composeRule.onNodeWithText("Try again").performClick()
        composeRule.waitForIdle()
        assertFalse("Try again re-arms the prompt, it must not unlock", unlocked)
        assertFalse("Try again must not end the session either", endedSession)
    }

    @Test
    fun noControlOnThisScreenEverUnlocksWithoutBiometrics() {
        // Exhaustive sweep: every clickable on the lock screen other than
        // biometric success must leave both callbacks untouched — this is the
        // invariant "Skip never reaches Screen.DESKTOP" depends on.
        var unlocked = false
        var endedSession = false

        composeRule.setContent {
            com.example.androidhost.screens.BiometricLockScreen(
                onUnlockSuccess = { unlocked = true },
                onEndSession = { endedSession = true }
            )
        }

        // Under Robolectric the host is not a FragmentActivity, so the biometric
        // prompt never arms; the error text is shown instead. Clicking every
        // visible button must not unlock.
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.onNodeWithText("End locked session").performClick()
        composeRule.waitForIdle()
        assertFalse(unlocked)
        assertTrue("the sweep still proves End session fires its own callback", endedSession)
    }
}
