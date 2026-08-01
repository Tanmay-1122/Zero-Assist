package com.zeroclaw.android.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.backup.BackupViewModel
import com.zeroclaw.android.backup.SyncStatus
import com.zeroclaw.android.ui.theme.zeroAssistSecondaryActionButtonColors

@Composable
fun BackupSettingsSection(viewModel: BackupViewModel) {
    val context = LocalContext.current
    val isSignedIn by viewModel.isSignedIn.collectAsState()
    val userEmail by viewModel.userEmail.collectAsState()
    val preferredName by viewModel.preferredName.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val signInLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            viewModel.handleSignInResult(context, result.data)
        }

    LaunchedEffect(viewModel, context) {
        viewModel.refreshSignInState()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Cloud Backup",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (!isSignedIn) {
                Text(
                    text = "Sign in to back up your agents, plugins, and API keys",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { signInLauncher.launch(viewModel.getSignInIntent(context)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = zeroAssistSecondaryActionButtonColors(),
                ) {
                    Text("Sign in with Google")
                }
            } else {
                Text(
                    text = "Connected to Google Drive",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = preferredName ?: userEmail.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (!userEmail.isNullOrBlank()) {
                    Text(
                        text = userEmail.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                SyncStatusIndicator(syncStatus)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.backupNow() },
                        enabled = syncStatus != SyncStatus.SYNCING,
                        colors = zeroAssistSecondaryActionButtonColors(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Backup Now")
                    }
                    OutlinedButton(
                        onClick = { viewModel.signOut(context) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Sign Out")
                    }
                }
            }
        }
    }
}
