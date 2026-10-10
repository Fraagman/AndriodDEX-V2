package com.example.androidhost

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Cross-language golden vectors for the InputEvent wire bytes the Rust flood
 * policy classifies (rust_quic_server/src/lib.rs tests
 * `classifier_reads_the_oneof_tag_and_mouse_buttons` etc.). The Rust side reads
 * the oneof tag byte (every member is a message: mouse=0x0A, keyboard=0x12,
 * scroll=0x1A, text=0x2A, open_app=0x32, nav=0x3A) and MouseEvent's `buttons`
 * (field 3, proto3-default 0 when absent). If either side drifts, one of the
 * two test suites fails.
 */
class InputEventGoldenTest {

    @Test
    fun mouseEventWithoutButtonsMatchesTheRustGoldenVector() {
        // MouseEvent{x=1, y=1, buttons=0}: proto3 omits the zero-valued buttons.
        val mouse = com.androiddex.protocol.MouseEvent.newBuilder()
            .setX(1)
            .setY(1)
            .build()
        val event = com.androiddex.protocol.InputEvent.newBuilder()
            .setMouse(mouse)
            .build()
        assertArrayEquals(
            byteArrayOf(0x0A, 0x04, 0x08, 0x01, 0x10, 0x01),
            event.toByteArray()
        )
    }

    @Test
    fun mouseEventWithButtonsMatchesTheRustGoldenVector() {
        val mouse = com.androiddex.protocol.MouseEvent.newBuilder()
            .setX(1)
            .setY(1)
            .setButtons(1)
            .build()
        val event = com.androiddex.protocol.InputEvent.newBuilder()
            .setMouse(mouse)
            .build()
        assertArrayEquals(
            byteArrayOf(0x0A, 0x06, 0x08, 0x01, 0x10, 0x01, 0x18, 0x01),
            event.toByteArray()
        )
    }

    @Test
    fun keyboardEventMatchesTheRustGoldenVector() {
        val keyboard = com.androiddex.protocol.KeyboardEvent.newBuilder()
            .setKeycode(57)
            .setPressed(true)
            .build()
        val event = com.androiddex.protocol.InputEvent.newBuilder()
            .setKeyboard(keyboard)
            .build()
        assertArrayEquals(
            byteArrayOf(0x12, 0x04, 0x08, 0x39, 0x10, 0x01),
            event.toByteArray()
        )
    }

    @Test
    fun scrollTextNavOpenAppMatchTheirOneofTags() {
        val scroll = com.androiddex.protocol.ScrollEvent.newBuilder()
            .setX(100).setY(200).setVScroll(0f).setHScroll(0f)
            .build()
        assertArrayEquals(
            byteArrayOf(0x1A.toByte()),
            com.androiddex.protocol.InputEvent.newBuilder().setScroll(scroll).build()
                .toByteArray().copyOf(1)
        )

        val text = com.androiddex.protocol.TextEvent.newBuilder().setText("hi").build()
        assertArrayEquals(
            byteArrayOf(0x2A),
            com.androiddex.protocol.InputEvent.newBuilder().setText(text).build()
                .toByteArray().copyOf(1)
        )

        val openApp = com.androiddex.protocol.OpenAppRequest.newBuilder()
            .setPackageName("browser").build()
        assertArrayEquals(
            byteArrayOf(0x32),
            com.androiddex.protocol.InputEvent.newBuilder().setOpenApp(openApp).build()
                .toByteArray().copyOf(1)
        )

        val nav = com.androiddex.protocol.NavEvent.newBuilder()
            .setAction(com.androiddex.protocol.NavAction.NAV_HOME).build()
        assertArrayEquals(
            byteArrayOf(0x3A.toByte()),
            com.androiddex.protocol.InputEvent.newBuilder().setNav(nav).build()
                .toByteArray().copyOf(1)
        )
    }
}
