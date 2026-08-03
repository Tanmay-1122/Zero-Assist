/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolDisplayFormatterTest {

    @Test
    fun knownToolWithHint() {
        assertEquals("shell: ls -la", ToolDisplayFormatter.format("sandbox_execute", "ls -la"))
    }

    @Test
    fun knownToolEmptyHintReturnsPrefixOnly() {
        assertEquals("shell", ToolDisplayFormatter.format("sandbox_execute", ""))
    }

    @Test
    fun knownToolBlankHintReturnsPrefixOnly() {
        assertEquals("shell", ToolDisplayFormatter.format("sandbox_execute", "   "))
    }

    @Test
    fun unknownToolWithHint() {
        assertEquals("custom_tool: some action", ToolDisplayFormatter.format("custom_tool", "some action"))
    }

    @Test
    fun unknownToolEmptyHintReturnsName() {
        assertEquals("custom_tool", ToolDisplayFormatter.format("custom_tool", ""))
    }

    @Test
    fun credentialInHintIsRedacted() {
        val result = ToolDisplayFormatter.format("web_fetch", "https://api.example.com/sk-ant-abc123secret")
        assertFalse(result.contains("sk-ant-abc123secret"))
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun longHintIsTruncated() {
        val longHint = "a".repeat(100)
        val result = ToolDisplayFormatter.format("sandbox_execute", longHint)
        assertTrue(result.length <= "shell: ".length + 80)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun bearerTokenPreservesLabel() {
        val result = ToolDisplayFormatter.format("web_fetch", "Authorization: Bearer secrettoken123")
        assertTrue(result.contains("Bearer [REDACTED]"))
    }

    @Test
    fun urlQueryParamsStrippedFromDisplay() {
        val result = ToolDisplayFormatter.format("web_fetch", "https://example.com/page?token=secret&user=admin")
        assertFalse(result.contains("?token=secret"))
        assertFalse(result.contains("&user=admin"))
    }

    @Test
    fun curlUserFlagRedacted() {
        val result = ToolDisplayFormatter.format("sandbox_execute", "curl -u admin:password123 https://example.com")
        assertFalse(result.contains("admin:password123"))
        assertTrue(result.contains("[REDACTED_CREDENTIALS]"))
    }

    @Test
    fun authorizationBasicRedactedBearerPreserved() {
        val basic = ToolDisplayFormatter.format("http_request", "Authorization: Basic dXNlcjpwYXNz")
        assertFalse(basic.contains("dXNlcjpwYXNz"))
        assertTrue(basic.contains("[REDACTED]"))

        val bearer = ToolDisplayFormatter.format("http_request", "Authorization: Bearer mytoken")
        assertTrue(bearer.contains("Bearer [REDACTED]"))
    }

    @Test
    fun memoryStoreShowsKey() {
        assertEquals("remember: user/prefs", ToolDisplayFormatter.format("memory_store", "user/prefs"))
    }

    @Test
    fun termuxRunShowsCommand() {
        assertEquals("termux: pkg install git", ToolDisplayFormatter.format("termux_run", "pkg install git"))
    }

    @Test
    fun sharedFolderReadShowsPath() {
        assertEquals("files-read: /sdcard/docs", ToolDisplayFormatter.format("shared_folder_read", "/sdcard/docs"))
    }

    @Test
    fun workflowFolderWriteShowsPath() {
        assertEquals("wf-write: /data/pipeline.json", ToolDisplayFormatter.format("workflow_folder_write", "/data/pipeline.json"))
    }

    @Test
    fun sharedFolderListPrefix() {
        assertEquals("files-list: /sdcard", ToolDisplayFormatter.format("shared_folder_list", "/sdcard"))
    }

    @Test
    fun sharedFolderWritePrefix() {
        assertEquals("files-write: /sdcard/out.txt", ToolDisplayFormatter.format("shared_folder_write", "/sdcard/out.txt"))
    }

    @Test
    fun workflowFolderReadPrefix() {
        assertEquals("wf-read: /data/config", ToolDisplayFormatter.format("workflow_folder_read", "/data/config"))
    }

    @Test
    fun workflowFolderListPrefix() {
        assertEquals("wf-list: /data", ToolDisplayFormatter.format("workflow_folder_list", "/data"))
    }

    @Test
    fun memoryRecallPrefix() {
        assertEquals("recall: rust async", ToolDisplayFormatter.format("memory_recall", "rust async"))
    }

    @Test
    fun memoryForgetPrefix() {
        assertEquals("forget: old-key", ToolDisplayFormatter.format("memory_forget", "old-key"))
    }

    @Test
    fun sandboxManageProcessPrefix() {
        assertEquals("sandbox: kill", ToolDisplayFormatter.format("sandbox_manage_process", "kill"))
    }

    @Test
    fun webSearchPrefix() {
        assertEquals("search: who is tanmay bhat", ToolDisplayFormatter.format("web_search_tool", "who is tanmay bhat"))
    }

    @Test
    fun httpRequestPrefix() {
        assertEquals("http: https://api.example.com", ToolDisplayFormatter.format("http_request", "https://api.example.com"))
    }

    @Test
    fun termuxGetCapabilitiesStaticHint() {
        assertEquals("termux: Checking termux capabilities", ToolDisplayFormatter.format("termux_get_capabilities", "Checking termux capabilities"))
    }
}
