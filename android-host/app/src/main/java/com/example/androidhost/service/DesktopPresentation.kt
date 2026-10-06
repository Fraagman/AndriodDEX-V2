package com.example.androidhost.service

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import android.app.Application
import com.example.androidhost.DesktopShell

import com.example.androidhost.input.LocalInputDispatcher

class DesktopPresentation(
    context: Context,
    display: Display
) : Presentation(context, display), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, HasDefaultViewModelProviderFactory {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    /** The Compose view that receives input from the PC. */
    private var contentView: ComposeView? = null

    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var heartbeatTick = 0
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            // Always push a fresh frame so the encoder surface never starves.
            // ComposeView.invalidate() schedules a Choreographer frame on the
            // VirtualDisplay's SurfaceFlinger slot, which gives the H.264 encoder
            // the pixel data it needs to produce an IDR on the next PARAMETER_KEY_REQUEST_SYNC_FRAME.
            contentView?.invalidate()
            heartbeatTick++
            // Request a keyframe every ~1 s (10 × 100 ms) so a connecting client
            // never waits more than one second for a decodable picture.
            if (heartbeatTick % 10 == 0) {
                DisplayService.requestKeyframe()
            }
            heartbeatHandler.postDelayed(this, 100)
        }
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras().apply {
            set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, context.applicationContext as Application)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND or
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )

        savedStateRegistryController.performRestore(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(context).apply {
            setContent {
                DesktopShell(displayId = display.displayId)
            }
        }

        // Wire up lifecycle, view model store, and saved state for Compose
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        setContentView(composeView)
        contentView = composeView

        // Input from the PC is dispatched straight into this view hierarchy.
        LocalInputDispatcher.attach(composeView)
    }

    override fun onStart() {
        super.onStart()
        // onCreate only runs once; re-attach here so a stop/start cycle does not
        // silently leave the desktop unresponsive to the PC.
        contentView?.let { LocalInputDispatcher.attach(it) }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        // Kick an immediate keyframe so the first encoded frame is an IDR,
        // giving a late-joining receiver a decodable picture right away.
        DisplayService.requestKeyframe()
        heartbeatHandler.postDelayed(heartbeatRunnable, 100)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStop() {
        super.onStop()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        LocalInputDispatcher.detach()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun dismiss() {
        super.dismiss()
        LocalInputDispatcher.detach()
        contentView = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
