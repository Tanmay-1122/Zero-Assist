/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroclaw.android.model.Skill
import com.zeroclaw.android.ui.component.CategoryBadge
import com.zeroclaw.android.ui.component.ErrorCard
import com.zeroclaw.android.ui.component.LoadingIndicator
import com.zeroclaw.android.ui.component.OfficialPluginBadge
import com.zeroclaw.android.ui.component.PluginSectionHeader
import com.zeroclaw.android.ui.theme.ZeroAssistSpacing
import com.zeroclaw.android.ui.theme.zeroAssistOutlinedTextFieldColors
import com.zeroclaw.android.ui.theme.zeroAssistSecondaryActionButtonColors

/** Maximum number of badges to show before truncating the row. */
private const val MAX_BADGES = 3

@Composable
fun SkillsTab(
    skillsViewModel: SkillsViewModel,
    modifier: Modifier = Modifier,
) {
    val filteredState by skillsViewModel.filteredUiState.collectAsStateWithLifecycle()
    val filteredMarketplaceState by skillsViewModel.filteredMarketplaceUiState.collectAsStateWithLifecycle()
    val searchQuery by skillsViewModel.searchQuery.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            colors = zeroAssistOutlinedTextFieldColors(),
            value = searchQuery,
            onValueChange = { skillsViewModel.updateSearch(it) },
            label = { Text("Search skills and marketplace") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Medium),
        ) {
            item(key = "skills-overview", contentType = "overview") {
                SkillsOverviewCard()
            }

            item(key = "installed-header", contentType = "section-header") {
                PluginSectionHeader(
                    title = "Installed Skills",
                    count = sectionCount(filteredState),
                )
            }
            installedSkillsSection(
                state = filteredState,
                searchQuery = searchQuery,
                onRetry = skillsViewModel::loadSkills,
                onRemoveSkill = skillsViewModel::removeSkill,
            )

            item(key = "marketplace-header", contentType = "section-header") {
                PluginSectionHeader(
                    title = "Marketplace",
                    count = sectionCount(filteredMarketplaceState),
                )
            }
            marketplaceSkillsSection(
                state = filteredMarketplaceState,
                searchQuery = searchQuery,
                onRetry = skillsViewModel::loadMarketplaceSkills,
                onInstallSkill = skillsViewModel::installMarketplaceSkill,
            )
        }
    }
}

