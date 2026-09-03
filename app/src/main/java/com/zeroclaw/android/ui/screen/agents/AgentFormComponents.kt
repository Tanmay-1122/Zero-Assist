/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.model.ThinkingLevel

/** Common emoji options for avatar selection. */
private val COMMON_EMOJIS = listOf(
    "🤖", "👑", "🔍", "💻", "📋", "✍️", "📊", "⚡",
    "🎯", "🧠", "🔧", "📱", "🌐", "💡", "📈", "🎨",
    "🚀", "⚙️", "🔐", "📚", "🎭", "🎪", "🌟", "💼",
)

/**
 * Allows the user to choose an avatar emoji.
 */
@Composable
fun EmojiPickerButton(
    selectedEmoji: String,
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }

    Button(
        onClick = { showDialog = true },
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(if (selectedEmoji.isEmpty()) "Select Avatar" else "Avatar: $selectedEmoji")
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Select Avatar Emoji") },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(COMMON_EMOJIS) { emoji ->
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (emoji == selectedEmoji) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .border(
                                    width = if (emoji == selectedEmoji) 2.dp else 1.dp,
                                    color = if (emoji == selectedEmoji) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable {
                                    onEmojiSelected(emoji)
                                    showDialog = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { showDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
}

/**
 * Lets the user choose an agent role.
 */
@Composable
fun RoleDropdown(
    selectedRole: AgentRole,
    onRoleSelected: (AgentRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = "${selectedRole.icon} ${selectedRole.displayName}",
            onValueChange = {},
            label = { Text("Role") },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                )
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            AgentRole.entries.forEach { role ->
                DropdownMenuItem(
                    text = { Text("${role.icon} ${role.displayName}") },
                    onClick = {
                        onRoleSelected(role)
                        expanded = false
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true },
        )
    }
}

/**
 * Lets the user add and remove agent tags.
 */
@Composable
fun TagsInput(
    tags: List<String>,
    onTagsChanged: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tagInput by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        Text(
            text = "Tags",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = tagInput,
                onValueChange = { tagInput = it },
                label = { Text("Add tags") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val normalized = tagInput.trim()
                    if (normalized.isNotEmpty() && normalized !in tags) {
                        onTagsChanged(tags + normalized)
                        tagInput = ""
                    }
                },
                modifier = Modifier.height(56.dp),
            ) {
                Text("Add")
            }
        }

        if (tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            TagChipRow(
                tags = tags,
                onRemove = { tag -> onTagsChanged(tags - tag) },
            )
        }
    }
}

/**
 * Displays selected tags with remove buttons.
 */
@Composable
private fun TagChipRow(
    tags: List<String>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    IconButton(
                        onClick = { onRemove(tag) },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove tag",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lets the user mark an agent as the single master.
 */
@Composable
fun MasterAgentToggle(
    isMaster: Boolean,
    onMasterChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showWarning by remember { mutableStateOf(false) }
    var pendingValue by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(
            checked = isMaster,
            onCheckedChange = { newValue ->
                if (newValue) {
                    pendingValue = true
                    showWarning = true
                } else {
                    onMasterChanged(false)
                }
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Set as Master Agent",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (isMaster) "This agent orchestrates all others" else "This agent is not the master",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = {
                showWarning = false
                pendingValue = false
            },
            title = { Text("Replace Master Agent") },
            text = {
                Text("Setting this agent as Master will replace the current Master agent. Continue?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onMasterChanged(pendingValue)
                        showWarning = false
                    },
                ) {
                    Text("Yes, Replace")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showWarning = false
                        pendingValue = false
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Lets the user choose a priority from 0 to 10.
 */
@Composable
fun PrioritySlider(
    priority: Int,
    onPriorityChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Priority",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "$priority/10",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }

        Slider(
            value = priority.toFloat(),
            onValueChange = { onPriorityChanged(it.toInt()) },
            valueRange = 0f..10f,
            steps = 9,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = when (priority) {
                0 -> "Lowest priority - runs when nothing else needs attention"
                5 -> "Medium priority - balanced execution"
                10 -> "Highest priority - executes first"
                else -> "Priority level $priority"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Three-option thinking depth selector for agent defaults.
 */
@Composable
fun ThinkingLevelSelector(
    selectedLevel: ThinkingLevel,
    onLevelSelected: (ThinkingLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Thinking Level",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThinkingLevel.entries.forEach { level ->
                val selected = level == selectedLevel
                Surface(
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    shape = RoundedCornerShape(8.dp),
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable { onLevelSelected(level) },
                ) {
                    Text(
                        text = level.label,
                        style = MaterialTheme.typography.labelLarge,
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
