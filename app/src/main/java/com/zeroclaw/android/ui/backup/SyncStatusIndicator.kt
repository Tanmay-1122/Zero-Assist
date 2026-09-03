package com.zeroclaw.android.ui.backup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.backup.SyncStatus
import kotlinx.coroutines.delay

@Composable
fun SyncStatusIndicator(status: SyncStatus) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(status) {
        visible = status != SyncStatus.IDLE
        if (status == SyncStatus.SUCCESS) {
            delay(3000)
            visible = false
        }
    }

    AnimatedVisibility(visible = visible) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (status) {
                SyncStatus.SYNCING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Syncing...",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                SyncStatus.SUCCESS -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Synced",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                SyncStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.SyncProblem,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Sync failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> Unit
            }
        }
    }
}
