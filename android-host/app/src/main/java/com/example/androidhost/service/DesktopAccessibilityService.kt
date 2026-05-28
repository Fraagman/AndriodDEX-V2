package com.example.androidhost.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class DesktopAccessibilityService : AccessibilityService() {

    companion object {
        var instance: DesktopAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("A11y", "Service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Do nothing
    }

    override fun onInterrupt() {
        // Do nothing
    }

    fun injectClick(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 10)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d("A11y", "Gesture completed at $x, $y")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e("A11y", "Gesture cancelled at $x, $y")
            }
        }, null)

        if (!dispatched) {
            Log.e("A11y", "Failed to dispatch gesture")
        }
    }

    fun injectScroll(x: Float, y: Float, direction: Int) {
        // To be implemented or extended later
        val path = Path().apply {
            moveTo(x, y)
            // simple vertical scroll example
            lineTo(x, y - (direction * 100))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
