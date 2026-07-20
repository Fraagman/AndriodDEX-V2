package com.example.androidhost.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object InputInjectionManager {
    private const val TAG = "InputInjectionManager"
    
    // Hidden API mode for async injection (INJECT_INPUT_EVENT_MODE_ASYNC)
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    
    // Binder proxy to the system's IInputManager
    private var inputManagerProxy: Any? = null
    
    // The hidden injectInputEvent method
    private var injectMethod: java.lang.reflect.Method? = null
    
    // Tracks the down time to ensure MOVE and UP events are part of the same gesture
    private var lastDownTime: Long = 0L

    init {
        initShizukuInputManager()
    }

    /**
     * Initializes the IPC bridge to the system's InputManager using Shizuku.
     * This retrieves the IInputManager binder as the 'shell' user (UID 2000).
     */
    private fun initShizukuInputManager() {
        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "Shizuku is not running.")
            return
        }
        
        try {
            // Get the raw InputManager binder from ServiceManager via Shizuku IPC
            val binder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
            if (binder == null) {
                Log.e(TAG, "Failed to get INPUT_SERVICE binder from Shizuku.")
                return
            }
            
            // Wrap the binder to ensure all transactions are routed through the Shizuku proxy
            val shizukuBinder = ShizukuBinderWrapper(binder)
            
            // Use reflection to cast the IBinder to an IInputManager proxy interface
            val iInputManagerClass = Class.forName("android.hardware.input.IInputManager")
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            
            inputManagerProxy = asInterfaceMethod.invoke(null, shizukuBinder)
            
            // Resolve the hidden injectInputEvent method
            injectMethod = iInputManagerClass.getDeclaredMethod(
                "injectInputEvent", 
                android.view.InputEvent::class.java, 
                Int::class.javaPrimitiveType
            ).apply {
                isAccessible = true
            }
            
            Log.i(TAG, "Successfully initialized Shizuku InputManager proxy.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Shizuku InputManager proxy", e)
        }
    }

    /**
     * Constructs a raw MotionEvent and injects it into the specified VirtualDisplay.
     * 
     * @param x The X coordinate on the VirtualDisplay.
     * @param y The Y coordinate on the VirtualDisplay.
     * @param action The MotionEvent action (e.g., ACTION_DOWN, ACTION_MOVE, ACTION_UP).
     * @param displayId The explicit ID of the target VirtualDisplay.
     */
    fun injectTouchEvent(x: Float, y: Float, action: Int, displayId: Int) {
        if (inputManagerProxy == null || injectMethod == null) {
            initShizukuInputManager()
            if (inputManagerProxy == null) {
                Log.e(TAG, "Cannot inject event: Shizuku InputManager not initialized or permission denied.")
                return
            }
        }

        // Generate accurate timestamps to prevent the OS from rejecting stale events
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) {
            lastDownTime = now
        }
        // Fallback in case of a missing DOWN event (e.g., initial connection state)
        val currentDownTime = if (lastDownTime == 0L) now else lastDownTime

        val properties = arrayOf(PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        
        val coords = arrayOf(PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0.0f else 1.0f
            size = 1.0f
        })

        val event = MotionEvent.obtain(
            currentDownTime,
            now,
            action,
            1, // pointerCount
            properties,
            coords,
            0, // metaState
            0, // buttonState
            1.0f, // xPrecision
            1.0f, // yPrecision
            0, // deviceId
            0, // edgeFlags
            InputDevice.SOURCE_TOUCHSCREEN,
            0 // flags
        )
        
        // CRITICAL: Explicitly target the VirtualDisplay.
        // We use reflection for setDisplayId to ensure it works on API 29 (minSdk) where it was hidden.
        try {
            val setDisplayIdMethod = MotionEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
            setDisplayIdMethod.invoke(event, displayId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set displayId via reflection on MotionEvent", e)
        }

        // Inject the event as the 'shell' user
        try {
            val result = injectMethod?.invoke(inputManagerProxy, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean
            if (result == false) {
                Log.w(TAG, "InputManager.injectInputEvent returned false. The event may have been dropped.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject event via Shizuku binder IPC", e)
            // If the binder died (e.g., Shizuku restarted), clear the proxy so it re-initializes on the next attempt
            inputManagerProxy = null
            injectMethod = null
        } finally {
            event.recycle()
        }
        
        // Reset down time tracker on release or cancel
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            lastDownTime = 0L
        }
    }
}
