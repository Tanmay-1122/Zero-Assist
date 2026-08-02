/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroclaw.android.model.VoiceAssistantSessionState
import com.zeroclaw.android.model.VoiceAssistantUiState
import com.zeroclaw.android.ui.component.LinkifiedText
import com.zeroclaw.android.ui.screen.terminal.orb.OrbSize
import com.zeroclaw.android.ui.screen.terminal.orb.OrbState
import com.zeroclaw.android.ui.screen.terminal.orb.OrbTheme
import com.zeroclaw.android.ui.screen.terminal.orb.ThinkingOrb
import kotlin.math.sin

@Composable
fun VoiceAssistantPopupHost(
    viewModel: VoiceAssistantViewModel,
    onOpenVoiceSettings: () -> Unit,
    modifier: Modifier = Modifier,
    showLauncher: Boolean = true,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val microphonePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.startListening()
            } else {
                viewModel.microphonePermissionDenied()
            }
        }
    val contactsPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.contactsPermissionResult(granted)
        }

    LaunchedEffect(viewModel) {
        viewModel.contactPermissionRequests.collect {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.contactsPermissionResult(granted = true)
            } else {
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = uiState.popupVisible,
            enter =
                slideInVertically(
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                    initialOffsetY = { it / 3 },
                ) + fadeIn(
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                ),
            exit =
                slideOutVertically(
                    animationSpec = tween(200, easing = FastOutLinearInEasing),
                    targetOffsetY = { it / 4 },
                ) + fadeOut(
                    animationSpec = tween(160, easing = FastOutLinearInEasing),
                ),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier =
                    Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.exclude(WindowInsets.statusBars)
                        )
                        .padding(horizontal = 14.dp, vertical = 16.dp),
            ) {
                VoiceAssistantPopup(
                    uiState = uiState,
                    onStartListening = {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.startListening()
                        } else {
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStopListening = viewModel::finishListening,
                    onCancelTurn = viewModel::cancelAssistantTurn,
                    onSubmitTextPrompt = viewModel::submitTextPrompt,
                )
            }
        }
    }
}

@Composable
private fun VoiceAssistantPopup(
    uiState: VoiceAssistantUiState,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onCancelTurn: () -> Unit,
    onSubmitTextPrompt: (String) -> Unit,
) {
    val visuals = assistantVisualState(uiState)
    val shape = RoundedCornerShape(28.dp)
    var prompt by rememberSaveable { mutableStateOf("") }
    val transcript =
        uiState.partialTranscript.ifBlank {
            uiState.lastTranscript
        }
    val assistantMessage =
        uiState.statusMessage
            ?.takeIf { it.isNotBlank() }
    val isIdle = uiState.sessionState == VoiceAssistantSessionState.Idle ||
        uiState.sessionState == VoiceAssistantSessionState.MissingVoice
    val animatedShadow by animateDpAsState(
        targetValue = if (isIdle) 8.dp else 12.dp,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "popup-shadow",
    )
    val animatedBorderAlpha by animateFloatAsState(
        targetValue = if (isIdle) 0.92f else 1f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "popup-border-alpha",
    )

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .animateContentSize(animationSpec = tween(320, easing = FastOutSlowInEasing)),
        shape = shape,
        tonalElevation = 2.dp,
        shadowElevation = animatedShadow,
        border = BorderStroke(
            1.dp,
            AssistantStrokeIdle.copy(alpha = animatedBorderAlpha),
        ),
        color = AssistantSurface,
        contentColor = AssistantTextPrimary,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistantCompactHeader(
                visuals = visuals,
                uiState = uiState,
            )

            if (transcript.isNotBlank() || assistantMessage != null) {
                AssistantConversationPanel(
                    transcript = transcript,
                    assistantMessage = assistantMessage,
                )
            }

            AssistantControls(
                uiState = uiState,
                accent = visuals.accent,
                onStartListening = onStartListening,
                onStopListening = onStopListening,
                onCancelTurn = onCancelTurn,
                prompt = prompt,
                onPromptChange = { prompt = it },
                onSubmitPrompt = {
                    val submitted = prompt.trim()
                    if (submitted.isNotBlank()) {
                        prompt = ""
                        onSubmitTextPrompt(submitted)
                    }
                },
            )
        }
    }
}

