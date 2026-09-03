/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("TooManyFunctions")

package com.zeroclaw.android.ui.screen.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.BackgroundProcessState
import com.zeroclaw.android.model.ProcessedImage
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.ui.component.CameraPreviewSheet
import com.zeroclaw.android.ui.component.LinkifiedText
import com.zeroclaw.android.ui.component.LoadingIndicator
import com.zeroclaw.android.service.termux.TermuxApprovalRequest
import com.zeroclaw.android.service.termux.TermuxCommandRisk
import com.zeroclaw.android.ui.history.ConversationHistoryDrawerHost
import com.zeroclaw.android.ui.screen.agents.ActiveAgentsBar
import com.zeroclaw.android.ui.screen.agents.AgentGroupChatMessageList
import com.zeroclaw.android.ui.screen.agents.AgentGroupChatState
import com.zeroclaw.android.ui.screen.agents.AgentGroupChatViewModel
import com.zeroclaw.android.ui.screen.agents.ChatInputBar
import com.zeroclaw.android.ui.screen.terminal.quickstart.TerminalQuickStartPanel
import com.zeroclaw.android.ui.theme.TerminalTypography
import com.zeroclaw.android.ui.theme.ZeroAssistFullShape
import com.zeroclaw.android.ui.theme.ZeroAssistSpacing
import com.zeroclaw.android.util.LocalPowerSaveMode
import kotlinx.coroutines.launch

/** Horizontal padding inside the input bar. */
private const val INPUT_BAR_PADDING_DP = 0

/** Spacing between items in the scrollback. */
private const val BLOCK_SPACING_DP = 4

/** Avoid expensive smooth scrolls once the terminal has a real scrollback. */
private const val ANIMATED_AUTOSCROLL_MAX_BLOCKS = 80

/** Maximum images per picker invocation. */
private const val MAX_PICKER_IMAGES = 5

/** Autocomplete popup elevation. */
private const val AUTOCOMPLETE_ELEVATION_DP = 4

/** Autocomplete item vertical padding. */
private const val AUTOCOMPLETE_ITEM_V_PAD_DP = 10

/** Autocomplete item horizontal padding. */
private const val AUTOCOMPLETE_ITEM_H_PAD_DP = 12

/** Small spacing used between elements. */
private const val SMALL_SPACING_DP = 4

/** Pending image strip item horizontal padding. */
private const val STRIP_ITEM_H_PAD_DP = 8

/** Pending image strip item vertical padding. */
private const val STRIP_ITEM_V_PAD_DP = 4

/** Pending image strip corner radius. */
private const val STRIP_ITEM_CORNER_DP = 4

/** Dismiss badge size for pending images. */
private const val DISMISS_BADGE_DP = 16

/** Dismiss icon size. */
private const val DISMISS_ICON_DP = 10

/** Loading indicator size in the pending strip. */
private const val PROCESSING_INDICATOR_DP = 14

/** Maximum agent shortcuts shown in the empty terminal quick-start panel. */
private const val MAX_QUICK_START_AGENTS = 4

/** Commands surfaced in the empty terminal quick-start panel. */
private val quickStartCommandLabels = listOf("/status", "/health", "/doctor", "/agents", "/tools")

private enum class TerminalPane {
    TERMINAL,
    GROUP_CHAT,
}

private fun terminalStatusLabel(serviceState: ServiceState): String =
    when (serviceState) {
        ServiceState.STOPPED -> "Daemon offline"
        ServiceState.STARTING -> "Starting..."
        ServiceState.RUNNING -> "Running"
        ServiceState.STOPPING -> "Stopping..."
        ServiceState.ERROR -> "Daemon error"
    }

