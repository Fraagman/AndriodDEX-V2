package com.example.androidhost.input

import android.view.KeyEvent

/**
 * Translates the keycodes sent by the Windows receiver into Android keycodes.
 *
 * The receiver sends `winit::keyboard::KeyCode as u32` (see
 * `rust-receiver/zc-core/src/main.rs`, `WindowEvent::KeyboardInput`). That is the
 * *ordinal* of winit 0.29's `KeyCode` enum, which declares no explicit discriminants
 * and no payload variants, so the ordinals are `Backquote = 0` through `F35 = 193`
 * in declaration order.
 *
 * Pinned to winit 0.29.15 (`rust-receiver/zc-core/Cargo.toml`). Bumping winit to a
 * release that inserts or reorders `KeyCode` variants invalidates this table; the
 * ordinals below must be regenerated from that version's `src/keyboard.rs`.
 */
object WinitKeyMap {

    // Modifier ordinals, needed by LocalInputDispatcher to maintain metaState.
    const val ALT_LEFT = 50
    const val ALT_RIGHT = 51
    const val CAPS_LOCK = 53
    const val CONTROL_LEFT = 55
    const val CONTROL_RIGHT = 56
    const val SUPER_LEFT = 58
    const val SUPER_RIGHT = 59
    const val SHIFT_LEFT = 60
    const val SHIFT_RIGHT = 61
    const val NUM_LOCK = 83
    const val META = 142

