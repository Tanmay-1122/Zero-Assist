/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.ui.theme.zeroAssistOutlinedTextFieldColors

/**
 * Chat input bar with inline @mention suggestions.
 */
@Composable
fun ChatInputBar(
    userInput: String,
    onInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onAttachImages: () -> Unit,
    activeAgents: List<Agent>,
    selectedMentionTarget: Agent?,
    onMentionSelected: (Agent) -> Unit,
    onClearMention: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val mentionQuery = findTrailingMentionQuery(userInput)
    val canSend = userInput.isNotBlank()
    val mentionSuggestions =
        if (mentionQuery == null) {
            emptyList()
        } else {
            activeAgents.filter { agent ->
                mentionQuery.isBlank() || agent.name.contains(mentionQuery, ignoreCase = true)
            }
        }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        shape = RoundedCornerShape(24.dp),
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (selectedMentionTarget != null) {
                MentionTargetChip(
                    agent = selectedMentionTarget,
                    onRemove = {
                        onInputChange(removeMentionToken(userInput, selectedMentionTarget.name))
                        onClearMention()
                    },
                    modifier =
                        Modifier
                            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                            .fillMaxWidth(),
                )
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    IconButton(
                        onClick = onAttachImages,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            contentDescription = "Attach images",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = userInput,
                    onValueChange = onInputChange,
                    placeholder = { Text("Type @ to mention an agent") },
                    modifier =
                        Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 54.dp)
                            .then(
                                if (focusRequester != null) {
                                    Modifier.focusRequester(focusRequester)
                                } else {
                                    Modifier
                                },
                            ),
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp),
                    colors = zeroAssistOutlinedTextFieldColors(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSendMessage() }),
                )

                Surface(
                    color =
                        if (canSend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                        },
                    shape = RoundedCornerShape(18.dp),
                ) {
                    IconButton(
                        onClick = onSendMessage,
                        enabled = canSend,
                        modifier = Modifier.size(46.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint =
                                if (canSend) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }

            if (mentionQuery != null && mentionSuggestions.isNotEmpty()) {
                MentionAutocompletePopup(
                    agents = mentionSuggestions,
                    onAgentSelected = { agent ->
                        onInputChange(replaceTrailingMention(userInput, agent.name))
                        onMentionSelected(agent)
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun MentionTargetChip(
    agent: Agent,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Routing to @${agent.name}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove mention",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun MentionAutocompletePopup(
    agents: List<Agent>,
    onAgentSelected: (Agent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
            ),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(
                items = agents,
                key = { it.id },
                contentType = { "agent" },
            ) { agent ->
                MentionSuggestionItem(
                    agent = agent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAgentSelected(agent) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun MentionSuggestionItem(
    agent: Agent,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = agent.avatar,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "@${agent.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = agent.role.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Strip displaying pending images before they are sent.
 */
@Composable
fun PendingImagesStrip(
    images: List<com.zeroclaw.android.model.ProcessedImage>,
    isProcessing: Boolean,
    onRemoveImage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty() && !isProcessing) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEachIndexed { index, image ->
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${image.width}×${image.height}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { onRemoveImage(index) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove image",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (isProcessing) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
