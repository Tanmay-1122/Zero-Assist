/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.saf

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("WorkflowFolderFileHelper")
class WorkflowFolderFileHelperTest {
    @TempDir
    lateinit var tempDir: File

    private val helper = WorkflowFolderFileHelper()

    @Test
    fun `writes reads and lists text files`() {
        val writeJson =
            helper.write(
                root = tempDir,
                path = "runs/hello.txt",
                content = "hello workflow",
                isBase64 = false,
                mkdir = false,
            )

        assertEquals(14, JSONObject(writeJson).getLong("bytes_written"))

        val readJson = JSONObject(helper.read(tempDir, "runs/hello.txt"))
        assertEquals("text", readJson.getString("type"))
        assertEquals("hello workflow", readJson.getString("content"))

        val entries = JSONArray(helper.list(tempDir, "runs"))
        assertEquals(1, entries.length())
        assertEquals("hello.txt", entries.getJSONObject(0).getString("name"))
        assertEquals("file", entries.getJSONObject(0).getString("type"))
    }

    @Test
    fun `creates directories`() {
        val result = JSONObject(helper.write(tempDir, "plans/phase-01", null, false, mkdir = true))

        assertEquals("plans/phase-01", result.getString("path"))
        assertTrue(File(tempDir, "plans/phase-01").isDirectory)
    }

    @Test
    fun `rejects path traversal`() {
        val result = JSONObject(helper.write(tempDir, "../escape.txt", "bad", false, mkdir = false))

        assertTrue(result.getString("error").contains("escapes workflow folder"))
    }

    @Test
    fun `rejects invalid pdf writes`() {
        val result = JSONObject(helper.write(tempDir, "report.pdf", "not a pdf", false, mkdir = false))

        assertTrue(result.getString("error").contains("invalid PDF"))
    }
}
