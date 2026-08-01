/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import com.zeroclaw.android.model.BackgroundProcessEntry
import com.zeroclaw.android.model.ProcessType
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Terminal render keys")
class TerminalRenderKeysTest {
    @Test
    fun `terminal block keys remain unique when persisted ids collide`() {
        val block =
            TerminalBlock.Response(
                id = 0,
                timestamp = 123,
                content = "hello",
            )

        assertNotEquals(
            terminalBlockRenderKey(block, 0),
            terminalBlockRenderKey(block, 1),
        )
    }

    @Test
    fun `background process keys remain unique when process ids collide`() {
        val process =
            BackgroundProcessEntry(
                id = "b8250413-4dbb-4e62-a9f5-aedd32d41786",
                type = ProcessType.TOOL_EXEC,
                description = "Processing request",
                timestamp = 123,
            )

        assertNotEquals(
            backgroundProcessRenderKey(process, 0),
            backgroundProcessRenderKey(process, 1),
        )
    }
}
