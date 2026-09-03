package com.zeroclaw.android.screen

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.screen.helpers.fakeTerminalState
import com.zeroclaw.android.ui.screen.terminal.StreamingPhase
import com.zeroclaw.android.ui.screen.terminal.StreamingState
import com.zeroclaw.android.ui.screen.terminal.TerminalBlock
import com.zeroclaw.android.ui.screen.terminal.TerminalContent
import com.zeroclaw.android.ui.screen.terminal.TerminalActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose screen tests for [TerminalContent].
 *
 * Uses the stateless [TerminalContent] composable with fake state to verify
 * rendering of terminal blocks, input bar controls, loading indicators,
 * and error display.
 */
@RunWith(AndroidJUnit4::class)
class TerminalScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val testActions = TerminalActions(
        onSubmit = {},
        onAttachImages = {},
        onRemoveImage = {},
        onCancelAgent = {},
    )

    @Test
    fun terminalContent_rendersSystemBlock() {
        val state = fakeTerminalState()
        composeTestRule.setContent {
            TerminalContent(
                state = state,
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                terminalActions = testActions,
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithText("ZeroClaw Terminal v0.0.37 \u2014 Type /help for commands")
            .assertIsDisplayed()
    }

    @Test
    fun inputBar_hasSendButton() {
        composeTestRule.setContent {
            TerminalContent(
                state = fakeTerminalState(),
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                terminalActions = testActions,
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithContentDescription("Send")
            .assertIsDisplayed()
    }

    @Test
    fun inputBar_hasAttachButton() {
        composeTestRule.setContent {
            TerminalContent(
                state = fakeTerminalState(),
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                terminalActions = testActions,
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithContentDescription("Attach images")
            .assertIsDisplayed()
    }

    @Test
    fun spinner_showsWhenLoading() {
        val state = fakeTerminalState().copy(isLoading = true)
        composeTestRule.setContent {
            TerminalContent(
                state = state,
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                terminalActions = testActions,
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithText("Thinking\u2026")
            .assertIsDisplayed()
    }

    @Test
    fun errorBlock_showsErrorPrefix() {
        val state =
            fakeTerminalState().copy(
                blocks =
                    listOf(
                        TerminalBlock.Error(
                            id = 2,
                            timestamp = System.currentTimeMillis(),
                            message = "Connection refused",
                        ),
                    ),
            )
        composeTestRule.setContent {
            TerminalContent(
                state = state,
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                terminalActions = testActions,
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithText("Error: Connection refused")
            .assertIsDisplayed()
    }

    @Test
    fun terminalHeader_exposesStatusSemantics() {
        composeTestRule.setContent {
            TerminalContent(
                state = fakeTerminalState(),
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                terminalActions = testActions,
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithContentDescription("Terminal controls, status: running")
            .assertExists()
    }

    @Test
    fun responseBlock_rendersUrlText() {
        val url = "https://www.youtube.com/watch?v=abc123"
        val state =
            fakeTerminalState().copy(
                blocks =
                    listOf(
                        TerminalBlock.Response(
                            id = 2,
                            timestamp = System.currentTimeMillis(),
                            content = "Latest video: $url.",
                        ),
                    ),
            )

        composeTestRule.setContent {
            TerminalContent(
                state = state,
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                terminalActions = testActions,
                edgeMargin = 16.dp,
            )
        }

        composeTestRule
            .onNodeWithText("Latest video: $url.")
            .assertIsDisplayed()
    }

    @Test
    fun streamingResponseBlock_rendersUrlText() {
        val url = "https://drive.google.com/file/d/abc/view?usp=sharing"

        composeTestRule.setContent {
            TerminalContent(
                state = fakeTerminalState().copy(blocks = emptyList()),
                streamingState =
                    StreamingState(
                        phase = StreamingPhase.RESPONDING,
                        responseText = "Drive file: $url",
                    ),
                serviceState = ServiceState.RUNNING,
                terminalActions = testActions,
                edgeMargin = 16.dp,
            )
        }

        composeTestRule
            .onNodeWithText("Drive file: $url")
            .assertIsDisplayed()
    }
}