    /**
     * @return the Android keycode for [winitCode], or [KeyEvent.KEYCODE_UNKNOWN] when
     *         the key has no Android equivalent.
     */
    fun toAndroidKeyCode(winitCode: Int): Int = when (winitCode) {
        0 -> KeyEvent.KEYCODE_GRAVE
        1 -> KeyEvent.KEYCODE_BACKSLASH
        2 -> KeyEvent.KEYCODE_LEFT_BRACKET
        3 -> KeyEvent.KEYCODE_RIGHT_BRACKET
        4 -> KeyEvent.KEYCODE_COMMA

        // Digit0..Digit9 -> KEYCODE_0..KEYCODE_9
        in 5..14 -> KeyEvent.KEYCODE_0 + (winitCode - 5)

        15 -> KeyEvent.KEYCODE_EQUALS
        16 -> KeyEvent.KEYCODE_BACKSLASH // IntlBackslash
        17 -> KeyEvent.KEYCODE_RO
        18 -> KeyEvent.KEYCODE_YEN

        // KeyA..KeyZ -> KEYCODE_A..KEYCODE_Z
        in 19..44 -> KeyEvent.KEYCODE_A + (winitCode - 19)

        45 -> KeyEvent.KEYCODE_MINUS
        46 -> KeyEvent.KEYCODE_PERIOD
        47 -> KeyEvent.KEYCODE_APOSTROPHE
        48 -> KeyEvent.KEYCODE_SEMICOLON
        49 -> KeyEvent.KEYCODE_SLASH

        ALT_LEFT -> KeyEvent.KEYCODE_ALT_LEFT
        ALT_RIGHT -> KeyEvent.KEYCODE_ALT_RIGHT
        52 -> KeyEvent.KEYCODE_DEL // Backspace
        CAPS_LOCK -> KeyEvent.KEYCODE_CAPS_LOCK
        54 -> KeyEvent.KEYCODE_MENU // ContextMenu
        CONTROL_LEFT -> KeyEvent.KEYCODE_CTRL_LEFT
        CONTROL_RIGHT -> KeyEvent.KEYCODE_CTRL_RIGHT
        57 -> KeyEvent.KEYCODE_ENTER
        SUPER_LEFT -> KeyEvent.KEYCODE_META_LEFT
        SUPER_RIGHT -> KeyEvent.KEYCODE_META_RIGHT
        SHIFT_LEFT -> KeyEvent.KEYCODE_SHIFT_LEFT
        SHIFT_RIGHT -> KeyEvent.KEYCODE_SHIFT_RIGHT
        62 -> KeyEvent.KEYCODE_SPACE
        63 -> KeyEvent.KEYCODE_TAB

        64 -> KeyEvent.KEYCODE_HENKAN // Convert
        65 -> KeyEvent.KEYCODE_KANA // KanaMode
        66 -> KeyEvent.KEYCODE_ZENKAKU_HANKAKU // Lang1
        67 -> KeyEvent.KEYCODE_EISU // Lang2
        71 -> KeyEvent.KEYCODE_MUHENKAN // NonConvert

        72 -> KeyEvent.KEYCODE_FORWARD_DEL // Delete
        73 -> KeyEvent.KEYCODE_MOVE_END
        74 -> KeyEvent.KEYCODE_HELP
        75 -> KeyEvent.KEYCODE_MOVE_HOME
        76 -> KeyEvent.KEYCODE_INSERT
        77 -> KeyEvent.KEYCODE_PAGE_DOWN
        78 -> KeyEvent.KEYCODE_PAGE_UP

        79 -> KeyEvent.KEYCODE_DPAD_DOWN
        80 -> KeyEvent.KEYCODE_DPAD_LEFT
        81 -> KeyEvent.KEYCODE_DPAD_RIGHT
        82 -> KeyEvent.KEYCODE_DPAD_UP

        NUM_LOCK -> KeyEvent.KEYCODE_NUM_LOCK

        // Numpad0..Numpad9 -> KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9
        in 84..93 -> KeyEvent.KEYCODE_NUMPAD_0 + (winitCode - 84)

        94 -> KeyEvent.KEYCODE_NUMPAD_ADD
        95 -> KeyEvent.KEYCODE_DEL // NumpadBackspace
        96, 97 -> KeyEvent.KEYCODE_CLEAR // NumpadClear, NumpadClearEntry
        98 -> KeyEvent.KEYCODE_NUMPAD_COMMA
        99 -> KeyEvent.KEYCODE_NUMPAD_DOT
        100 -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
        101 -> KeyEvent.KEYCODE_NUMPAD_ENTER
        102 -> KeyEvent.KEYCODE_NUMPAD_EQUALS
        103 -> KeyEvent.KEYCODE_POUND // NumpadHash
        109 -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
        110 -> KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN
        111 -> KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN
        112 -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY // NumpadStar
        113 -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT

        114 -> KeyEvent.KEYCODE_ESCAPE
        117 -> KeyEvent.KEYCODE_SYSRQ // PrintScreen
        118 -> KeyEvent.KEYCODE_SCROLL_LOCK
        119 -> KeyEvent.KEYCODE_BREAK // Pause

        120 -> KeyEvent.KEYCODE_BACK // BrowserBack
        121 -> KeyEvent.KEYCODE_BOOKMARK // BrowserFavorites
        122 -> KeyEvent.KEYCODE_FORWARD // BrowserForward
        123 -> KeyEvent.KEYCODE_HOME // BrowserHome
        124 -> KeyEvent.KEYCODE_REFRESH // BrowserRefresh
        125 -> KeyEvent.KEYCODE_SEARCH // BrowserSearch

        128 -> KeyEvent.KEYCODE_EXPLORER // LaunchApp1
        129 -> KeyEvent.KEYCODE_CALCULATOR // LaunchApp2
        130 -> KeyEvent.KEYCODE_ENVELOPE // LaunchMail

        131 -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        132 -> KeyEvent.KEYCODE_MUSIC // MediaSelect
        133 -> KeyEvent.KEYCODE_MEDIA_STOP
        134 -> KeyEvent.KEYCODE_MEDIA_NEXT
        135 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS

        136 -> KeyEvent.KEYCODE_POWER
        137 -> KeyEvent.KEYCODE_SLEEP
        138 -> KeyEvent.KEYCODE_VOLUME_DOWN
        139 -> KeyEvent.KEYCODE_VOLUME_MUTE
        140 -> KeyEvent.KEYCODE_VOLUME_UP
        141 -> KeyEvent.KEYCODE_WAKEUP

        META -> KeyEvent.KEYCODE_META_LEFT

        149 -> KeyEvent.KEYCODE_COPY
        150 -> KeyEvent.KEYCODE_CUT
        153 -> KeyEvent.KEYCODE_PASTE

        157 -> KeyEvent.KEYCODE_KANA // Hiragana
        158 -> KeyEvent.KEYCODE_KATAKANA_HIRAGANA

        // F1..F12 -> KEYCODE_F1..KEYCODE_F12. Android has no keycodes past F12.
        in 159..170 -> KeyEvent.KEYCODE_F1 + (winitCode - 159)

        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    /** True when [winitCode] is a modifier that contributes to metaState. */
    fun isModifier(winitCode: Int): Boolean = when (winitCode) {
        ALT_LEFT, ALT_RIGHT, CONTROL_LEFT, CONTROL_RIGHT,
        SUPER_LEFT, SUPER_RIGHT, SHIFT_LEFT, SHIFT_RIGHT, META -> true
        else -> false
    }
}
