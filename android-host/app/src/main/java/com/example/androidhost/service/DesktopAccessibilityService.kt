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
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.launch
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
        
        // Start collecting from InputManager using the service's lifecycle
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            InputManager.mouseEvents.collect { onMouseEvent(it) }
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            InputManager.keyboardEvents.collect { onKeyboardEvent(it) }
        }
        
        showCursorOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
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

    // Input polling moved to InputManager

    // ---- Event Handlers ----

    private var lastButtons = 0

    private fun onMouseEvent(event: InputManager.ParsedMouseEvent) {
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

    private fun onKeyboardEvent(event: InputManager.ParsedKeyboardEvent) {
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
