package com.zeroclaw.android.ui.screen.terminal

import com.zeroclaw.android.model.RefreshCommand

internal object TerminalRefreshCommandResolver {
    fun resolve(expression: String): RefreshCommand? =
        when {
            expression.startsWith("cron_add(") ||
                expression.startsWith("cron_oneshot(") ||
                expression.startsWith("cron_remove(") ||
                expression.startsWith("cron_pause(") ||
                expression.startsWith("cron_resume(") -> RefreshCommand.Cron
            expression.startsWith("send(") ||
                expression.startsWith("send_vision(") -> RefreshCommand.Cost
            expression.startsWith("skill_install(") ||
                expression.startsWith("skill_remove(") -> RefreshCommand.Skills
            else -> null
        }
}
