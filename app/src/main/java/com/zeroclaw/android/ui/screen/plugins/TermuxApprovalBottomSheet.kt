/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.service.termux.TermuxApprovalRequest
import com.zeroclaw.android.service.termux.TermuxCommandRisk
import com.zeroclaw.android.ui.theme.JetBrainsMonoFamily
import com.zeroclaw.android.ui.theme.zeroAssistSecondaryActionButtonColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxApprovalBottomSheet(
    request: TermuxApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onPreApprovePattern: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val riskColor =
        when (request.risk) {
            TermuxCommandRisk.LOW -> MaterialTheme.colorScheme.primary
            TermuxCommandRisk.MEDIUM -> MaterialTheme.colorScheme.tertiary
            TermuxCommandRisk.HIGH -> MaterialTheme.colorScheme.error
            TermuxCommandRisk.BLOCKED -> MaterialTheme.colorScheme.error
        }

    ModalBottomSheet(
        onDismissRequest = onDeny,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Termux Command Approval",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Risk: ${request.risk.name}",
                style = MaterialTheme.typography.labelMedium,
                color = riskColor,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = request.commandPreview,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = JetBrainsMonoFamily,
                ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = request.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            FilledTonalButton(
                colors = zeroAssistSecondaryActionButtonColors(),
                onClick = onApprove,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Approve")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Deny")
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (request.risk != TermuxCommandRisk.BLOCKED) {
                OutlinedButton(
                    onClick = {
                        val pattern = request.argv.joinToString(" ").substringBefore(" ") + " *"
                        onPreApprovePattern(pattern)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Pre-approve pattern")
                }
            }
        }
    }
}
