/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import com.zeroclaw.android.ZeroClawApplication
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentChatMessage
import com.zeroclaw.android.model.AgentLiveState
import com.zeroclaw.android.model.AgentStatus
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.ui.component.CameraPreviewSheet
import com.zeroclaw.android.ui.history.ConversationHistoryDrawerHost
import com.zeroclaw.android.ui.screen.terminal.StreamingState

/**
 * Group chat screen for the multi-agent conversation view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentGroupChatScreen(
    familyId: String = "Active",
    highlightedAgentId: String? = null,
    initialInput: String? = null,
    viewModel: AgentGroupChatViewModel = viewModel(),
    onNavigateBack: () -> Unit,
    onOpenConversation: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(familyId) {
        viewModel.setFamilyId(familyId)
    }

    val chatState by viewModel.chatState.collectAsStateWithLifecycle()
    val userInput by viewModel.userInputText.collectAsStateWithLifecycle()
    val selectedMention by viewModel.selectedMentionTarget.collectAsStateWithLifecycle()
    val pendingImages by viewModel.pendingImages.collectAsStateWithLifecycle()
    val isProcessingImages by viewModel.isProcessingImages.collectAsStateWithLifecycle()
    val cameraCaptureRequest by viewModel.cameraCaptureRequest.collectAsStateWithLifecycle()
    val screenCaptureRequest by viewModel.screenCaptureRequest.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as ZeroClawApplication
    val highlightedAgentName = highlightedAgentId?.let { chatState.agents[it]?.name }
    var initialInputConsumed by rememberSaveable(initialInput, highlightedAgentId) { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.attachImages(uris)
        }
    }
    val screenCaptureLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onScreenCapturePermissionResult(result.resultCode, result.data)
        }

    LaunchedEffect(initialInput, highlightedAgentId, chatState.agents, initialInputConsumed) {
        if (!initialInputConsumed && !initialInput.isNullOrBlank()) {
            viewModel.updateUserInput(initialInput)
            val highlightedAgent = highlightedAgentId?.let(chatState.agents::get)
            if (highlightedAgentId == null || highlightedAgent != null) {
                highlightedAgent?.let(viewModel::selectMentionTarget)
                initialInputConsumed = true
            }
        }
    }

    LaunchedEffect(screenCaptureRequest?.requestId) {
        if (screenCaptureRequest != null) {
            app.screenCaptureBridge.requestPermission(screenCaptureLauncher)
            viewModel.consumeScreenCaptureRequest()
        }
    }

    ConversationHistoryDrawerHost(
        onNewChat = viewModel::startNewChat,
        onOpenConversation = onOpenConversation,
        onOpenSettings = onNavigateToSettings,
        modifier = modifier,
    ) { openDrawer ->
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                GroupChatTopBar(
                    agentCount = chatState.activeAgents.size,
                    hasPendingApprovals = chatState.hasPendingApprovals,
                    liveStates = chatState.liveStates,
                    highlightedAgentName = highlightedAgentName,
                    onOpenDrawer = openDrawer,
                    onNavigateBack = onNavigateBack,
                    onAddAgent = {},
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(paddingValues),
            ) {
                ActiveAgentsBar(
                    agents = chatState.activeAgents,
                    liveStates = chatState.liveStates,
                    highlightedAgentId = highlightedAgentId,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )

                AgentGroupChatMessageList(
                    messages = chatState.messages,
                    typingAgentIds = chatState.typingAgentIds,
                    agentNameForId = { id -> id?.let { chatState.agents[it]?.name } ?: "Agent" },
                    onApproveMessage = viewModel::approveMessage,
                    onRejectMessage = viewModel::rejectMessage,
                    liveStates = chatState.liveStates,
                    masterAgentId = chatState.masterAgent?.id,
                    masterStreamingState = chatState.masterStreamingState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                if (pendingImages.isNotEmpty() || isProcessingImages) {
                    PendingImagesStrip(
                        images = pendingImages,
                        isProcessing = isProcessingImages,
                        onRemoveImage = viewModel::removeImage,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ChatInputBar(
                    userInput = userInput,
                    onInputChange = viewModel::updateUserInput,
                    onSendMessage = { viewModel.submitUserMessage(userInput) },
                    onAttachImages = { photoPickerLauncher.launch(PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    activeAgents = chatState.activeAgents,
                    selectedMentionTarget = selectedMention,
                    onMentionSelected = viewModel::selectMentionTarget,
                    onClearMention = { viewModel.selectMentionTarget(null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    cameraCaptureRequest?.let {
        CameraPreviewSheet(
            onDismiss = viewModel::dismissCameraCaptureRequest,
            onImageCaptured = viewModel::onCameraImageCaptured,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatTopBar(
    agentCount: Int,
    hasPendingApprovals: Boolean,
    liveStates: Map<String, AgentLiveState>,
    highlightedAgentName: String?,
    onOpenDrawer: () -> Unit,
    onNavigateBack: () -> Unit,
    onAddAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Memoize expensive calculations
    val workingAgents = remember(liveStates) {
        liveStates.values.count { it.status !in setOf(AgentStatus.IDLE, AgentStatus.DONE) }
    }
    
    val headlineStatus = remember(liveStates) {
        liveStates.values
            .sortedByDescending { it.lastUpdated }
            .firstOrNull { it.status != AgentStatus.IDLE }
    }
    
    val statusLineText = remember(agentCount, workingAgents, hasPendingApprovals) {
        buildString {
            append("Agent Group Chat")
            append(" | ")
            append("$agentCount active")
            if (workingAgents > 0) {
                append(" | $workingAgents busy")
            }
            if (hasPendingApprovals) {
                append(" | Pending approvals")
            }
        }
    }
    
    val taskStatusText = remember(headlineStatus) {
        headlineStatus?.let { status ->
            buildString {
                append(agentStatusLabel(status.status))
                if (status.currentTask.isNotBlank()) {
                    append(": ")
                    // Truncate to prevent massive text
                    append(status.currentTask.take(40))
                }
            }
        }
    }

    TopAppBar(
        title = {
            Column {
                Text(
                    text = "ZeroClaw Agents",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = statusLineText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (headlineStatus != null && taskStatusText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    )
                    {
                        AgentStatusDot(status = headlineStatus.status)
                        Text(
                            text = taskStatusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = agentStatusColor(headlineStatus.status),
                            maxLines = 1,
                        )
                    }
                }
                if (!highlightedAgentName.isNullOrBlank()) {
                    Text(
                        text = "Focused agent: $highlightedAgentName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open history drawer",
                )
            }
        },
        actions = {
            IconButton(onClick = onAddAgent) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add agent to group",
                )
            }
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to agents",
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun ActiveAgentsBar(
    agents: List<Agent>,
    liveStates: Map<String, AgentLiveState>,
    highlightedAgentId: String? = null,
    modifier: Modifier = Modifier,
) {
    if (agents.isEmpty()) return
    val listState = rememberLazyListState()

    LaunchedEffect(agents, highlightedAgentId) {
        val targetIndex = agents.indexOfFirst { it.id == highlightedAgentId }
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(agents, key = { it.id }) { agent ->
            AgentChip(
                agent = agent,
                liveState = liveStates[agent.id],
                isHighlighted = agent.id == highlightedAgentId,
                modifier = Modifier.height(40.dp),
            )
        }
    }
}

@Composable
fun AgentChip(
    agent: Agent,
    liveState: AgentLiveState?,
    isHighlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val status = liveState?.status ?: AgentStatus.IDLE

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary,
        tonalElevation = if (isHighlighted) 2.dp else 0.dp,
        shadowElevation = if (isHighlighted) 2.dp else 0.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AgentStatusDot(status = status)
            Text(
                text = agent.role.icon,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = agent.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun AgentGroupChatScreenPreview() {
    val masterAgent =
        Agent(
            id = "master-1",
            name = "Master",
            avatar = "\uD83D\uDC51",
            role = AgentRole.MASTER,
            provider = "openai",
            modelName = "gpt-4",
            isMaster = true,
            accentColor = 0xFFB88918,
        )

    val coderAgent =
        Agent(
            id = "coder-1",
            name = "Coder",
            avatar = "\uD83D\uDCBB",
            role = AgentRole.CODER,
            provider = "openai",
            modelName = "gpt-4",
            accentColor = 0xFF0074D9,
        )

    val researcherAgent =
        Agent(
            id = "researcher-1",
            name = "Researcher",
            avatar = "\uD83D\uDD0D",
            role = AgentRole.RESEARCHER,
            provider = "openai",
            modelName = "gpt-4",
            accentColor = 0xFF2ECC40,
        )

    val sampleMessages =
        listOf(
            AgentChatMessage.systemEvent("Group chat started with 3 agents"),
            AgentChatMessage.taskAssignment(
                senderId = masterAgent.id,
                senderName = masterAgent.name,
                senderAvatar = masterAgent.avatar,
                senderColor = masterAgent.accentColor,
                senderRole = masterAgent.role,
                content = "Research the latest AI trends and provide a summary.",
                targetAgentId = researcherAgent.id,
            ),
            AgentChatMessage.statusUpdate(
                senderId = researcherAgent.id,
                senderName = researcherAgent.name,
                senderAvatar = researcherAgent.avatar,
                senderColor = researcherAgent.accentColor,
                senderRole = researcherAgent.role,
                content = "Reviewing current sources and pulling together the signal.",
            ),
            AgentChatMessage.userMessage(
                content = "@Coder Can you build the demo next?",
                targetAgentId = coderAgent.id,
            ),
            AgentChatMessage.approvalRequest(
                senderId = masterAgent.id,
                senderName = masterAgent.name,
                senderAvatar = masterAgent.avatar,
                senderColor = masterAgent.accentColor,
                senderRole = masterAgent.role,
                content = "Deploy the demo to the production environment?",
                targetAgentId = coderAgent.id,
            ),
        )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AgentGroupChatMessageList(
            messages = sampleMessages,
            typingAgentIds = setOf(coderAgent.id),
            agentNameForId = { agentId ->
                when (agentId) {
                    masterAgent.id -> masterAgent.name
                    coderAgent.id -> coderAgent.name
                    researcherAgent.id -> researcherAgent.name
                    else -> "Agent"
                }
            },
            onApproveMessage = {},
            onRejectMessage = {},
            masterStreamingState = StreamingState.idle(),
        )
    }
}