private fun LazyListScope.installedSkillsSection(
    state: SkillsUiState<List<Skill>>,
    searchQuery: String,
    onRetry: () -> Unit,
    onRemoveSkill: (String) -> Unit,
) {
    when (state) {
        is SkillsUiState.Loading -> {
            item(key = "installed-loading", contentType = "state") {
                SectionLoadingCard(message = "Loading installed skills")
            }
        }

        is SkillsUiState.Error -> {
            item(key = "installed-error", contentType = "state") {
                ErrorCard(
                    message = state.detail,
                    onRetry = onRetry,
                )
            }
        }

        is SkillsUiState.Content -> {
            if (state.data.isEmpty()) {
                item(key = "installed-empty", contentType = "state") {
                    SectionMessageCard(
                        icon = Icons.Outlined.AutoFixHigh,
                        title = "No installed skills yet",
                        message =
                            if (searchQuery.isBlank()) {
                                "Skills you add from the marketplace will show up here automatically."
                            } else {
                                "No installed skills match your search right now."
                            },
                    )
                }
            } else {
                items(
                    items = state.data,
                    key = { it.name },
                    contentType = { "installed-skill" },
                ) { skill ->
                    val onRemove =
                        remember(skill.name) {
                            { onRemoveSkill(skill.name) }
                        }
                    SkillCard(
                        skill = skill,
                        onRemove = onRemove,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.marketplaceSkillsSection(
    state: SkillsUiState<List<MarketplaceSkillItem>>,
    searchQuery: String,
    onRetry: () -> Unit,
    onInstallSkill: (String) -> Unit,
) {
    when (state) {
        is SkillsUiState.Loading -> {
            item(key = "marketplace-loading", contentType = "state") {
                SectionLoadingCard(message = "Loading official marketplace")
            }
        }

        is SkillsUiState.Error -> {
            item(key = "marketplace-error", contentType = "state") {
                ErrorCard(
                    message = state.detail,
                    onRetry = onRetry,
                )
            }
        }

        is SkillsUiState.Content -> {
            if (state.data.isEmpty()) {
                item(key = "marketplace-empty", contentType = "state") {
                    SectionMessageCard(
                        icon = Icons.Outlined.Extension,
                        title = "Marketplace is quiet",
                        message =
                            if (searchQuery.isBlank()) {
                                "We could not find any marketplace skills to show right now."
                            } else {
                                "No marketplace skills match your search right now."
                            },
                    )
                }
            } else {
                items(
                    items = state.data,
                    key = { it.skill.name },
                    contentType = { "marketplace-skill" },
                ) { skill ->
                    val onInstall =
                        remember(skill.skill.name) {
                            { onInstallSkill(skill.skill.name) }
                        }
                    MarketplaceSkillCard(
                        item = skill,
                        onInstall = onInstall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillsOverviewCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(ZeroAssistSpacing.Large)) {
            Text(
                text = "Teach ZeroClaw new skills",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Installed skills become available to ZeroClaw automatically. Browse the official marketplace to add new capabilities, or keep using only what is already local on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        "${skill.name}: ${skill.description}, " +
                            "${skill.toolCount} tools, version ${skill.version}"
                },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(ZeroAssistSpacing.Large)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier =
                        Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Remove skill ${skill.name}" },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "v${skill.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                skill.author?.let { author ->
                    Text(
                        text = " - $author",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${skill.toolCount} tools",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (skill.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.XSmall)) {
                    skill.tags.take(MAX_BADGES).forEach { tag ->
                        CategoryBadge(category = tag)
                    }
                }
            }

            if (skill.toolNames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.XSmall)) {
                    skill.toolNames.take(MAX_BADGES).forEach { tool ->
                        CategoryBadge(category = tool)
                    }
                    if (skill.toolNames.size > MAX_BADGES) {
                        CategoryBadge(category = "+${skill.toolNames.size - MAX_BADGES}")
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketplaceSkillCard(
    item: MarketplaceSkillItem,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val skill = item.skill
    val extraTags =
        skill.tags
            .filterNot {
                it.equals("Official", ignoreCase = true) || it.equals("Featured", ignoreCase = true)
            }.take(MAX_BADGES - 1)

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(ZeroAssistSpacing.Large)) {
            Text(
                text = skill.name,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CategoryBadge(category = skill.category)
                if (skill.isOfficial) {
                    OfficialPluginBadge()
                }
                if (skill.isFeatured) {
                    CategoryBadge(category = "Featured")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "v${skill.version} - ${skill.author}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${skill.downloads} downloads - ${skill.stars} stars",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (extraTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.XSmall)) {
                    extraTags.forEach { tag ->
                        CategoryBadge(category = tag)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onInstall,
                enabled = !item.isInstalled && !item.isInstalling,
                colors = zeroAssistSecondaryActionButtonColors(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics {
                            contentDescription =
                                when {
                                    item.isInstalling -> "Installing ${skill.name}"
                                    item.isInstalled -> "${skill.name} is installed"
                                    else -> "Add ${skill.name} to ZeroClaw"
                                }
                        },
            ) {
                Text(
                    text =
                        when {
                            item.isInstalling -> "Installing..."
                            item.isInstalled -> "Installed"
                            else -> "Add to ZeroClaw"
                        },
                )
            }
        }
    }
}

@Composable
private fun SectionLoadingCard(
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            modifier = Modifier.padding(ZeroAssistSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionMessageCard(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            modifier = Modifier.padding(ZeroAssistSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun <T> sectionCount(state: SkillsUiState<List<T>>): Int =
    when (state) {
        is SkillsUiState.Content -> state.data.size
        is SkillsUiState.Error -> 0
        SkillsUiState.Loading -> 0
    }
