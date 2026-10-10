package com.example.androidhost.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LocalInputDispatcherTest {

    private fun runUiTasks() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun idleFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    @Test
    fun testExactCornersScaling() {
        // Target 1920x1080
        val targetWidth = 1920
        val targetHeight = 1080

        // Top-left corner: (0, 0)
        assertEquals(0.0f, LocalInputDispatcher.scaleX(0, targetWidth), 0.001f)
        assertEquals(0.0f, LocalInputDispatcher.scaleY(0, targetHeight), 0.001f)

        // Bottom-right corner: (1919, 1079)
        assertEquals(1919.0f, LocalInputDispatcher.scaleX(1919, targetWidth), 0.001f)
        assertEquals(1079.0f, LocalInputDispatcher.scaleY(1079, targetHeight), 0.001f)
    }

    @Test
    fun testMidpointScaling() {
        val targetWidth = 1920
        val targetHeight = 1080

        // Midpoint: (960, 540)
        assertEquals(960.0f, LocalInputDispatcher.scaleX(960, targetWidth), 0.001f)
        assertEquals(540.0f, LocalInputDispatcher.scaleY(540, targetHeight), 0.001f)

        // Midpoint on non-1080p target display (e.g. 1280x720)
        val altWidth = 1280
        val altHeight = 720
        assertEquals(640.0f, LocalInputDispatcher.scaleX(960, altWidth), 0.001f)
        assertEquals(360.0f, LocalInputDispatcher.scaleY(540, altHeight), 0.001f)
    }

    @Test
    fun testOutOfRangeInputClamped() {
        val targetWidth = 1920
        val targetHeight = 1080

        // Below minimum (negative coordinates) must clamp to 0.0f
        assertEquals(0.0f, LocalInputDispatcher.scaleX(-100, targetWidth), 0.001f)
        assertEquals(0.0f, LocalInputDispatcher.scaleY(-50, targetHeight), 0.001f)

        // Above maximum must clamp to (WIRE_MAX - 1)
        assertEquals(1919.0f, LocalInputDispatcher.scaleX(5000, targetWidth), 0.001f)
        assertEquals(1079.0f, LocalInputDispatcher.scaleY(3000, targetHeight), 0.001f)
    }

    /**
     * The wire modifier bitmask (input.proto `Modifier` enum: shift=1, ctrl=2,
     * alt=4, super=8) must map onto the Android META flags the dispatched
     * KeyEvents carry. This pins the two sides of the wire together.
     */
    @Test
    fun testWireModifiersToAndroidMeta() {
        assertEquals(0, LocalInputDispatcher.wireModifiersToAndroidMeta(0))
        assertEquals(
            KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
            LocalInputDispatcher.wireModifiersToAndroidMeta(1)
        )
        assertEquals(
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
            LocalInputDispatcher.wireModifiersToAndroidMeta(2)
        )
        assertEquals(
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
            LocalInputDispatcher.wireModifiersToAndroidMeta(4)
        )
        assertEquals(
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON,
            LocalInputDispatcher.wireModifiersToAndroidMeta(8)
        )
        assertEquals(
            KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
            LocalInputDispatcher.wireModifiersToAndroidMeta(3)
        )
    }

    @Test
    fun testTextInsertOwnership() {
        val ownerA = Any()
        val ownerB = Any()

        var receivedA: String? = null
        var receivedB: String? = null

        // Owner A registers
        LocalInputDispatcher.registerComposeTarget(ownerA, { receivedA = it }, { _, _ -> true })
        runUiTasks()

        // Owner B registers, overwriting A
        LocalInputDispatcher.registerComposeTarget(ownerB, { receivedB = it }, { _, _ -> true })
        runUiTasks()

        // Owner A deregisters (stale blur)
        LocalInputDispatcher.registerComposeTarget(ownerA, null, null)
        runUiTasks()

        // B's callback must survive. Let's fire some text and check
        LocalInputDispatcher.onText("hello")
        runUiTasks()

        assertNull("A should not receive text", receivedA)
        assertEquals("B should survive the stale deregistration", "hello", receivedB)
    }

    /**
     * Ownership alternation: the most recent registration wins, and a dead
     * WebView's stale registration never steals text from the live target.
     *
     * Robolectric does not emulate `View.dispatchAttachedToWindow` (no
     * `mAttachInfo` is ever set), so a unit-test WebView can never satisfy the
     * `isLive()` gate — the delivery-to-live-WebView leg is therefore verified
     * end-to-end on the device instead (browser page typing over QUIC).
     */
    @Test
    fun testMutualExclusion() {
        val webViewOwner = Any()
        val textOwner = Any()

        var receivedText: String? = null
        var receivedKey: Int? = null
        var webViewReceivedText: CharSequence? = null

        // A WebView that is never attached: not live.
        val webView = WebView(RuntimeEnvironment.getApplication())
        val bridge = object : WebViewInputBridge {
            override fun insertText(text: CharSequence) { webViewReceivedText = text }
        }

        // 1. WebView claims
        LocalInputDispatcher.registerWebViewBridge(webViewOwner, bridge, webView)
        runUiTasks()

        // 2. Compose claims
        LocalInputDispatcher.registerComposeTarget(textOwner, { receivedText = it }, { key, _ -> receivedKey = key; true })
        runUiTasks()

        // Ensure only Compose receives
        LocalInputDispatcher.onText("hello")
        runUiTasks()

        assertNull("dead WebView must not receive text", webViewReceivedText)
        assertEquals("Compose should receive text", "hello", receivedText)

        // Ensure only Compose receives key
        LocalInputDispatcher.onKey(57, true)
        runUiTasks()
        assertEquals("Compose should receive Enter key", KeyEvent.KEYCODE_ENTER, receivedKey)

        // Reset
        receivedText = null
        webViewReceivedText = null
        receivedKey = null

        // 3. WebView claims again (reverse order)
        LocalInputDispatcher.registerWebViewBridge(webViewOwner, bridge, webView)
        runUiTasks()

        LocalInputDispatcher.onText("world")
        runUiTasks()

        // Not attached => not live => the text must be dropped, not stolen
        assertNull("Compose should not receive text", receivedText)
        assertNull("dead WebView must not receive text", webViewReceivedText)

        // 4. Compose claims again to test repeated alternations
        LocalInputDispatcher.registerComposeTarget(textOwner, { receivedText = it }, { key, _ -> receivedKey = key; true })
        runUiTasks()
        LocalInputDispatcher.onText("third")
        runUiTasks()
        assertEquals("Compose should receive third text", "third", receivedText)
    }

    /**
     * The §3.7 regression: a dead (detached) WebView must never swallow text —
     * its stale registration is inert and the drop is logged, not silently lost
     * to a phantom bridge.
     */
    @Test
    fun testDeadWebViewDoesNotSwallowText() {
        val webViewOwner = Any()
        val deadOwner = Any()
        var receivedText: String? = null
        var deadReceived: CharSequence? = null

        // A WebView that is never attached to a window: not live.
        val deadView = WebView(RuntimeEnvironment.getApplication())
        val bridge = object : WebViewInputBridge {
            override fun insertText(text: CharSequence) { deadReceived = text }
        }
        LocalInputDispatcher.registerWebViewBridge(deadOwner, bridge, deadView)
        runUiTasks()

        LocalInputDispatcher.onText("dropped")
        runUiTasks()
        assertNull("dead WebView must not receive text", deadReceived)

        // A live Compose target registered afterwards still works.
        LocalInputDispatcher.registerComposeTarget(webViewOwner, { receivedText = it }, { _, _ -> false })
        runUiTasks()
        LocalInputDispatcher.onText("alive")
        runUiTasks()
        assertEquals("alive", receivedText)
    }

    /**
     * Key auto-repeat: the dispatcher synthesises repeats for keyboard-routed
     * keys (the receiver suppresses OS repeats), with a 400 ms delay then a 50 ms
     * period. Releasing the key stops the loop.
     */
    @Test
    fun testKeyAutoRepeat() {
        val owner = Any()
        var delPresses = 0
        LocalInputDispatcher.registerComposeTarget(
            owner,
            onText = { },
            onKey = { keyCode, pressed ->
                if (keyCode == KeyEvent.KEYCODE_DEL && pressed) delPresses++
                true
            }
        )
        runUiTasks()

        // winit 52 = Backspace
        LocalInputDispatcher.onKey(52, true)
        runUiTasks()
        assertEquals(1, delPresses)

        idleFor(1000)
        // 400 ms delay + 50 ms period -> repeats at t=450,500,...,1000 => 12 more
        assertTrue(
            "expected ~13 total, got $delPresses",
            delPresses in 10..16
        )

        LocalInputDispatcher.onKey(52, false)
        runUiTasks()
        val atRelease = delPresses
        idleFor(1000)
        assertEquals("release must stop the repeat loop", atRelease, delPresses)
    }

    /**
     * A new key press takes over the repeat slot (Windows behaviour), and the
     * previous key's release must not cancel the new key's repeats.
     */
    @Test
    fun testRepeatFollowsNewestKey() {
        val owner = Any()
        val presses = HashMap<Int, Int>()
        LocalInputDispatcher.registerComposeTarget(
            owner,
            onText = { },
            onKey = { keyCode, pressed ->
                if (pressed) presses[keyCode] = (presses[keyCode] ?: 0) + 1
                true
            }
        )
        runUiTasks()

        LocalInputDispatcher.onKey(52, true) // Backspace
        runUiTasks()
        LocalInputDispatcher.onKey(63, true) // Tab — control key, takes over the repeat slot
        runUiTasks()
        idleFor(500)
        LocalInputDispatcher.onKey(52, false) // old key released — must not stop Tab
        runUiTasks()
        idleFor(500)

        val tabPresses = presses[KeyEvent.KEYCODE_TAB] ?: 0
        val bsPresses = presses[KeyEvent.KEYCODE_DEL] ?: 0
        assertEquals("Backspace pressed exactly once", 1, bsPresses)
        assertTrue("Tab must keep repeating after Backspace release (got $tabPresses)", tabPresses >= 3)
    }
}
