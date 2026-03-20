/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a single message chunk stored in the [PersistentEpochBuffer].
 *
 * Each message is assigned a unique [id] upon creation to ensure that
 * list-based diffing (e.g. in LazyColumn) works correctly even if multiple
 * messages have identical text and timestamps.
 *
 * @property epoch The FFI epoch timestamp during which this message was sent.
 * @property text The raw content of the message.
 * @property id Unique identifier for this message instance.
 */
@Serializable
data class BufferedMessage(
    val epoch: Long,
    val text: String,
    val id: String = UUID.randomUUID().toString()
)
