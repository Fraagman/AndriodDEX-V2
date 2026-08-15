package com.example.androidhost.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional service providing the three global navigation actions the desktop shell
 * cannot perform for itself: back, home and recents.
 *
 * This service is **not required**. Pointer and keyboard input go through
 * `LocalInputDispatcher`, which dispatches into our own Compose hierarchy with no
 * permissions at all and handles hover, scroll and multi-button mice better than
 * gesture dispatch ever could. Nothing in the shell depends on this service being
 * enabled; when it is off, the shell simply hides the three buttons it powers.
 */
class DesktopAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DesktopA11y"

        @Volatile
        private var instance: DesktopAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)

        /**
         * True while the service is bound. The shell collects this to decide whether to
         * show the back/home/recents buttons.
         */
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        /**
         * Authoritative check against the system's list of running accessibility
         * services. Used to seed [isConnected] at startup, since the shell may compose
         * before (or long after) `onServiceConnected` fires.
         */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return false
            val self = ComponentName(context, DesktopAccessibilityService::class.java)
            val enabled = manager.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            return enabled.any { info ->
                val id = info.id ?: return@any false
                id == self.flattenToString() || id == self.flattenToShortString()
            }
        }

        /** Re-syncs [isConnected] with the system's view of the world. */
        fun refresh(context: Context) {
            _isConnected.value = isEnabled(context)
        }

        /** Opens the system accessibility settings so the user can enable the service. */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /** @return true when the action was handed to the system. */
        fun performBack(): Boolean = perform(GLOBAL_ACTION_BACK)

        /** @return true when the action was handed to the system. */
        fun performHome(): Boolean = perform(GLOBAL_ACTION_HOME)

        /** @return true when the action was handed to the system. */
        fun performRecents(): Boolean = perform(GLOBAL_ACTION_RECENTS)

        private fun perform(action: Int): Boolean {
            val service = instance
            if (service == null) {
                Log.d(TAG, "Global action $action ignored: service not enabled")
                return false
            }
            return service.performGlobalAction(action)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.d(TAG, "Connected — global navigation actions available")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        _isConnected.value = false
        Log.d(TAG, "Unbound — global navigation actions unavailable")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    /**
     * We subscribe to no event types (see `accessibility_service_config.xml`), so this
     * never fires. Reading the screen's content is not something this service does.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