/**
 * Terminal REPL screen for interacting with the Zero-Assist daemon.
 *
 * Thin stateful wrapper that collects [TerminalViewModel] flows and
 * delegates rendering to [TerminalContent]. Provides the photo picker
 * launcher for image attachments.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param terminalViewModel The [TerminalViewModel] for terminal state.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun TerminalScreen(
    edgeMargin: Dp,
    terminalViewModel: TerminalViewModel = viewModel(),
    groupChatViewModel: AgentGroupChatViewModel = viewModel(),
    initialInput: String? = null,
    onOpenConversation: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by terminalViewModel.state.collectAsStateWithLifecycle()
    val streamingState by terminalViewModel.streamingState.collectAsStateWithLifecycle()
    val isSessionReady by terminalViewModel.isSessionReady.collectAsStateWithLifecycle()
    val cameraCaptureRequest by terminalViewModel.cameraCaptureRequest.collectAsStateWithLifecycle()
    val screenCaptureRequest by terminalViewModel.screenCaptureRequest.collectAsStateWithLifecycle()
    val backgroundProcessState by terminalViewModel.backgroundProcessState.collectAsStateWithLifecycle()
    val groupChatState by groupChatViewModel.chatState.collectAsStateWithLifecycle()
    val groupChatInput by groupChatViewModel.userInputText.collectAsStateWithLifecycle()
    val selectedMentionTarget by groupChatViewModel.selectedMentionTarget.collectAsStateWithLifecycle()
    val groupCameraCaptureRequest by groupChatViewModel.cameraCaptureRequest.collectAsStateWithLifecycle()
    val groupScreenCaptureRequest by groupChatViewModel.screenCaptureRequest.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as ZeroClawApplication
    val serviceState by app.daemonBridge.serviceState.collectAsStateWithLifecycle()
    val resetTerminalSession: () -> Unit = {
        terminalViewModel.resetAgentSession()
        groupChatViewModel.startNewChat()
    }
    val screenCaptureLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            terminalViewModel.onScreenCapturePermissionResult(result.resultCode, result.data)
        }
    val groupScreenCaptureLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            groupChatViewModel.onScreenCapturePermissionResult(result.resultCode, result.data)
        }

    LaunchedEffect(Unit) {
        groupChatViewModel.setFamilyId("Active")
    }

    LaunchedEffect(screenCaptureRequest?.requestId) {
        if (screenCaptureRequest != null) {
            app.screenCaptureBridge.requestPermission(screenCaptureLauncher)
            terminalViewModel.consumeScreenCaptureRequest()
        }
    }

    LaunchedEffect(groupScreenCaptureRequest?.requestId) {
        if (groupScreenCaptureRequest != null) {
            app.screenCaptureBridge.requestPermission(groupScreenCaptureLauncher)
            groupChatViewModel.consumeScreenCaptureRequest()
        }
    }

    ConversationHistoryDrawerHost(
        onNewChat = resetTerminalSession,
        onOpenConversation = onOpenConversation,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    ) { openDrawer ->
        val terminalActions = TerminalActions(
            onSubmit = terminalViewModel::submitInput,
            onAttachImages = terminalViewModel::attachImages,
            onRemoveImage = terminalViewModel::removeImage,
            onCancelAgent = terminalViewModel::cancelAgentTurn,
            onApproveTermuxCommand = terminalViewModel::approveTermuxCommand,
            onTrySaferTermuxCommand = terminalViewModel::rejectTermuxCommand,
            onResetSession = resetTerminalSession,
            onOpenDrawer = openDrawer,
        )
        val canvasPanelOpen by terminalViewModel.canvasPanelOpen.collectAsStateWithLifecycle()
        val activeCanvasId by terminalViewModel.activeCanvasId.collectAsStateWithLifecycle()

        TerminalContent(
            state = state,
            streamingState = streamingState,
            isSessionReady = isSessionReady,
            serviceState = serviceState,
            groupChatState = groupChatState,
            groupChatInput = groupChatInput,
            selectedMentionTarget = selectedMentionTarget,
            terminalActions = terminalActions,
            groupChatActions = GroupChatActions(
                onInputChange = groupChatViewModel::updateUserInput,
                onSendMessage = { groupChatViewModel.submitUserMessage(groupChatInput) },
                onApproveMessage = groupChatViewModel::approveMessage,
                onRejectMessage = groupChatViewModel::rejectMessage,
                onMentionSelected = groupChatViewModel::selectMentionTarget,
                onClearMention = { groupChatViewModel.selectMentionTarget(null) },
            ),
            backgroundProcessState = backgroundProcessState,
            backgroundProcessActions = BackgroundProcessActions(
                onClose = terminalViewModel::closeBackgroundProcess,
                onToggleExpand = terminalViewModel::toggleBackgroundProcessExpansion,
                onShow = terminalViewModel::showBackgroundProcess,
            ),
            edgeMargin = edgeMargin,
            initialInput = initialInput,
            canvasPanelOpen = canvasPanelOpen,
            onToggleCanvasPanel = terminalViewModel::toggleCanvasPanel,
            onDismissCanvas = terminalViewModel::toggleCanvasPanel,
            activeCanvasId = activeCanvasId,
        )
    }

    cameraCaptureRequest?.let {
        CameraPreviewSheet(
            onDismiss = terminalViewModel::dismissCameraCaptureRequest,
            onImageCaptured = terminalViewModel::onCameraImageCaptured,
        )
    }

    groupCameraCaptureRequest?.let {
        CameraPreviewSheet(
            onDismiss = groupChatViewModel::dismissCameraCaptureRequest,
            onImageCaptured = groupChatViewModel::onCameraImageCaptured,
        )
    }
}

/**
 * Stateless terminal content composable for testing.
 *
 * Renders the terminal scrollback buffer, input bar, pending image
 * strip, autocomplete overlay, and live agent streaming card. All
 * state is passed in as parameters for deterministic previews and
 * unit tests.
 *
 * @param state Aggregated terminal state snapshot.
 * @param streamingState Live agent session streaming state.
 * @param serviceState Current daemon service lifecycle state.
 * @param onSubmit Callback to submit user input text.
 * @param onAttachImages Callback to attach images from URIs.
 * @param onRemoveImage Callback to remove a pending image by index.
 * @param onCancelAgent Callback to cancel the active agent turn.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param modifier Modifier applied to the root layout.
 */
