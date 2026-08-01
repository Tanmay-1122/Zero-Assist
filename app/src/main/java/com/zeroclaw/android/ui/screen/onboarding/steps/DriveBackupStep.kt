/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.onboarding.steps

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.ui.theme.ZeroClawTheme
import com.zeroclaw.android.ui.theme.zeroAssistSecondaryActionButtonColors

private val TitleSpacing = 16.dp
private val DescriptionSpacing = 20.dp
private val CardSpacing = 24.dp
private val ActionSpacing = 12.dp
private val CardPadding = 16.dp

@Composable
fun DriveBackupStep(
    isSignedIn: Boolean,
    preferredName: String?,
    email: String?,
    onContinueWithGoogle: () -> Unit,
    onUseEverythingLocally: () -> Unit,
    onContinueSetup: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = "Google Drive Backup",
            style = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.height(TitleSpacing))

        Text(
            text =
                "Connect Google Drive if you want cloud backup and restore for your agents, " +
                    "plugins, and API keys. This step is optional, and you can keep everything local.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(DescriptionSpacing))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(CardPadding)) {
                if (isSignedIn) {
                    Text(
                        text = "Google Drive is connected",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = preferredName ?: email.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!email.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = email.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text =
                            "You can continue setup with this account, or sign out and keep using the app locally.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Cloud backup is optional",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text =
                            "Google handles both login and signup. If you prefer, you can skip this entirely and use Zero-Assist only on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(CardSpacing))

        if (isSignedIn) {
            Button(
                onClick = onContinueSetup,
                modifier = Modifier.fillMaxWidth(),
                colors = zeroAssistSecondaryActionButtonColors(),
            ) {
                Text("Continue Setup")
            }
            Spacer(modifier = Modifier.height(ActionSpacing))
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign Out")
            }
        } else {
            Button(
                onClick = onContinueWithGoogle,
                modifier = Modifier.fillMaxWidth(),
                colors = zeroAssistSecondaryActionButtonColors(),
            ) {
                Text("Continue with Google")
            }
            Spacer(modifier = Modifier.height(ActionSpacing))
            OutlinedButton(
                onClick = onUseEverythingLocally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use Everything Locally")
            }
        }
    }
}

@Preview(name = "Drive Backup Step")
@Composable
private fun DriveBackupStepPreview() {
    ZeroClawTheme {
        Surface {
            DriveBackupStep(
                isSignedIn = false,
                preferredName = null,
                email = null,
                onContinueWithGoogle = {},
                onUseEverythingLocally = {},
                onContinueSetup = {},
                onSignOut = {},
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Preview(
    name = "Drive Backup Step - Connected",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DriveBackupStepConnectedPreview() {
    ZeroClawTheme {
        Surface {
            DriveBackupStep(
                isSignedIn = true,
                preferredName = "Tanmay",
                email = "tanmay@example.com",
                onContinueWithGoogle = {},
                onUseEverythingLocally = {},
                onContinueSetup = {},
                onSignOut = {},
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
