/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.runtime

import androidx.compose.ui.graphics.Color
import com.zeroclaw.android.data.local.entity.BlockRuntimeStateEntity
import com.zeroclaw.android.model.content.AssistantEvent
import com.zeroclaw.android.model.content.BlockState
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.runtime.plugin.BlockPluginRegistry
import com.zeroclaw.android.service.BlockReconciler
import com.zeroclaw.android.ui.renderer.BlockInteraction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Capability flags for rich content blocks.
 * Renderers query capabilities to enable features dynamically without checking block types.
 */
enum class BlockCapability {
    SELECTABLE,
    COPYABLE,
    EXPANDABLE,
    SEARCHABLE,
    SHAREABLE,
    DOWNLOADABLE,
    REFRESHABLE,
    EXECUTABLE,
    STREAMABLE,
    PERSISTENT,
}

/**
 * Execution context injected into block renderers.
 * Exposes current block data, capabilities, theme, scope, runtime reference, and injectables.
 */
data class BlockContext(
    val block: ContentBlock,
    val runtime: BlockRuntime? = null,
    val capabilities: Set<BlockCapability> = emptySet(),
    val coroutineScope: CoroutineScope? = null,
    val textColor: Color = Color.Unspecified,
    val isStreaming: Boolean = false,
    val onInteraction: ((BlockInteraction) -> Unit)? = null,
    val injectables: Map<String, Any> = emptyMap(),
) {
    /**
     * Checks if this block supports a specific capability.
     */
    fun hasCapability(capability: BlockCapability): Boolean = capabilities.contains(capability)

    /**
     * Retrieves an injected service by key.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getInjectable(key: String): T? = injectables[key] as? T
}

/**
 * Lifecycle-aware resource manager tracking active jobs, streams, and file handles for blocks.
 * Automatically cleans up resources when a block is removed or the runtime is destroyed.
 */
class BlockResourceManager {
    private val blockJobs = ConcurrentHashMap<String, MutableList<Job>>()
    private val blockCloseables = ConcurrentHashMap<String, MutableList<Closeable>>()

    /**
     * Registers a coroutine [Job] associated with a specific block.
     */
    fun registerJob(blockId: String, job: Job) {
        blockJobs.computeIfAbsent(blockId) { mutableListOf() }.add(job)
    }

    /**
     * Registers a [Closeable] resource associated with a specific block.
     */
    fun registerCloseable(blockId: String, closeable: Closeable) {
        blockCloseables.computeIfAbsent(blockId) { mutableListOf() }.add(closeable)
    }

    /**
     * Cleans up all resources bound to a specific block ID.
     */
    fun releaseBlockResources(blockId: String) {
        blockJobs.remove(blockId)?.forEach { it.cancel() }
        blockCloseables.remove(blockId)?.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Cleans up all managed resources across all blocks.
     */
    fun releaseAll() {
        blockJobs.keys.toList().forEach { releaseBlockResources(it) }
        blockCloseables.keys.toList().forEach { releaseBlockResources(it) }
    }

    /**
     * Returns count of active registered jobs for a block.
     */
    fun activeJobCount(blockId: String): Int = blockJobs[blockId]?.count { it.isActive } ?: 0
}

/**
 * Internal runtime lifecycle event bus.
 * Coordinates UI state, plugins, and background services independently from transport AssistantEvents.
 */
sealed interface RuntimeEvent {
    data class BlockCreated(val block: ContentBlock) : RuntimeEvent
    data class BlockUpdated(val block: ContentBlock) : RuntimeEvent
    data class BlockRemoved(val blockId: String) : RuntimeEvent
    data class BlockStateChanged(val blockId: String, val newState: BlockState) : RuntimeEvent
    data class InteractionDispatched(val interaction: BlockInteraction) : RuntimeEvent
    object RuntimeDestroyed : RuntimeEvent
}

class BlockRuntimeEventBus {
    private val _events = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<RuntimeEvent> = _events.asSharedFlow()

    fun emit(event: RuntimeEvent) {
        _events.tryEmit(event)
    }
}

/**
 * Metadata record for UI state associated with a block.
 */
data class BlockRuntimeMetadata(
    val blockId: String,
    val isExpanded: Boolean = false,
    val scrollPositionY: Int = 0,
    val retryCount: Int = 0,
    val customMetadata: Map<String, String> = emptyMap(),
)

/**
 * In-memory state store for block runtime metadata with persistence hooks.
 */
class BlockRuntimeStateStore {
    private val store = ConcurrentHashMap<String, BlockRuntimeMetadata>()

    fun getMetadata(blockId: String): BlockRuntimeMetadata {
        return store.computeIfAbsent(blockId) { BlockRuntimeMetadata(blockId = it) }
    }

    fun updateMetadata(blockId: String, transform: (BlockRuntimeMetadata) -> BlockRuntimeMetadata) {
        val current = getMetadata(blockId)
        store[blockId] = transform(current)
    }

    fun setExpanded(blockId: String, expanded: Boolean) {
        updateMetadata(blockId) { it.copy(isExpanded = expanded) }
    }

    fun incrementRetry(blockId: String) {
        updateMetadata(blockId) { it.copy(retryCount = it.retryCount + 1) }
    }

    fun clear(blockId: String) {
        store.remove(blockId)
    }

    fun clearAll() {
        store.clear()
    }

    fun toEntity(blockId: String, messageId: String): BlockRuntimeStateEntity {
        val meta = getMetadata(blockId)
        return BlockRuntimeStateEntity(
            blockId = meta.blockId,
            messageId = messageId,
            isExpanded = meta.isExpanded,
            scrollPositionY = meta.scrollPositionY,
            retryCount = meta.retryCount,
        )
    }

    fun restoreFromEntity(entity: BlockRuntimeStateEntity) {
        store[entity.blockId] = BlockRuntimeMetadata(
            blockId = entity.blockId,
            isExpanded = entity.isExpanded,
            scrollPositionY = entity.scrollPositionY,
            retryCount = entity.retryCount,
        )
    }
}

/**
 * Central runtime managing lifecycle, state reconciliation, resource cleanup, plugin hooks,
 * and event bus dispatching for rich content blocks.
 */
class BlockRuntime(
    val conversationId: String,
    val messageId: String,
    val parentScope: CoroutineScope? = null,
) {
    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val reconciler = BlockReconciler()
    val resourceManager = BlockResourceManager()
    val eventBus = BlockRuntimeEventBus()
    val stateStore = BlockRuntimeStateStore()

    private val _blocksState = MutableStateFlow<List<ContentBlock>>(emptyList())
    val blocksState: StateFlow<List<ContentBlock>> = _blocksState.asStateFlow()

    /**
     * Initializes runtime with initial blocks (e.g. from Room DB).
     */
    fun initialize(blocks: List<ContentBlock>) {
        reconciler.initialize(blocks)
        _blocksState.value = blocks
        blocks.forEach { block ->
            val context = createContext(block)
            BlockPluginRegistry.notifyBlockCreated(block, context)
            eventBus.emit(RuntimeEvent.BlockCreated(block))
        }
    }

    /**
     * Processes an incoming transport [AssistantEvent].
     */
    fun processAssistantEvent(event: AssistantEvent): List<ContentBlock> {
        val updatedBlocks = reconciler.processEvent(event)
        _blocksState.value = updatedBlocks

        when (event) {
            is AssistantEvent.BlockStarted -> {
                val context = createContext(event.block)
                BlockPluginRegistry.notifyBlockCreated(event.block, context)
                eventBus.emit(RuntimeEvent.BlockCreated(event.block))
            }
            is AssistantEvent.BlockUpdated -> {
                eventBus.emit(RuntimeEvent.BlockUpdated(event.block))
            }
            is AssistantEvent.BlockFinished -> {
                val block = updatedBlocks.firstOrNull { it.blockId == event.blockId }
                if (block != null) {
                    eventBus.emit(RuntimeEvent.BlockStateChanged(event.blockId, block.state))
                }
            }
            is AssistantEvent.BlockError -> {
                val block = updatedBlocks.firstOrNull { it.blockId == event.blockId }
                if (block != null) {
                    eventBus.emit(RuntimeEvent.BlockStateChanged(event.blockId, block.state))
                }
            }
            else -> {}
        }

        return updatedBlocks
    }

    /**
     * Dispatches a user/UI interaction via the runtime.
     */
    fun dispatchInteraction(interaction: BlockInteraction) {
        eventBus.emit(RuntimeEvent.InteractionDispatched(interaction))
        when (interaction) {
            is BlockInteraction.Expand -> stateStore.setExpanded(interaction.blockId, true)
            is BlockInteraction.Collapse -> stateStore.setExpanded(interaction.blockId, false)
            is BlockInteraction.Retry -> stateStore.incrementRetry(interaction.blockId)
            else -> {}
        }
    }

    /**
     * Creates a [BlockContext] for rendering a specific block.
     */
    fun createContext(
        block: ContentBlock,
        isStreaming: Boolean = false,
        onInteraction: ((BlockInteraction) -> Unit)? = null,
    ): BlockContext {
        val capabilities = BlockPluginRegistry.getCapabilities(block)
        return BlockContext(
            block = block,
            runtime = this,
            capabilities = capabilities,
            coroutineScope = coroutineScope,
            isStreaming = isStreaming,
            onInteraction = onInteraction ?: { dispatchInteraction(it) },
        )
    }

    /**
     * Removes a block and releases its resources.
     */
    fun removeBlock(blockId: String) {
        resourceManager.releaseBlockResources(blockId)
        stateStore.clear(blockId)
        BlockPluginRegistry.notifyBlockDestroyed(blockId)
        eventBus.emit(RuntimeEvent.BlockRemoved(blockId))
    }

    /**
     * Destroys the runtime and releases all resources.
     */
    fun destroy() {
        resourceManager.releaseAll()
        stateStore.clearAll()
        eventBus.emit(RuntimeEvent.RuntimeDestroyed)
        coroutineScope.cancel()
    }
}
