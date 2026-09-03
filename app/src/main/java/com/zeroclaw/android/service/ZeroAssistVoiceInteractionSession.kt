/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
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
import com.zeroclaw.android.MainActivity
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.ui.screen.voice.VoiceAssistantStandaloneHost

/**
 * Android's real Assist session. This shows only the assistant popup over the
 * current screen instead of bringing the full Zero-Assist app task forward.
 */
class ZeroAssistVoiceInteractionSession(
    context: Context,
) : VoiceInteractionSession(context),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val app = context.applicationContext as ZeroClawApplication
    private var contentView: ComposeView? = null

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreateContentView(): View {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return ComposeView(context).also { view ->
            contentView = view
            view.setViewTreeLifecycleOwner(this)
            view.setViewTreeViewModelStoreOwner(this)
            view.setViewTreeSavedStateRegistryOwner(this)
            view.setContent {
                VoiceAssistantStandaloneHost(
                    app = app,
                    onCloseSurface = { finish() },
                    onOpenVoiceSettings = {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                ),
                        )
                        finish()
                    },
                    launchCommand = { command, _ ->
                        VoiceAssistantCommandLauncher.launch(
                            context = context,
                            command = command,
                        )
                    },
                )
            }
        }
    }

    override fun onShow(
        args: Bundle?,
        showFlags: Int,
    ) {
        super.onShow(args, showFlags)
        app.voiceAssistantLaunchRequests.requestOpen()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    override fun onHide() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }
        super.onHide()
    }

    override fun onDestroy() {
        contentView?.disposeComposition()
        contentView = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }
}
