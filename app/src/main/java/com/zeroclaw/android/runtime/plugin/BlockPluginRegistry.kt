/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.runtime.plugin

import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.runtime.BlockCapability
import com.zeroclaw.android.runtime.BlockContext
import com.zeroclaw.android.ui.renderer.CalloutBlockRenderer
import com.zeroclaw.android.ui.renderer.CodeBlockRenderer
import com.zeroclaw.android.ui.renderer.ContainerBlockRenderer
import com.zeroclaw.android.ui.renderer.ContentBlockRendererRegistry
import com.zeroclaw.android.ui.renderer.ImageBlockRenderer
import java.util.concurrent.ConcurrentHashMap

/**
 * Extensible plugin interface for registering new block renderers, capabilities, and hooks.
 */
interface BlockPlugin {
    val pluginId: String

    /**
     * Called when the plugin is registered to add renderers to the UI registry.
     */
    fun registerRenderers(registry: ContentBlockRendererRegistry)

    /**
     * Advertises capabilities supported by blocks handled by this plugin.
     */
    fun getCapabilities(block: ContentBlock): Set<BlockCapability>

    /**
     * Hook called when a block managed by this plugin is created.
     */
    fun onBlockCreated(block: ContentBlock, context: BlockContext) {}

    /**
     * Hook called when a block managed by this plugin is destroyed/removed.
     */
    fun onBlockDestroyed(blockId: String) {}
}

/**
 * Native plugin registering [ContentBlock.Image] rendering, capabilities, and lifecycle hooks.
 */
class ImageBlockPlugin : BlockPlugin {
    override val pluginId: String = "native_image_block_plugin"

    override fun registerRenderers(registry: ContentBlockRendererRegistry) {
        registry.register(ContentBlock.Image::class, ImageBlockRenderer)
    }

    override fun getCapabilities(block: ContentBlock): Set<BlockCapability> {
        return if (block is ContentBlock.Image) {
            setOf(
                BlockCapability.DOWNLOADABLE,
                BlockCapability.SHAREABLE,
                BlockCapability.STREAMABLE,
                BlockCapability.EXPANDABLE,
            )
        } else {
            emptySet()
        }
    }

    override fun onBlockCreated(block: ContentBlock, context: BlockContext) {
        if (block is ContentBlock.Image) {
            context.runtime?.stateStore?.setExpanded(block.blockId, false)
        }
    }
}

/**
 * Native plugin registering [ContentBlock.Code] rendering and capabilities.
 */
class CodeBlockPlugin : BlockPlugin {
    override val pluginId: String = "native_code_block_plugin"

    override fun registerRenderers(registry: ContentBlockRendererRegistry) {
        registry.register(ContentBlock.Code::class, CodeBlockRenderer)
    }

    override fun getCapabilities(block: ContentBlock): Set<BlockCapability> {
        return if (block is ContentBlock.Code) {
            setOf(
                BlockCapability.COPYABLE,
                BlockCapability.SELECTABLE,
                BlockCapability.EXPANDABLE,
                BlockCapability.EXECUTABLE,
            )
        } else {
            emptySet()
        }
    }
}

/**
 * Native plugin registering [ContentBlock.Callout] rendering and capabilities.
 */
class CalloutBlockPlugin : BlockPlugin {
    override val pluginId: String = "native_callout_block_plugin"

    override fun registerRenderers(registry: ContentBlockRendererRegistry) {
        registry.register(ContentBlock.Callout::class, CalloutBlockRenderer)
    }

    override fun getCapabilities(block: ContentBlock): Set<BlockCapability> {
        return if (block is ContentBlock.Callout) {
            setOf(
                BlockCapability.COPYABLE,
                BlockCapability.SELECTABLE,
            )
        } else {
            emptySet()
        }
    }
}

/**
 * Native plugin registering [ContentBlock.Container] rendering and capabilities.
 */
class ContainerBlockPlugin : BlockPlugin {
    override val pluginId: String = "native_container_block_plugin"

    override fun registerRenderers(registry: ContentBlockRendererRegistry) {
        registry.register(ContentBlock.Container::class, ContainerBlockRenderer)
    }

    override fun getCapabilities(block: ContentBlock): Set<BlockCapability> {
        return if (block is ContentBlock.Container) {
            setOf(
                BlockCapability.EXPANDABLE,
                BlockCapability.PERSISTENT,
            )
        } else {
            emptySet()
        }
    }
}

/**
 * Global registry managing runtime plugins for rich content blocks.
 */
object BlockPluginRegistry {
    private val plugins = ConcurrentHashMap<String, BlockPlugin>()

    init {
        registerPlugin(ImageBlockPlugin())
        registerPlugin(CodeBlockPlugin())
        registerPlugin(CalloutBlockPlugin())
        registerPlugin(ContainerBlockPlugin())
    }

    /**
     * Registers a new [BlockPlugin] and binds its renderers to [ContentBlockRendererRegistry].
     */
    fun registerPlugin(plugin: BlockPlugin) {
        plugins[plugin.pluginId] = plugin
        plugin.registerRenderers(ContentBlockRendererRegistry)
    }

    /**
     * Unregisters a plugin by ID.
     */
    fun unregisterPlugin(pluginId: String) {
        plugins.remove(pluginId)
    }

    /**
     * Resolves advertised capabilities for a block across all registered plugins.
     */
    fun getCapabilities(block: ContentBlock): Set<BlockCapability> {
        val capabilities = mutableSetOf<BlockCapability>()
        // Standard default capabilities based on block variant
        when (block) {
            is ContentBlock.Text, is ContentBlock.Markdown -> {
                capabilities.add(BlockCapability.SELECTABLE)
                capabilities.add(BlockCapability.COPYABLE)
                capabilities.add(BlockCapability.STREAMABLE)
            }
            is ContentBlock.Reasoning -> {
                capabilities.add(BlockCapability.EXPANDABLE)
                capabilities.add(BlockCapability.COPYABLE)
                capabilities.add(BlockCapability.STREAMABLE)
            }
            is ContentBlock.ToolCard -> {
                capabilities.add(BlockCapability.EXPANDABLE)
                capabilities.add(BlockCapability.EXECUTABLE)
                capabilities.add(BlockCapability.STREAMABLE)
            }
            is ContentBlock.File, is ContentBlock.Image -> {
                capabilities.add(BlockCapability.DOWNLOADABLE)
                capabilities.add(BlockCapability.SHAREABLE)
            }
            else -> {}
        }

        plugins.values.forEach { plugin ->
            capabilities.addAll(plugin.getCapabilities(block))
        }

        return capabilities
    }

    /**
     * Dispatches `onBlockCreated` hook across all registered plugins.
     */
    fun notifyBlockCreated(block: ContentBlock, context: BlockContext) {
        plugins.values.forEach { it.onBlockCreated(block, context) }
    }

    /**
     * Dispatches `onBlockDestroyed` hook across all registered plugins.
     */
    fun notifyBlockDestroyed(blockId: String) {
        plugins.values.forEach { it.onBlockDestroyed(blockId) }
    }
}
