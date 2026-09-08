package com.example.androidhost.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class WinitKeyMapTest {

    @Test
    fun testKnownGoodKeyMappings() {
        assertEquals(KeyEvent.KEYCODE_GRAVE, WinitKeyMap.toAndroidKeyCode(0))
        assertEquals(KeyEvent.KEYCODE_BACKSLASH, WinitKeyMap.toAndroidKeyCode(1))
        assertEquals(KeyEvent.KEYCODE_LEFT_BRACKET, WinitKeyMap.toAndroidKeyCode(2))
        assertEquals(KeyEvent.KEYCODE_RIGHT_BRACKET, WinitKeyMap.toAndroidKeyCode(3))
        assertEquals(KeyEvent.KEYCODE_COMMA, WinitKeyMap.toAndroidKeyCode(4))
        assertEquals(KeyEvent.KEYCODE_0, WinitKeyMap.toAndroidKeyCode(5))
        assertEquals(KeyEvent.KEYCODE_9, WinitKeyMap.toAndroidKeyCode(14))
        assertEquals(KeyEvent.KEYCODE_A, WinitKeyMap.toAndroidKeyCode(19))
        assertEquals(KeyEvent.KEYCODE_Z, WinitKeyMap.toAndroidKeyCode(44))
        assertEquals(KeyEvent.KEYCODE_ENTER, WinitKeyMap.toAndroidKeyCode(57))
        assertEquals(KeyEvent.KEYCODE_SPACE, WinitKeyMap.toAndroidKeyCode(62))
        assertEquals(KeyEvent.KEYCODE_TAB, WinitKeyMap.toAndroidKeyCode(63))
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.SHIFT_LEFT))
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.SHIFT_RIGHT))
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.CONTROL_LEFT))
        assertEquals(KeyEvent.KEYCODE_CTRL_RIGHT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.CONTROL_RIGHT))
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.ALT_LEFT))
        assertEquals(KeyEvent.KEYCODE_ALT_RIGHT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.ALT_RIGHT))
        assertEquals(KeyEvent.KEYCODE_CAPS_LOCK, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.CAPS_LOCK))
        assertEquals(KeyEvent.KEYCODE_NUM_LOCK, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.NUM_LOCK))
        assertEquals(KeyEvent.KEYCODE_ESCAPE, WinitKeyMap.toAndroidKeyCode(114))
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, WinitKeyMap.toAndroidKeyCode(140))
        assertEquals(KeyEvent.KEYCODE_VOLUME_DOWN, WinitKeyMap.toAndroidKeyCode(138))
        assertEquals(KeyEvent.KEYCODE_F1, WinitKeyMap.toAndroidKeyCode(159))
        assertEquals(KeyEvent.KEYCODE_F12, WinitKeyMap.toAndroidKeyCode(170))
    }

    @Test
    fun testUnmappedAndOutOfRangeKeycodes() {
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, WinitKeyMap.toAndroidKeyCode(-1))
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, WinitKeyMap.toAndroidKeyCode(9999))
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, WinitKeyMap.toAndroidKeyCode(194))
    }
}
