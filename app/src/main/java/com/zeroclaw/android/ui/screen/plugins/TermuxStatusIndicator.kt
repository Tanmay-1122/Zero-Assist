/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.service.termux.TermuxAutoConnector
import kotlinx.coroutines.delay

private val StatusGreen = Color(0xFF4CAF50)
private val StatusYellow = Color(0xFFFFC107)
private val StatusRed = Color(0xFFF44336)

@Composable
fun TermuxStatusIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val autoConnector = remember {
        val app = context.applicationContext as com.zeroclaw.android.ZeroClawApplication
        TermuxAutoConnector(
            context = context,
            probe = app.termuxRuntimeProbe,
            supervisor = app.termuxBridgeSupervisor,
            healthClient = app.termuxHealthClient,
        )
    }

    var state by remember { mutableStateOf<TermuxAutoConnector.ConnectionState>(
        TermuxAutoConnector.ConnectionState.Checking
    ) }

    LaunchedEffect(Unit) {
        while (true) {
            autoConnector.observeConnection().collect { state = it }
            delay(30_000)
        }
    }

    val color =
        when (state) {
            is TermuxAutoConnector.ConnectionState.Connected -> StatusGreen
            is TermuxAutoConnector.ConnectionState.Checking,
            is TermuxAutoConnector.ConnectionState.StartingBridge,
            is TermuxAutoConnector.ConnectionState.TestingConnection,
            -> StatusYellow
            else -> StatusRed
        }

    Box(
        modifier =
            modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
    )
}
