package com.example.androidhost.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.io.DataInputStream
import java.io.EOFException
import java.net.ServerSocket
import java.nio.ByteBuffer

class DesktopAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11y"
        private const val INPUT_PORT = 55557
        var instance: DesktopAccessibilityService? = null
            private set
        var hasReceivedData = false
            private set
    }

    private var inputServerThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var cursorView: CursorView? = null
    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Current cursor position (atomic updates from network thread)
    @Volatile private var cursorX: Float = 960f
    @Volatile private var cursorY: Float = 540f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service connected")
        startInputServer()
        showCursorOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopInputServer()
        removeCursorOverlay()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for input injection
    }

    override fun onInterrupt() {
        // Not used
    }

    // ---- Cursor Overlay ----

    private var virtualDisplayId = android.view.Display.DEFAULT_DISPLAY

    private fun findVirtualDisplay(): android.view.Display? {
        val displayManager = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        for (display in displayManager.displays) {
            if (display.name == "AndroidDex") {
                virtualDisplayId = display.displayId
                return display
            }
        }
        return null
    }

    private fun showCursorOverlay() {
        val targetDisplay = findVirtualDisplay()
        if (targetDisplay == null) {
            Log.e(TAG, "AndroidDex VirtualDisplay not found, retrying in 1s...")
            mainHandler.postDelayed({ showCursorOverlay() }, 1000)
            return
        }

        val displayContext = createDisplayContext(targetDisplay)
        // TYPE_ACCESSIBILITY_OVERLAY might require the service context, so we create a window context from the display
        val windowContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            createWindowContext(targetDisplay, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        } else {
            displayContext
        }

        windowManager = windowContext.getSystemService(WINDOW_SERVICE) as WindowManager
        cursorView = CursorView(windowContext).apply {
            setWillNotDraw(false)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        mainHandler.post {
            try {
                windowManager?.addView(cursorView, params)
                Log.d(TAG, "Cursor overlay added to VirtualDisplay (ID: $virtualDisplayId)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add cursor overlay", e)
            }
        }
    }

    private fun removeCursorOverlay() {
        mainHandler.post {
            try {
                cursorView?.let { windowManager?.removeView(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove cursor overlay", e)
            }
            cursorView = null
        }
    }

    private fun updateCursorPosition(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        mainHandler.post {
            cursorView?.invalidate()
        }
    }

    inner class CursorView(context: android.content.Context) : View(context) {
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Scale from 1920x1080 virtual coords to actual screen size
            val screenW = width.toFloat()
            val screenH = height.toFloat()
            val sx = cursorX * screenW / 1920f
            val sy = cursorY * screenH / 1080f
            canvas.drawCircle(sx, sy, 12f, cursorPaint)
            canvas.drawCircle(sx, sy, 12f, borderPaint)
        }
    }

    // ---- Input Server (via QUIC polling) ----

    private fun startInputServer() {
        com.example.androidhost.quic.QuicServer.startServer(4433, filesDir.absolutePath)
        
        inputServerThread = Thread {
            val buffer = ByteArray(1024 * 1024)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val bytesRead = com.example.androidhost.quic.QuicServer.pollInput(buffer)
                    if (bytesRead > 0) {
                        hasReceivedData = true
                        Log.d(TAG, "Input event received via QUIC")
                        val data = buffer.copyOf(bytesRead)
                        handleInputEvent(data)
                    } else {
                        Thread.sleep(10) // Small delay if no data
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Input polling error", e)
                }
            }
        }.apply {
            name = "InputPollingThread"
            isDaemon = true
            start()
        }
    }

    private fun stopInputServer() {
        inputServerThread?.interrupt()
        inputServerThread = null
    }

    // ---- Protobuf Decoding (wire-format compatible with input.proto InputEvent) ----
    //
    // InputEvent { oneof event { MouseEvent mouse = 1; KeyboardEvent keyboard = 2; } }
    // MouseEvent { uint32 x=1; uint32 y=2; uint32 buttons=3; uint64 timestamp=4; }
    // KeyboardEvent { uint32 keycode=1; bool pressed=2; uint32 modifiers=3; uint64 timestamp=4; }
    //
    // Wire format for InputEvent:
    //   field 1 (mouse):    tag = (1 << 3) | 2 = 0x0A, then varint length, then MouseEvent bytes
    //   field 2 (keyboard): tag = (2 << 3) | 2 = 0x12, then varint length, then KeyboardEvent bytes

    private fun handleInputEvent(data: ByteArray) {
        var pos = 0

        while (pos < data.size) {
            val tagResult = readVarint(data, pos) ?: return
            pos = tagResult.second
            val tag = tagResult.first.toInt()

            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (wireType != 2) {
                // Not a length-delimited field, skip
                return
            }

            val lengthResult = readVarint(data, pos) ?: return
            pos = lengthResult.second
            val fieldLen = lengthResult.first.toInt()

            if (pos + fieldLen > data.size) return

            val fieldData = data.copyOfRange(pos, pos + fieldLen)
            pos += fieldLen

            when (fieldNumber) {
                1 -> { // MouseEvent
                    val mouse = decodeMouseEvent(fieldData)
                    if (mouse != null) {
                        onMouseEvent(mouse)
                    }
                }
                2 -> { // KeyboardEvent
                    val kb = decodeKeyboardEvent(fieldData)
                    if (kb != null) {
                        onKeyboardEvent(kb)
                    }
                }
            }
        }
    }

    data class ParsedMouseEvent(val x: Int, val y: Int, val buttons: Int, val timestamp: Long)
    data class ParsedKeyboardEvent(val keycode: Int, val pressed: Boolean, val modifiers: Int, val timestamp: Long)

    private fun decodeMouseEvent(data: ByteArray): ParsedMouseEvent? {
        var pos = 0
        var x = 0; var y = 0; var buttons = 0; var timestamp = 0L

        while (pos < data.size) {
            val tagResult = readVarint(data, pos) ?: break
            pos = tagResult.second
            val tag = tagResult.first.toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (wireType != 0) {
                // Skip unknown wire types for robustness
                break
            }

            val valResult = readVarint(data, pos) ?: break
            pos = valResult.second

            when (fieldNumber) {
                1 -> x = valResult.first.toInt()
                2 -> y = valResult.first.toInt()
                3 -> buttons = valResult.first.toInt()
                4 -> timestamp = valResult.first
            }
        }
        return ParsedMouseEvent(x, y, buttons, timestamp)
    }

    private fun decodeKeyboardEvent(data: ByteArray): ParsedKeyboardEvent? {
        var pos = 0
        var keycode = 0; var pressed = false; var modifiers = 0; var timestamp = 0L

        while (pos < data.size) {
            val tagResult = readVarint(data, pos) ?: break
            pos = tagResult.second
            val tag = tagResult.first.toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (wireType != 0) break

            val valResult = readVarint(data, pos) ?: break
            pos = valResult.second

            when (fieldNumber) {
                1 -> keycode = valResult.first.toInt()
                2 -> pressed = valResult.first != 0L
                3 -> modifiers = valResult.first.toInt()
                4 -> timestamp = valResult.first
            }
        }
        return ParsedKeyboardEvent(keycode, pressed, modifiers, timestamp)
    }

    private fun readVarint(data: ByteArray, startPos: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = startPos
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) {
                return Pair(result, pos)
            }
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }

    // ---- Event Handlers ----

    private var lastButtons = 0

    private fun onMouseEvent(event: ParsedMouseEvent) {
        val x = event.x.toFloat()
        val y = event.y.toFloat()

        // Always update cursor position
        updateCursorPosition(x, y)

        // Detect button press transitions (edge-triggered, not level-triggered)
        val wasPressed = lastButtons and 1 != 0
        val isPressed = event.buttons and 1 != 0
        lastButtons = event.buttons

        if (isPressed && !wasPressed) {
            // Left mouse button just pressed — inject click
            Log.d(TAG, "Injecting click at $x, $y")
            injectClick(x, y)
        }
    }

    private fun onKeyboardEvent(event: ParsedKeyboardEvent) {
        Log.d(TAG, "Keyboard event: keycode=${event.keycode}, pressed=${event.pressed}")
        // Keyboard injection will be implemented when we add IME support
    }

    // ---- Gesture Injection ----

    fun injectClick(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 10)
        val builder = GestureDescription.Builder().addStroke(stroke)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && virtualDisplayId != android.view.Display.DEFAULT_DISPLAY) {
            builder.setDisplayId(virtualDisplayId)
        }
        
        val gesture = builder.build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Gesture completed at $x, $y on display $virtualDisplayId")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e(TAG, "Gesture cancelled at $x, $y on display $virtualDisplayId")
            }
        }, null)

        if (!dispatched) {
            Log.e(TAG, "Failed to dispatch gesture at $x, $y on display $virtualDisplayId")
        }
    }

    fun injectScroll(x: Float, y: Float, direction: Int) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y - (direction * 100))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val builder = GestureDescription.Builder().addStroke(stroke)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && virtualDisplayId != android.view.Display.DEFAULT_DISPLAY) {
            builder.setDisplayId(virtualDisplayId)
        }
        
        val gesture = builder.build()
        dispatchGesture(gesture, null, null)
    }
}
