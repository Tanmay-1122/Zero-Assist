/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageUrlDetectorTest {
    @Test
    fun detectsOnePlainUrl() {
        val links = MessageUrlDetector.detect("Open https://example.com/docs now")

        assertEquals(
            listOf(DetectedUrl(5, 29, "https://example.com/docs", "https://example.com/docs")),
            links,
        )
    }

    @Test
    fun detectsMultipleUrlsInOneMessage() {
        val links = MessageUrlDetector.detect("Use https://one.example and http://two.example/path")

        assertEquals(listOf("https://one.example", "http://two.example/path"), links.map { it.openUrl })
    }

    @Test
    fun preservesYoutubeQueryParameters() {
        val url = "https://www.youtube.com/watch?v=abc123&list=PL456#top"
        val links = MessageUrlDetector.detect("Latest video: $url")

        assertEquals(url, links.single().openUrl)
    }

    @Test
    fun preservesGoogleDriveQueryParameters() {
        val url = "https://drive.google.com/file/d/abc/view?usp=sharing&resourcekey=0-x"
        val links = MessageUrlDetector.detect("Drive file: $url")

        assertEquals(url, links.single().openUrl)
    }

    @Test
    fun stripsTrailingSentencePunctuation() {
        val links = MessageUrlDetector.detect("Read https://example.com/path, then continue.")

        assertEquals("https://example.com/path", links.single().openUrl)
        assertEquals("https://example.com/path", links.single().displayText)
    }

    @Test
    fun stripsUnmatchedClosingParenthesisAroundUrl() {
        val links = MessageUrlDetector.detect("Use (https://example.com/path).")

        assertEquals("https://example.com/path", links.single().openUrl)
    }

    @Test
    fun keepsBalancedParenthesesInsideUrl() {
        val url = "https://example.com/wiki/Foo_(bar)"
        val links = MessageUrlDetector.detect("Read $url.")

        assertEquals(url, links.single().openUrl)
    }

    @Test
    fun normalizesWwwUrlForOpening() {
        val links = MessageUrlDetector.detect("Visit www.example.com/path?q=1")

        assertEquals("www.example.com/path?q=1", links.single().displayText)
        assertEquals("https://www.example.com/path?q=1", links.single().openUrl)
    }

    @Test
    fun rejectsNonHttpSchemes() {
        val links =
            MessageUrlDetector.detect(
                "Do not open javascript:alert(1), file:///tmp/a, content://provider/item, or tel:555.",
            )

        assertTrue(links.isEmpty())
    }

    @Test
    fun handlesMarkdownLinkPunctuation() {
        val links = MessageUrlDetector.detect("Docs: [read this](https://example.com/docs).")

        assertEquals("https://example.com/docs", links.single().openUrl)
        assertEquals("https://example.com/docs", links.single().displayText)
    }
}