@Composable
private fun AssistantCompactHeader(
    visuals: AssistantVisualState,
    uiState: VoiceAssistantUiState,
) {
    val animatedAccent by animateColorAsState(
        targetValue = visuals.accent,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "header-accent",
    )
    
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 2.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AnimatedVisibility(
            visible = uiState.sessionState == VoiceAssistantSessionState.Listening,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.6f, animationSpec = tween(240, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.6f, animationSpec = tween(140)),
        ) {
            AssistantVoiceWaveform(
                accent = animatedAccent,
                modifier =
                    Modifier
                        .width(58.dp)
                        .height(32.dp),
            )
        }
        AnimatedVisibility(
            visible = uiState.sessionState != VoiceAssistantSessionState.Listening,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.4f, animationSpec = spring(stiffness = Spring.StiffnessMedium)),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.4f, animationSpec = tween(140)),
        ) {
            val headerOrbState = when (uiState.sessionState) {
                VoiceAssistantSessionState.Processing   -> OrbState.WORKING
                VoiceAssistantSessionState.Speaking     -> OrbState.LISTENING
                else                                    -> OrbState.SHAPING
            }
            ThinkingOrb(
                state = headerOrbState,
                orbSize = OrbSize.SMALL,
                theme = OrbTheme.AUTO,
                modifier = androidx.compose.ui.Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = visuals.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = AssistantTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = visuals.detail,
                style = MaterialTheme.typography.labelMedium,
                color = AssistantTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (visuals.showActivity) {
        AssistantActivityLine(accent = animatedAccent)
    }
}

@Composable
private fun AssistantStatePanel(
    visuals: AssistantVisualState,
    sessionState: VoiceAssistantSessionState,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = visuals.accent.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                )
                .border(
                    width = 1.dp,
                    color = visuals.accent.copy(alpha = 0.20f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (sessionState == VoiceAssistantSessionState.Listening) {
                AssistantVoiceWaveform(
                    accent = visuals.accent,
                    modifier =
                        Modifier
                            .width(92.dp)
                            .height(68.dp),
                )
            } else {
                AssistantStatusOrb(
                    sessionState = sessionState,
                    visuals = visuals,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = visuals.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = visuals.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (visuals.showActivity) {
            AssistantActivityLine(accent = visuals.accent)
        }
    }
}

@Composable
private fun AssistantActivityLine(accent: Color) {
    val phaseTransition = rememberInfiniteTransition(label = "assistant-activity-line")
    val phase by phaseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 2_800, easing = FastOutSlowInEasing),
            ),
        label = "assistant-activity-line-phase",
    )
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(3.dp),
    ) {
        val trackHeight = 1.dp.toPx()
        val y = size.height / 2f
        drawRoundRect(
            color = accent.copy(alpha = 0.16f),
            topLeft = Offset(0f, y - (trackHeight / 2f)),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight, trackHeight),
        )
        val sweepWidth = size.width * 0.26f
        val centerX = (size.width + sweepWidth) * phase - (sweepWidth / 2f)
        drawRoundRect(
            color = accent.copy(alpha = 0.56f),
            topLeft = Offset(centerX - (sweepWidth / 2f), y - trackHeight),
            size = Size(sweepWidth, trackHeight * 2f),
            cornerRadius = CornerRadius(trackHeight * 2f, trackHeight * 2f),
        )
    }
}

@Composable
private fun AssistantVoiceWaveform(
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "assistant-waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation =
                    tween(
                        durationMillis = WAVEFORM_CYCLE_MS,
                        easing = LinearEasing,
                    ),
            ),
        label = "assistant-waveform-phase",
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.14f)),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            val laneCount = SIGNAL_LANE_COUNT
            val laneHeight = size.height / laneCount.toFloat()
            repeat(laneCount) { index ->
                val wave =
                    ((sin((phase * TWO_PI) + (index * SIGNAL_PHASE_STEP)) + 1.0) / 2.0)
                        .toFloat()
                val width = size.width * (SIGNAL_MIN_WIDTH + (wave * SIGNAL_WIDTH_RANGE))
                val top = (index * laneHeight) + ((laneHeight - SIGNAL_LINE_HEIGHT_DP.dp.toPx()) / 2f)
                val alpha = 0.22f + (wave * 0.42f)
                drawRoundRect(
                    color = accent.copy(alpha = alpha),
                    topLeft = Offset((size.width - width) / 2f, top),
                    size = Size(width, SIGNAL_LINE_HEIGHT_DP.dp.toPx()),
                    cornerRadius =
                        CornerRadius(
                            SIGNAL_LINE_HEIGHT_DP.dp.toPx(),
                            SIGNAL_LINE_HEIGHT_DP.dp.toPx(),
                        ),
                )
            }
        }
    }
}