internal data class GroupChatActions(
    val onInputChange: (String) -> Unit = {},
    val onSendMessage: () -> Unit = {},
    val onApproveMessage: (String) -> Unit = {},
    val onRejectMessage: (String) -> Unit = {},
    val onMentionSelected: (Agent) -> Unit = {},
    val onClearMention: () -> Unit = {},
)

internal data class BackgroundProcessActions(
    val onClose: () -> Unit = {},
    val onToggleExpand: () -> Unit = {},
    val onShow: () -> Unit = {},
)

internal data class TerminalActions(
    val onSubmit: (String) -> Unit,
    val onAttachImages: (List<Uri>) -> Unit,
    val onRemoveImage: (Int) -> Unit,
    val onCancelAgent: () -> Unit,
    val onApproveTermuxCommand: (String) -> Unit = {},
    val onTrySaferTermuxCommand: (String) -> Unit = {},
    val onResetSession: () -> Unit = {},
    val onOpenDrawer: () -> Unit = {},
)

@Suppress("OutdatedDocumentation")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TerminalContent(
    state: TerminalState,
    streamingState: StreamingState,
    isSessionReady: Boolean = true,
    serviceState: ServiceState,
    groupChatState: AgentGroupChatState = AgentGroupChatState(),
    groupChatInput: String = "",
    selectedMentionTarget: Agent? = null,
    terminalActions: TerminalActions,
    groupChatActions: GroupChatActions = GroupChatActions(),
    backgroundProcessState: BackgroundProcessState = BackgroundProcessState(),
    backgroundProcessActions: BackgroundProcessActions = BackgroundProcessActions(),
    edgeMargin: Dp,
    initialInput: String? = null,
    canvasPanelOpen: Boolean = false,
    onToggleCanvasPanel: () -> Unit = {},
    onDismissCanvas: () -> Unit = {},
    activeCanvasId: String = "default",
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var inputText by remember { mutableStateOf("") }
    var initialInputConsumed by rememberSaveable(initialInput) { mutableStateOf(false) }
    val isPowerSave = LocalPowerSaveMode.current
    val terminalInputFocusRequester = remember { FocusRequester() }
    val groupChatInputFocusRequester = remember { FocusRequester() }
    var pendingFocusPane by remember { mutableStateOf<TerminalPane?>(null) }
    val isKeyboardVisible = WindowInsets.isImeVisible
    val terminalStatusDescription = terminalStatusLabel(serviceState).lowercase()

    val photoPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKER_IMAGES),
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                terminalActions.onAttachImages(uris)
            }
        }

    val isAgentActive =
        streamingState.phase != StreamingPhase.IDLE &&
            streamingState.phase != StreamingPhase.COMPLETE &&
            streamingState.phase != StreamingPhase.CANCELLED &&
            streamingState.phase != StreamingPhase.ERROR
    val isInputDisabled = state.isLoading || isAgentActive
    val hasGroupChat =
        groupChatState.activeAgents.isNotEmpty() ||
            groupChatState.messages.isNotEmpty() ||
            groupChatState.typingAgentIds.isNotEmpty()
    var selectedPane by rememberSaveable(hasGroupChat) {
        mutableStateOf(TerminalPane.TERMINAL)
    }

    val stableOnRemove: (Int) -> Unit = remember { { index -> terminalActions.onRemoveImage(index) } }
    val quickStartAgents =
        remember(groupChatState.activeAgents, groupChatState.agents) {
            (groupChatState.activeAgents + groupChatState.agents.values)
                .distinctBy { it.id }
                .take(MAX_QUICK_START_AGENTS)
        }

    val autocompletePrefix by remember {
        derivedStateOf {
            if (inputText.startsWith("/")) {
                inputText.removePrefix("/")
            } else {
                null
            }
        }
    }
    val autocompleteSuggestions by remember {
        derivedStateOf {
            val prefix = autocompletePrefix
            if (prefix != null) {
                CommandRegistry.matches(prefix)
            } else {
                emptyList()
            }
        }
    }

    LaunchedEffect(state.blocks.size, isAgentActive) {
        if (state.blocks.isNotEmpty() || isAgentActive) {
            if (isPowerSave || state.blocks.size > ANIMATED_AUTOSCROLL_MAX_BLOCKS) {
                listState.scrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }

    LaunchedEffect(hasGroupChat) {
        if (!hasGroupChat && selectedPane == TerminalPane.GROUP_CHAT) {
            selectedPane = TerminalPane.TERMINAL
        }
    }

    LaunchedEffect(initialInput, initialInputConsumed) {
        if (!initialInputConsumed && !initialInput.isNullOrBlank()) {
            inputText = initialInput
            initialInputConsumed = true
        }
    }

    LaunchedEffect(selectedPane, pendingFocusPane, hasGroupChat) {
        when (pendingFocusPane) {
            TerminalPane.TERMINAL ->
                if (selectedPane == TerminalPane.TERMINAL) {
                    terminalInputFocusRequester.requestFocus()
                    pendingFocusPane = null
                }
            TerminalPane.GROUP_CHAT ->
                if (selectedPane == TerminalPane.GROUP_CHAT && hasGroupChat) {
                    groupChatInputFocusRequester.requestFocus()
                    pendingFocusPane = null
                }
            null -> Unit
        }
    }

    fun focusActiveInput(pane: TerminalPane) {
        pendingFocusPane = pane
    }

    fun updateTerminalInputFromQuickStart(text: String) {
        inputText = text
        selectedPane = TerminalPane.TERMINAL
        focusActiveInput(TerminalPane.TERMINAL)
    }

    TerminalPaneSelector(
        hasGroupChat = hasGroupChat,
        selectedPane = selectedPane,
        groupChatState = groupChatState,
        edgeMargin = edgeMargin,
        onPaneSelected = { selectedPane = it },
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .semantics {
                        contentDescription = "Terminal controls, status: $terminalStatusDescription"
                    }
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.exclude(WindowInsets.statusBars)
                    ),
        ) {
            TerminalHeaderSection(
                isKeyboardVisible = isKeyboardVisible,
                edgeMargin = edgeMargin,
                serviceState = serviceState,
                isSessionReady = isSessionReady,
                terminalActions = terminalActions,
                canvasPanelOpen = canvasPanelOpen,
                onToggleCanvasPanel = onToggleCanvasPanel,
            )

            if (selectedPane == TerminalPane.GROUP_CHAT && hasGroupChat) {
                EmbeddedGroupChatPane(
                    state = groupChatState,
                    userInput = groupChatInput,
                    selectedMentionTarget = selectedMentionTarget,
                    streamingState = groupChatState.masterStreamingState,
                    onInputChange = groupChatActions.onInputChange,
                    onSendMessage = groupChatActions.onSendMessage,
                    onAttachImages = { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onApproveMessage = groupChatActions.onApproveMessage,
                    onRejectMessage = groupChatActions.onRejectMessage,
                    onMentionSelected = groupChatActions.onMentionSelected,
                    onClearMention = groupChatActions.onClearMention,
                    inputFocusRequester = groupChatInputFocusRequester,
                    edgeMargin = edgeMargin,
                    modifier = Modifier.weight(1f),
                )
            } else {
                TerminalScrollbackPane(
                    state = state,
                    streamingState = streamingState,
                    isAgentActive = isAgentActive,
                    listState = listState,
                    edgeMargin = edgeMargin,
                    quickStartAgents = quickStartAgents,
                    showQuickStart = state.blocks.isEmpty() && !state.isLoading && !isAgentActive,
                    onQuickStartAction = { action -> updateTerminalInputFromQuickStart(quickStartPromptFor(action)) },
                    onQuickStartCommand = { command -> updateTerminalInputFromQuickStart("$command ") },
                    onQuickStartAgent = { agent -> updateTerminalInputFromQuickStart("/delegate ${agent.id} ") },
                    terminalActions = terminalActions,
                    context = context,
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                    modifier = Modifier.weight(1f),
                )
            }

            if (selectedPane == TerminalPane.TERMINAL || !hasGroupChat) {
                TerminalBottomControls(
                    state = state,
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSubmit = {
                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                        terminalActions.onSubmit(inputText)
                        inputText = ""
                    },
                    onAttach = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    isInputDisabled = isInputDisabled,
                    stableOnRemove = stableOnRemove,
                    autocompleteSuggestions = autocompleteSuggestions,
                    onAutocompleteSelect = { command -> inputText = "/${command.name} " },
                    terminalActions = terminalActions,
                    terminalInputFocusRequester = terminalInputFocusRequester,
                    edgeMargin = edgeMargin,
                )
            }
        }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )

    if (canvasPanelOpen) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.75f)
                .align(Alignment.CenterEnd)
                .shadow(8.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            CanvasScreen(
                canvasId = activeCanvasId,
                onDismiss = onDismissCanvas,
            )
        }
    }
}
}

