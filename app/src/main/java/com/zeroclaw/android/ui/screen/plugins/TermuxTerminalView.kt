/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.service.termux.TermuxAuditRecord
import com.zeroclaw.android.service.termux.TermuxAuditState
import com.zeroclaw.android.ui.theme.JetBrainsMonoFamily
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun TermuxTerminalView(
    records: List<TermuxAuditRecord>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
    ) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 300.dp),
        ) {
            items(records, key = { it.id }) { record ->
                TerminalEntry(record)
            }
            if (records.isEmpty()) {
                item {
                    Text(
                        text = "No command history yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalEntry(record: TermuxAuditRecord) {
    val stateColor =
        when (record.state) {
            TermuxAuditState.APPROVED,
            TermuxAuditState.EXECUTED,
            -> MaterialTheme.colorScheme.primary
            TermuxAuditState.DENIED,
            TermuxAuditState.BLOCKED,
            -> MaterialTheme.colorScheme.error
            TermuxAuditState.REQUESTED -> MaterialTheme.colorScheme.tertiary
            TermuxAuditState.FAILED -> MaterialTheme.colorScheme.error
        }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "[${record.requestedAt.atZone(java.time.ZoneId.systemDefault()).format(TIME_FORMAT)}] ${record.commandPreview}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "  ${record.state.name.lowercase()}${record.reason?.let { " — $it" } ?: ""}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = stateColor,
        )
    }
}