@Composable
private fun AssistantStatusOrb(
    sessionState: VoiceAssistantSessionState,
    visuals: AssistantVisualState,
) {
    val orbState = when (sessionState) {
        VoiceAssistantSessionState.Processing   -> OrbState.WORKING
        VoiceAssistantSessionState.Speaking     -> OrbState.LISTENING
        VoiceAssistantSessionState.MissingVoice -> OrbState.SHAPING
        else                                    -> OrbState.SHAPING
    }
    Box(
        modifier = Modifier.size(68.dp),
        contentAlignment = Alignment.Center,
    ) {
        ThinkingOrb(
            state = orbState,
            orbSize = OrbSize.LARGE,
            theme = OrbTheme.AUTO,
            contentDescription = sessionState.name,
        )
    }
}

@Composable
private fun AssistantConversationPanel(
    transcript: String,
    assistantMessage: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 132.dp)
                .background(
                    color = AssistantSurfaceRaised.copy(alpha = 0.76f),
                    shape = RoundedCornerShape(18.dp),
                )
                .border(
                    width = 1.dp,
                    color = AssistantStrokeIdle.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(18.dp),
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = transcript.isNotBlank(),
            enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) + slideInVertically(
                animationSpec = tween(260, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 5 },
            ),
            exit = fadeOut(tween(140)),
        ) {
            AssistantMessage(
                label = "You",
                text = transcript,
                emphasized = true,
            )
        }
        AnimatedVisibility(
            visible = assistantMessage != null,
            enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) + slideInVertically(
                animationSpec = tween(260, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 5 },
            ),
            exit = fadeOut(tween(140)),
        ) {
            assistantMessage?.let { msg ->
                AssistantMessage(
                    label = "ZeroClaw",
                    text = msg,
                    emphasized = false,
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    label: String,
    text: String,
    emphasized: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (emphasized) {
                    AssistantAccentTeal
                } else {
                    AssistantAccentBlue
                },
            fontWeight = FontWeight.SemiBold,
        )
        LinkifiedText(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AssistantTextPrimary,
        )
    }
}

@Composable
private fun AssistantControls(
    uiState: VoiceAssistantUiState,
    accent: Color,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onCancelTurn: () -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onSubmitPrompt: () -> Unit,
) {
    val listening = uiState.sessionState == VoiceAssistantSessionState.Listening
    val busy =
        uiState.sessionState == VoiceAssistantSessionState.Processing ||
            uiState.sessionState == VoiceAssistantSessionState.Speaking
    val controlEnabled = (uiState.canListen && !busy) || listening || busy
    val textEnabled = !busy && !listening
    val canSubmit = prompt.isNotBlank() && textEnabled
    val placeholder =
        when {
            listening -> "Listening..."
            busy -> uiState.statusMessage?.takeIf { it.isNotBlank() } ?: "Thinking..."
            else -> "Ask Zero-Assist"
        }

    val controlBorderAlpha by animateFloatAsState(
        targetValue = if (listening) 0.50f else 0.86f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "control-border-alpha",
    )
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        shape = RoundedCornerShape(26.dp),
        color = AssistantSurfaceRaised,
        contentColor = AssistantTextPrimary,
        border =
            BorderStroke(
                1.dp,
                if (listening) {
                    accent.copy(alpha = controlBorderAlpha)
                } else {
                    AssistantStrokeIdle.copy(alpha = controlBorderAlpha)
                },
            ),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (listening) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistantVoiceWaveform(
                            accent = accent,
                            modifier =
                                Modifier
                                    .width(56.dp)
                                    .height(22.dp),
                        )
                        Text(
                            text = "Listening...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = accent,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(accent),
                        )
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = accent,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    BasicTextField(
                        value = prompt,
                        onValueChange = onPromptChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = textEnabled,
                        singleLine = true,
                        textStyle =
                            MaterialTheme.typography.bodyMedium.copy(
                                color = AssistantTextPrimary,
                            ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(AssistantAccentTeal),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions =
                            KeyboardActions(
                                onSend = {
                                    if (canSubmit) {
                                        onSubmitPrompt()
                                    }
                                },
                            ),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (prompt.isBlank()) {
                                    Text(
                                        text = placeholder,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AssistantTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
            }

            val micBgAlpha by animateFloatAsState(
                targetValue = if (listening || busy) 0.12f else 0f,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                label = "mic-bg-alpha",
            )
            IconButton(
                onClick = {
                    when {
                        busy -> onCancelTurn()
                        listening -> onStopListening()
                        else -> onStartListening()
                    }
                },
                enabled = controlEnabled,
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(
                            accent.copy(alpha = micBgAlpha),
                            CircleShape,
                        )
                        .semantics { role = Role.Button },
            ) {
                Icon(
                    imageVector = if (busy || listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription =
                        if (busy) {
                            "Stop"
                        } else if (listening) {
                            "Stop listening"
                        } else {
                            "Start listening"
                        },
                    modifier = Modifier.size(20.dp),
                    tint =
                        if (controlEnabled) {
                            if (busy || listening) accent else AssistantAccentBlue
                        } else {
                            AssistantTextDisabled
                        },
                )
            }

            AnimatedVisibility(
                visible = prompt.isNotBlank() && !listening && !busy,
                enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) + scaleIn(
                    initialScale = 0.5f,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = 0.7f),
                ),
                exit = fadeOut(tween(120)) + scaleOut(
                    targetScale = 0.5f,
                    animationSpec = tween(120, easing = FastOutLinearInEasing),
                ),
            ) {
                IconButton(
                    onClick = onSubmitPrompt,
                    enabled = canSubmit,
                    modifier = Modifier
                        .size(40.dp)
                        .background(AssistantAccentTeal.copy(alpha = 0.12f), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send text request",
                        modifier = Modifier.size(20.dp),
                        tint = AssistantAccentTeal,
                    )
                }
            }
        }
    }
}