@Composable
private fun TerminalPaneSelector(
    hasGroupChat: Boolean,
    selectedPane: TerminalPane,
    groupChatState: AgentGroupChatState,
    edgeMargin: Dp,
    onPaneSelected: (TerminalPane) -> Unit,
) {
    if (hasGroupChat) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = edgeMargin, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Surface(
                    color = if (selectedPane == TerminalPane.TERMINAL) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.weight(1f).clickable { onPaneSelected(TerminalPane.TERMINAL) },
                ) {
                    Text(
                        text = "Terminal",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedPane == TerminalPane.TERMINAL) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                Surface(
                    color = if (selectedPane == TerminalPane.GROUP_CHAT) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.weight(1f).clickable { onPaneSelected(TerminalPane.GROUP_CHAT) },
                ) {
                    Text(
                        text = if (groupChatState.hasPendingApprovals) "Agents pending" else "Agents",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedPane == TerminalPane.GROUP_CHAT) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalHeaderSection(
    isKeyboardVisible: Boolean,
    edgeMargin: Dp,
    serviceState: ServiceState,
    isSessionReady: Boolean,
    terminalActions: TerminalActions,
    canvasPanelOpen: Boolean = false,
    onToggleCanvasPanel: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = !isKeyboardVisible,
        enter = fadeIn(tween(200, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(150, easing = LinearOutSlowInEasing)),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                .padding(top = 6.dp),
        ) {
            TerminalHeader(
                serviceState = serviceState,
                sessionReady = isSessionReady,
                terminalActions = terminalActions,
                canvasPanelOpen = canvasPanelOpen,
                onToggleCanvasPanel = onToggleCanvasPanel,
                modifier = Modifier.padding(horizontal = edgeMargin),
            )
        }
    }
}

@Composable
private fun TerminalScrollbackPane(
    state: TerminalState,
    streamingState: StreamingState,
    isAgentActive: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    edgeMargin: Dp,
    quickStartAgents: List<Agent>,
    showQuickStart: Boolean,
    onQuickStartAction: (String) -> Unit,
    onQuickStartCommand: (String) -> Unit,
    onQuickStartAgent: (Agent) -> Unit,
    terminalActions: TerminalActions,
    context: Context,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val visibleBlocks = remember(state.blocks) {
        state.blocks.asReversed().toList()
    }

    val dismissedErrorIds = remember { mutableSetOf<Long>() }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier
            .then(modifier)
            .padding(horizontal = edgeMargin),
        verticalArrangement = Arrangement.spacedBy(BLOCK_SPACING_DP.dp),
    ) {
        if (showQuickStart) {
            item(key = "terminal-quick-start", contentType = "quick-start") {
                TerminalEmptyQuickStart(
                    slashCommands = quickStartCommandLabels,
                    recentAgents = quickStartAgents,
                    onActionSelected = onQuickStartAction,
                    onCommandSelected = onQuickStartCommand,
                    onRecentAgentSelected = onQuickStartAgent,
                    modifier = Modifier.padding(
                        horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                        vertical = ZeroAssistSpacing.Small,
                    ),
                )
            }
        }

        if (isAgentActive) {
            if (streamingState.responseText.isNotEmpty()) {
                item(key = "streaming-response", contentType = "streaming") {
                    StreamingResponseBlock(
                        text = streamingState.responseText,
                        modifier = Modifier.padding(
                            horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                            vertical = SMALL_SPACING_DP.dp,
                        ),
                    )
                }
            }

            item(key = "thinking-card", contentType = "thinking") {
                ThinkingCard(
                    thinkingText = streamingState.thinkingText,
                    visible = true,
                    onCancel = terminalActions.onCancelAgent,
                    activeTools = streamingState.activeTools,
                    toolResults = streamingState.toolResults,
                    phase = streamingState.phase,
                    providerRound = streamingState.providerRound,
                    toolCallCount = streamingState.toolCallCount,
                    llmDurationSecs = streamingState.llmDurationSecs,
                    modifier = Modifier.padding(
                        horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                        vertical = SMALL_SPACING_DP.dp,
                    ),
                )
            }
        } else if (state.isLoading) {
            item(key = "spinner", contentType = "spinner") {
                BrailleSpinner(
                    label = "Thinking\u2026",
                    modifier = Modifier.padding(
                        horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                        vertical = SMALL_SPACING_DP.dp,
                    ),
                )
            }
        }

        itemsIndexed(
            items = visibleBlocks,
            key = { index, block -> terminalBlockRenderKey(block, index) },
            contentType = { _, block -> block::class.simpleName },
        ) { _, block ->
            val onCopy: (String) -> Unit = remember(block.id) {
                { text ->
                    copyToClipboard(context, text)
                    scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                    Unit
                }
            }
            TerminalBlockItem(block = block, onCopy = onCopy, dismissedErrorIds = dismissedErrorIds)
        }
    }
}

@Composable
private fun TerminalBottomControls(
    state: TerminalState,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onAttach: () -> Unit,
    isInputDisabled: Boolean,
    stableOnRemove: (Int) -> Unit,
    autocompleteSuggestions: List<SlashCommand>,
    onAutocompleteSelect: (SlashCommand) -> Unit,
    terminalActions: TerminalActions,
    terminalInputFocusRequester: FocusRequester,
    edgeMargin: Dp,
) {
    if (state.pendingImages.isNotEmpty() || state.isProcessingImages) {
        PendingImagesStrip(
            images = state.pendingImages,
            isProcessing = state.isProcessingImages,
            onRemove = stableOnRemove,
            modifier = Modifier.padding(horizontal = edgeMargin),
        )
    }

    if (autocompleteSuggestions.isNotEmpty()) {
        AutocompletePopup(
            suggestions = autocompleteSuggestions,
            onSelect = onAutocompleteSelect,
            modifier = Modifier.padding(horizontal = edgeMargin),
        )
    }

    state.pendingTermuxApproval?.let { approval ->
        TermuxApprovalCard(
            request = approval,
            onAllowOnce = { terminalActions.onApproveTermuxCommand(approval.id) },
            onTrySaferWay = { terminalActions.onTrySaferTermuxCommand(approval.id) },
            modifier = Modifier.padding(
                horizontal = edgeMargin,
                vertical = SMALL_SPACING_DP.dp,
            ),
        )
    }

    TerminalInputBar(
        value = inputText,
        onValueChange = onInputChange,
        onSubmit = onSubmit,
        onAttach = onAttach,
        isLoading = isInputDisabled,
        hasImages = state.pendingImages.isNotEmpty(),
        focusRequester = terminalInputFocusRequester,
        modifier = Modifier.padding(
            horizontal = edgeMargin,
            vertical = INPUT_BAR_PADDING_DP.dp,
        ),
    )
}

@Composable
private fun TerminalEmptyQuickStart(
    slashCommands: List<String>,
    recentAgents: List<Agent>,
    onActionSelected: (String) -> Unit,
    onCommandSelected: (String) -> Unit,
    onRecentAgentSelected: (Agent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shape = MaterialTheme.shapes.extraLarge,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
        modifier =
            modifier
                .fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Medium),
            modifier = Modifier.padding(ZeroAssistSpacing.Medium),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.XSmall)) {
                Text(
                    text = "Start a terminal task",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Ask in plain language, pick a slash command, or attach an image.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TerminalQuickStartPanel(
                slashCommands = slashCommands,
                recentAgents = recentAgents,
                onActionSelected = onActionSelected,
                onCommandSelected = onCommandSelected,
                onRecentAgentSelected = onRecentAgentSelected,
            )
        }
    }
}

