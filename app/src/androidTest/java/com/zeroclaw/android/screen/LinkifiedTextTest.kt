/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zeroclaw.android.ui.component.LinkifiedText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinkifiedTextTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun clickingDetectedUrlInvokesInjectedOpener() {
        var openedUrl: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                LinkifiedText(
                    text = "https://example.com/path?x=1",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    onOpenUrl = { openedUrl = it },
                )
            }
        }

        composeTestRule.onNodeWithText("https://example.com/path?x=1").performClick()

        composeTestRule.runOnIdle {
            assertEquals("https://example.com/path?x=1", openedUrl)
        }
    }

    @Test
    fun clickingWwwUrlInvokesInjectedOpenerWithHttpsUrl() {
        var openedUrl: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                LinkifiedText(
                    text = "www.example.com/watch?v=abc",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    onOpenUrl = { openedUrl = it },
                )
            }
        }

        composeTestRule.onNodeWithText("www.example.com/watch?v=abc").performClick()

        composeTestRule.runOnIdle {
            assertEquals("https://www.example.com/watch?v=abc", openedUrl)
        }
    }
}
