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
        // Dialogs dim what is behind them; on the VirtualDisplay that would darken the
        // desktop behind the shell window. The focusable flags stay at their defaults:
        // input reaches the shell through LocalInputDispatcher's direct view dispatch,
        // which does not depend on window focus, and there is no IME on this display
        // for ALT_FOCUSABLE_IM to matter.
        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        savedStateRegistryController.performRestore(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(context).apply {
            setContent {
                DesktopShell()
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
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /**
     * The framework never calls this for a Presentation, but the Compose UI inside
     * expects a fully resumed lifecycle owner, so [DisplayService] invokes it right
     * after [show].
     */
    fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onStop() {
        super.onStop()
        LocalInputDispatcher.detach()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun dismiss() {
        // Full lifecycle order before teardown: PAUSE, then the framework's
        // dismiss path drives our onStop (STOP), then DESTROY releases the
        // ViewModelStore the ComposeView may still be reading.
        onPause()
        super.dismiss()
        LocalInputDispatcher.detach()
        contentView = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
