package com.zeroclaw.android.ui.screen.terminal

import com.zeroclaw.android.model.RefreshCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Terminal refresh command routing")
class TerminalRefreshCommandTest {
    @Test
    fun `skill install emits skills refresh`() {
        assertEquals(
            RefreshCommand.Skills,
            TerminalViewModel.refreshCommandForExpression("skill_install(\"marketplace-skill\")"),
        )
    }

    @Test
    fun `skill remove emits skills refresh`() {
        assertEquals(
            RefreshCommand.Skills,
            TerminalViewModel.refreshCommandForExpression("skill_remove(\"marketplace-skill\")"),
        )
    }

    @Test
    fun `unrelated expression emits no refresh`() {
        assertNull(TerminalViewModel.refreshCommandForExpression("version()"))
    }
}