private fun quickStartPromptFor(action: String): String =
    when (action) {
        "Write" -> "Draft "
        "Learn" -> "Explain "
        "Code" -> "Help me implement "
        "Automate" -> "Automate this workflow: "
        else -> ""
    }

@Composable
private fun EmbeddedGroupChatPane(
    state: AgentGroupChatState,
    userInput: String,
    selectedMentionTarget: Agent?,
    streamingState: StreamingState,
    onInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onAttachImages: () -> Unit,
    onApproveMessage: (String) -> Unit,
    onRejectMessage: (String) -> Unit,
    onMentionSelected: (Agent) -> Unit,
    onClearMention: () -> Unit,
    inputFocusRequester: FocusRequester,
    edgeMargin: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text =
                buildString {
                    append("Agent group chat")
                    if (state.activeAgents.isNotEmpty()) {
                        append(" | ${state.activeAgents.size} active")
                    }
                    if (state.hasPendingApprovals) {
                        append(" | pending approvals")
                    }
                },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = edgeMargin, vertical = SMALL_SPACING_DP.dp),
        )

        if (state.activeAgents.isNotEmpty()) {
            ActiveAgentsBar(
                agents = state.activeAgents,
                liveStates = state.liveStates,
                modifier = Modifier.padding(horizontal = edgeMargin, vertical = SMALL_SPACING_DP.dp),
            )
        }

        AgentGroupChatMessageList(
            messages = state.messages,
            typingAgentIds = state.typingAgentIds,
            agentNameForId = { id -> id?.let { state.agents[it]?.name } ?: "Agent" },
            onApproveMessage = onApproveMessage,
            onRejectMessage = onRejectMessage,
            liveStates = state.liveStates,
            masterAgentId = state.masterAgent?.id,
            masterStreamingState = streamingState,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = edgeMargin),
        )

        ChatInputBar(
            userInput = userInput,
            onInputChange = onInputChange,
            onSendMessage = onSendMessage,
            onAttachImages = onAttachImages,
            activeAgents = state.activeAgents,
            selectedMentionTarget = selectedMentionTarget,
            onMentionSelected = onMentionSelected,
            onClearMention = onClearMention,
            focusRequester = inputFocusRequester,
            modifier =
                Modifier.padding(
                    horizontal = edgeMargin,
                    vertical = INPUT_BAR_PADDING_DP.dp,
                ),
        )
    }
}