@Composable
private fun assistantVisualState(uiState: VoiceAssistantUiState): AssistantVisualState {
    return when (uiState.sessionState) {
        VoiceAssistantSessionState.MissingVoice ->
            AssistantVisualState(
                title = "Text ready",
                detail = "Install a local voice for hands-free input.",
                accent = AssistantAccentAmber,
                showActivity = false,
            )
        VoiceAssistantSessionState.Idle ->
            AssistantVisualState(
                title = "Ready",
                detail = readinessDetail(uiState),
                accent = AssistantAccentTeal,
                showActivity = false,
            )
        VoiceAssistantSessionState.Listening ->
            AssistantVisualState(
                title = "Listening",
                detail = "Speech recognition is active.",
                accent = AssistantAccentBlue,
                showActivity = true,
            )
        VoiceAssistantSessionState.Processing ->
            AssistantVisualState(
                title = "Thinking",
                detail = "ZeroClaw is handling your request.",
                accent = AssistantAccentViolet,
                showActivity = true,
            )
        VoiceAssistantSessionState.Speaking ->
            AssistantVisualState(
                title = "Speaking",
                detail = "Playing local voice response.",
                accent = AssistantAccentTeal,
                showActivity = true,
            )
    }
}

@Immutable
private data class AssistantVisualState(
    val title: String,
    val detail: String,
    val accent: Color,
    val showActivity: Boolean,
)

private fun readinessDetail(uiState: VoiceAssistantUiState): String =
    when {
        !uiState.speechRecognitionAvailable ->
            uiState.speechRecognitionStatusMessage.orEmpty()
        uiState.wakeupEnabled && !uiState.wakeupAvailable ->
            uiState.wakeupStatusMessage
        uiState.selectedVoice != null ->
            "Ask by typing or voice."
        else ->
            "Ask by typing."
    }

private val AssistantSurface = Color(0xF21B1D24)
private val AssistantSurfaceRaised = Color(0xFF242733)
private val AssistantStrokeIdle = Color(0xFF3A3E4C)
private val AssistantTextPrimary = Color(0xFFF4F5F7)
private val AssistantTextSecondary = Color(0xFFB4BAC7)
private val AssistantTextDisabled = Color(0xFF737B8B)
private val AssistantAccentTeal = Color(0xFFA7E3D8)
private val AssistantAccentBlue = Color(0xFF9EC8FF)
private val AssistantAccentViolet = Color(0xFFC7BBFF)
private val AssistantAccentAmber = Color(0xFFE3C877)
private const val SIGNAL_LANE_COUNT = 3
private const val SIGNAL_LINE_HEIGHT_DP = 2
private const val SIGNAL_MIN_WIDTH = 0.28f
private const val SIGNAL_WIDTH_RANGE = 0.46f
private const val WAVEFORM_CYCLE_MS = 1_800
private const val SIGNAL_PHASE_STEP = 1.45f
private const val TWO_PI = (Math.PI * 2.0).toFloat()
