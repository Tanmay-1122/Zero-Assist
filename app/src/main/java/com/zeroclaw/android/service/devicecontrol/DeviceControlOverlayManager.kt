/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.devicecontrol

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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
import com.zeroclaw.android.ui.screen.voice.DeviceControlUiContent
import com.zeroclaw.android.ui.theme.ZeroAssistTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun show() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun hide() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

object DeviceControlOverlayManager {
    private const val TAG = "DeviceControlOverlay"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        val appContext = context.applicationContext
        windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        scope.launch {
            DeviceControlMonitor.state.collectLatest { state ->
                if (state.isActive) {
                    showOverlay(appContext, state)
                } else {
                    hideOverlay()
                }
            }
        }
    }

    private fun showOverlay(context: Context, state: DeviceControlState) {
        val wm = windowManager ?: return

        // Check overlay permission or accessibility service presence
        val canDrawOverlays = Settings.canDrawOverlays(context)
        val hasA11y = DeviceControlAccessibilityService.isRunning()
        if (!canDrawOverlays && !hasA11y) {
            Log.w(TAG, "Cannot draw overlay: SYSTEM_ALERT_WINDOW permission not granted and a11y not running")
            return
        }

        if (overlayView == null) {
            val owner = OverlayLifecycleOwner()
            lifecycleOwner = owner

            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    ZeroAssistTheme(darkTheme = true) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(modifier = Modifier.widthIn(max = 560.dp)) {
                                DeviceControlUiContent(
                                    state = state,
                                    onCancelControl = { DeviceControlMonitor.requestCancel() },
                                )
                            }
                        }
                    }
                }
            }

            val windowType = when {
                canDrawOverlays && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                hasA11y -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 32
            }

            try {
                wm.addView(view, params)
                owner.show()
                overlayView = view
                Log.i(TAG, "System overlay window attached successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach system overlay window: ${e.message}", e)
                owner.destroy()
                lifecycleOwner = null
                overlayView = null
            }
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val owner = lifecycleOwner

        try {
            owner?.hide()
            wm.removeView(view)
            owner?.destroy()
            Log.i(TAG, "System overlay window removed")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay view: ${e.message}", e)
        } finally {
            overlayView = null
            lifecycleOwner = null
        }
    }
}