@Composable
private fun TermuxApprovalCard(
    request: TermuxApprovalRequest,
    onAllowOnce: () -> Unit,
    onTrySaferWay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayRisk =
        if (request.risk == TermuxCommandRisk.BLOCKED) {
            TermuxCommandRisk.HIGH
        } else {
            request.risk
        }
    val accent =
        when (displayRisk) {
            TermuxCommandRisk.LOW -> MaterialTheme.colorScheme.primary
            TermuxCommandRisk.MEDIUM -> MaterialTheme.colorScheme.tertiary
            TermuxCommandRisk.HIGH,
            TermuxCommandRisk.BLOCKED,
            -> MaterialTheme.colorScheme.error
        }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Approve Termux command?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Risk: ${displayRisk.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = request.commandPreview,
                style = TerminalTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = request.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "cwd: ${request.workingDirectory}",
                style = TerminalTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onTrySaferWay) {
                    Text("Try safer way")
                }
                Button(onClick = onAllowOnce) {
                    Text("Allow once")
                }
            }
        }
    }
}

/**
 * Compact utility row for terminal-specific actions without duplicating
 * the app-shell title/status chrome.
 */
@Composable
private fun TerminalHeader(
    serviceState: ServiceState,
    sessionReady: Boolean,
    terminalActions: TerminalActions,
    canvasPanelOpen: Boolean = false,
    onToggleCanvasPanel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isPowerSave = LocalPowerSaveMode.current
    val statusLabel = terminalStatusLabel(serviceState)
    val statusContainerColor =
        when (serviceState) {
            ServiceState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
            ServiceState.STARTING,
            ServiceState.STOPPING,
            -> MaterialTheme.colorScheme.tertiaryContainer
            ServiceState.STOPPED,
            ServiceState.ERROR,
            -> MaterialTheme.colorScheme.errorContainer
        }
    val statusContentColor =
        when (serviceState) {
            ServiceState.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
            ServiceState.STARTING,
            ServiceState.STOPPING,
            -> MaterialTheme.colorScheme.onTertiaryContainer
            ServiceState.STOPPED,
            ServiceState.ERROR,
            -> MaterialTheme.colorScheme.onErrorContainer
        }

    val shouldPulse = !isPowerSave &&
        (serviceState == ServiceState.RUNNING || serviceState == ServiceState.STARTING)
    val pulseAlpha by animateFloatAsState(
        targetValue = if (shouldPulse) 0.35f else 1f,
        animationSpec = if (shouldPulse) {
            infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            )
        } else {
            tween<Float>(1)
        },
        label = "status-dot-alpha",
    )

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shape = MaterialTheme.shapes.extraLarge,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = "Terminal controls, status: ${statusLabel.lowercase()}"
                    liveRegion = LiveRegionMode.Polite
                },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                shape = MaterialTheme.shapes.large,
            ) {
                IconButton(
                    onClick = terminalActions.onOpenDrawer,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .semantics { contentDescription = "Open history drawer" },
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (serviceState != ServiceState.RUNNING) {
                Surface(
                    color = statusContainerColor,
                    shape = ZeroAssistFullShape,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(7.dp)
                                    .graphicsLayer { alpha = if (shouldPulse) pulseAlpha else 1f }
                                    .background(statusContentColor, CircleShape),
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusContentColor,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (sessionReady) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    shape = CircleShape,
                    modifier = Modifier.clickable(onClick = terminalActions.onResetSession),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "New chat",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Input bar with a prompt prefix, text field, attach button, and send button.
 *
 * Uses monospace typography for the terminal aesthetic. The `>` prompt
 * prefix is rendered as leading text within the outlined text field.
 *
 * @param value Current input text.
 * @param onValueChange Callback when text changes.
 * @param onSubmit Callback when the send button is tapped.
 * @param onAttach Callback when the attach button is tapped.
 * @param isLoading Whether a response is in progress (disables send).
 * @param hasImages Whether images are currently attached.
 * @param modifier Modifier applied to the input bar.
 */
@Composable
private fun TerminalInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onAttach: () -> Unit,
    isLoading: Boolean,
    hasImages: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val canSend = (value.isNotBlank() || hasImages) && !isLoading

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        shape = MaterialTheme.shapes.extraLarge,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                shape = MaterialTheme.shapes.large,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Attach images"
                    },
            ) {
                IconButton(
                    onClick = onAttach,
                    enabled = !isLoading,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = null,
                        tint =
                            if (!isLoading) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle =
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    placeholder = {
                        Text(
                            text = "Ask anything or type / for commands...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                )
            }
            Surface(
                color =
                    if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                    },
                shape = CircleShape,
                modifier = Modifier.size(44.dp),
            ) {
                IconButton(
                    onClick = onSubmit,
                    enabled = canSend,
                    modifier =
                        Modifier
                            .semantics {
                                contentDescription = "Send"
                            },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint =
                            if (canSend) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Autocomplete popup showing matching slash commands above the input bar.
 *
 * Each suggestion displays the command name and its description. Tapping
 * a suggestion inserts the command text into the input field.
 *
 * @param suggestions Filtered list of matching commands.
 * @param onSelect Callback when a suggestion is tapped.
 * @param modifier Modifier applied to the popup container.
 */
@Composable
private fun AutocompletePopup(
    suggestions: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = AUTOCOMPLETE_ELEVATION_DP.dp,
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            for (command in suggestions) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(command) }
                            .padding(
                                horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                                vertical = AUTOCOMPLETE_ITEM_V_PAD_DP.dp,
                            ).semantics {
                                contentDescription =
                                    "/${command.name}: ${command.description}"
                            },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "/${command.name}",
                        style = TerminalTypography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(INPUT_BAR_PADDING_DP.dp))
                    Text(
                        text = command.description,
                        style = TerminalTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Horizontal strip of pending image indicators in terminal aesthetic.
 *
 * Each image is shown as a text label `[filename size]` with a dismiss
 * button, matching the terminal look instead of graphical thumbnails.
 * A processing indicator appears when images are being downscaled.
 *
 * @param images Currently staged images.
 * @param isProcessing Whether images are still being processed.
 * @param onRemove Callback to remove an image by index.
 * @param modifier Modifier applied to the strip.
 */
@Composable
private fun PendingImagesStrip(
    images: List<ProcessedImage>,
    isProcessing: Boolean,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SMALL_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isProcessing) {
            LoadingIndicator(modifier = Modifier.size(PROCESSING_INDICATOR_DP.dp))
        }
        for ((index, image) in images.withIndex()) {
            val stableOnRemove = remember(index) { { onRemove(index) } }
            PendingImageChip(
                image = image,
                onRemove = stableOnRemove,
            )
        }
    }
}

/**
 * Terminal-styled chip showing an image filename with a dismiss button.
 *
 * @param image The processed image to display.
 * @param onRemove Callback when the dismiss button is tapped.
 */
@Composable
private fun PendingImageChip(
    image: ProcessedImage,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(STRIP_ITEM_CORNER_DP.dp),
                ).padding(
                    horizontal = STRIP_ITEM_H_PAD_DP.dp,
                    vertical = STRIP_ITEM_V_PAD_DP.dp,
                ),
    ) {
        Text(
            text = "[${image.displayName}]",
            style = TerminalTypography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(SMALL_SPACING_DP.dp))
        Box(
            modifier =
                Modifier
                    .size(DISMISS_BADGE_DP.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .clickable(onClick = onRemove)
                    .semantics {
                        contentDescription = "Remove ${image.displayName}"
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(DISMISS_ICON_DP.dp),
            )
        }
    }
}

/**
 * Streaming response block that renders progressively growing text.
 *
 * Styled identically to [TerminalBlock.Response] blocks but rendered
 * inline during the streaming phase. When the turn completes, this block
 * disappears and a persisted [TerminalBlock.Response] replaces it in
 * the scrollback.
 *
 * @param text Accumulated response tokens so far.
 * @param modifier Modifier applied to the text block.
 */
@Composable
private fun StreamingResponseBlock(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier =
            modifier
                .fillMaxWidth(0.88f)
                .semantics {
                    contentDescription = "Streaming response"
                    liveRegion = LiveRegionMode.Polite
                },
    ) {
        Row {
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
                        )
                        .align(Alignment.CenterVertically),
            )
            LinkifiedText(
                text = text,
                style = TerminalTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(12.dp),
            )
        }
    }
}

/**
 * Copies the given text to the system clipboard.
 *
 * @param context Android context for system service access.
 * @param text The text to copy.
 */
private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Terminal output", text)
    clip.description.extras =
        android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    clipboard.setPrimaryClip(clip)
}
