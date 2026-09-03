/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.devicecontrol

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zeroclaw.android.ui.component.DeviceControlOverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * True while a [DeviceControlState] should drive the on-screen overlay:
 * an active session that has not reached a terminal status yet.
 */
fun DeviceControlState.shouldShowOverlay(): Boolean =
    isActive && when (status) {
        DeviceControlStatus.COMPLETED,
        DeviceControlStatus.FAILED,
        DeviceControlStatus.CANCELLED,
        -> false

        else -> true
    }

/**
 * Full-screen overlay shown over all apps while device control is active.
 *
 * A plain [android.app.Service] (never an Activity): it attaches a
 * ComposeView to WindowManager with TYPE_APPLICATION_OVERLAY and observes
 * [DeviceControlMonitor.state] to show/dismiss itself — fully decoupled from
 * the executor, which only starts the service once.
 *
 * Flags keep input reaching the underlying app ([FLAG_NOT_FOCUSABLE],
 * [FLAG_NOT_TOUCH_MODAL]); device control acts via accessibility gestures,
 * not touch. The content view is hidden from the accessibility tree so the
 * agent never "sees" its own overlay nodes.
 *
 * @param injectedWindowManager Test seam; defaults to the system service.
 * @param permissionCheck Test seam over [Settings.canDrawOverlays].
 * @param overlayViewFactory Test seam producing the attached view.
 * @param externalScope Test seam replacing the monitor-collection scope.
 */
class DeviceControlOverlayService(
    private val injectedWindowManager: WindowManager? = null,
    private val permissionCheck: (Context) -> Boolean = { Settings.canDrawOverlays(it) },
    private val overlayViewFactory: (Context) -> View = ::buildOverlayView,
    private val externalScope: CoroutineScope? = null,
) : Service() {

    companion object {
        private const val TAG = "DeviceControlOverlay"

        /** How long the window lingers so the exit animation can play. */
        private const val EXIT_ANIMATION_MS = 450L

        /**
         * Convenience launcher. Safe to call from any process state —
         * background start restrictions are caught and logged by callers.
         */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, DeviceControlOverlayService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "overlay service start failed: ${e.message}")
            }
        }
    }

    private var ownedScope: CoroutineScope? = null
    private val monitorScope: CoroutineScope
        get() = externalScope
            ?: ownedScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                .also { ownedScope = it }

    private var overlayView: View? = null
    private var detachJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        observeMonitor()
    }

    override fun onDestroy() {
        removeOverlayWindow()
        ownedScope?.cancel()
        ownedScope = null
        super.onDestroy()
    }

    private fun observeMonitor() {
        monitorScope.launch {
            DeviceControlMonitor.state.collect { state ->
                onMonitorStateChanged(state)
            }
        }
    }

    /** State-driven window management; internal for tests. */
    internal fun onMonitorStateChanged(state: DeviceControlState) {
        if (state.shouldShowOverlay()) {
            attachOverlayWindow()
        } else {
            scheduleDetach()
        }
    }

    private fun attachOverlayWindow() {
        detachJob?.cancel()
        detachJob = null
        if (overlayView != null) return

        if (!permissionCheck(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay unavailable")
            return
        }
        val wm = windowManagerOrNull() ?: return
        val view = try {
            overlayViewFactory(this)
        } catch (e: Exception) {
            Log.w(TAG, "overlay view creation failed: ${e.message}")
            return
        }
        try {
            wm.addView(view, overlayLayoutParams())
            overlayView = view
            notifyViewAttached(view)
            Log.d(TAG, "overlay window attached")
        } catch (e: Exception) {
            Log.w(TAG, "overlay addView failed: ${e.message}")
            notifyViewDetached(view)
        }
    }

    private fun scheduleDetach() {
        if (overlayView == null) return
        if (detachJob?.isActive == true) return
        detachJob = monitorScope.launch {
            delay(EXIT_ANIMATION_MS)
            // A new session may have started during the exit window.
            if (!DeviceControlMonitor.state.value.shouldShowOverlay()) {
                removeOverlayWindow()
            }
        }
    }

    private fun removeOverlayWindow() {
        detachJob?.cancel()
        detachJob = null
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManagerOrNull()?.removeView(view)
            Log.d(TAG, "overlay window removed")
        } catch (e: Exception) {
            Log.w(TAG, "overlay removeView failed: ${e.message}")
        } finally {
            notifyViewDetached(view)
        }
    }

    private fun windowManagerOrNull(): WindowManager? =
        injectedWindowManager
            ?: getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private fun notifyViewAttached(view: View) {
        (view.tag as? OverlayLifecycleOwner)?.handleResumed()
    }

    private fun notifyViewDetached(view: View) {
        (view.tag as? OverlayLifecycleOwner)?.handleDestroyed()
    }
}

private fun overlayLayoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT,
).apply { gravity = Gravity.CENTER }

/**
 * Builds the Compose-backed overlay view. The lifecycle owner rides along in
 * the view tag so attachment/detachment hooks can drive its lifecycle events.
 */
private fun buildOverlayView(context: Context): View {
    val owner = OverlayLifecycleOwner()
    owner.handleCreated()
    return ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        tag = owner
        setContent {
            val state by DeviceControlMonitor.state.collectAsState()
            DeviceControlOverlayHost(
                visible = state.shouldShowOverlay(),
                state = state,
                onCancel = { DeviceControlMonitor.requestCancel() },
            )
        }
    }
}

/**
 * Self-contained LifecycleOwner + SavedStateRegistryOwner required by
 * [ComposeView] when hosted outside of an Activity.
 */
private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun handleCreated() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun handleResumed() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun handleDestroyed() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
