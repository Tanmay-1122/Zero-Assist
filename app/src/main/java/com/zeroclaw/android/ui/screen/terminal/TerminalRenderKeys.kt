/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import com.zeroclaw.android.model.BackgroundProcessEntry

internal fun terminalBlockRenderKey(
    block: TerminalBlock,
    index: Int,
): String = "${block.id}:${block.timestamp}:${block::class.simpleName}:$index"

internal fun backgroundProcessRenderKey(
    process: BackgroundProcessEntry,
    index: Int,
): String = "${process.id}:${process.timestamp}:$index"
