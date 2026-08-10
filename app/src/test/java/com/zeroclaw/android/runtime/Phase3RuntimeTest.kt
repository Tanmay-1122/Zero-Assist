/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.runtime

import com.zeroclaw.android.model.content.AssistantEvent
import com.zeroclaw.android.model.content.BlockState
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.runtime.plugin.BlockPlugin
import com.zeroclaw.android.runtime.plugin.BlockPluginRegistry
import com.zeroclaw.android.ui.renderer.BlockInteraction
import com.zeroclaw.android.ui.renderer.ContentBlockRendererRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class Phase3RuntimeTest {

    @Test
    fun testBlockContextCapabilitiesAndInjectables() {
        val block = ContentBlock.Text(blockId = "b1", sequenceIndex = 0, text = "Hello")
        val context = BlockContext(
            block = block,
            capabilities = setOf(BlockCapability.COPYABLE, BlockCapability.SELECTABLE),
            injectables = mapOf("custom_service" to "service_instance"),
        )

        assertTrue(context.hasCapability(BlockCapability.COPYABLE))
        assertTrue(context.hasCapability(BlockCapability.SELECTABLE))
        assertFalse(context.hasCapability(BlockCapability.DOWNLOADABLE))
        assertEquals("service_instance", context.getInjectable<String>("custom_service"))
    }

    @Test
    fun testBlockResourceManagerCleanup() {
        val manager = BlockResourceManager()
        val job = Job()
        val closeable = TestCloseable()

        manager.registerJob("b1", job)
        manager.registerCloseable("b1", closeable)

        assertEquals(1, manager.activeJobCount("b1"))
        assertFalse(closeable.isClosed)

        manager.releaseBlockResources("b1")

        assertTrue(job.isCancelled)
        assertTrue(closeable.isClosed)
        assertEquals(0, manager.activeJobCount("b1"))
    }

    @Test
    fun testBlockRuntimeEventBusEmission() = runBlocking {
        val bus = BlockRuntimeEventBus()
        val received = CompletableDeferred<RuntimeEvent>()

        val job = Job()
        val scope = CoroutineScope(job)

        // Launch collector
        val collectJob = scope.launch {
            bus.events.collect { received.complete(it) }
        }

        bus.emit(RuntimeEvent.BlockRemoved("b99"))
        val event = received.await()
        assertTrue(event is RuntimeEvent.BlockRemoved)
        assertEquals("b99", (event as RuntimeEvent.BlockRemoved).blockId)

        collectJob.cancel()
        job.cancel()
    }

    @Test
    fun testBlockPluginRegistrationAndCapabilities() {
        val plugin = TestPlugin()
        BlockPluginRegistry.registerPlugin(plugin)

        val block = ContentBlock.Markdown(blockId = "b_test", sequenceIndex = 0, markdown = "Test")
        val capabilities = BlockPluginRegistry.getCapabilities(block)

        assertTrue(capabilities.contains(BlockCapability.REFRESHABLE))
    }

    @Test
    fun testBlockRuntimeStateStorePersistence() {
        val store = BlockRuntimeStateStore()
        store.setExpanded("b10", true)
        store.incrementRetry("b10")

        val meta = store.getMetadata("b10")
        assertTrue(meta.isExpanded)
        assertEquals(1, meta.retryCount)

        val entity = store.toEntity("b10", "msg_1")
        assertEquals("b10", entity.blockId)
        assertTrue(entity.isExpanded)

        val restoredStore = BlockRuntimeStateStore()
        restoredStore.restoreFromEntity(entity)
        val restoredMeta = restoredStore.getMetadata("b10")
        assertTrue(restoredMeta.isExpanded)
    }

    @Test
    fun testBlockRuntimeLifecycleAndStressReconciliation() {
        val runtime = BlockRuntime(conversationId = "conv_1", messageId = "msg_1")

        // Stress test: 1,000 blocks
        val initialBlocks = (0 until 1000).map { i ->
            ContentBlock.Markdown(
                blockId = "msg_1_b$i",
                sequenceIndex = i,
                markdown = "Block $i initial content",
                state = BlockState.Ready,
            )
        }

        runtime.initialize(initialBlocks)
        assertEquals(1000, runtime.blocksState.value.size)

        // Process rapid delta events
        (0 until 100).forEach { i ->
            runtime.processAssistantEvent(
                AssistantEvent.BlockDelta(
                    messageId = "msg_1",
                    conversationId = "conv_1",
                    blockId = "msg_1_b$i",
                    delta = " updated",
                )
            )
        }

        val updatedBlock0 = runtime.blocksState.value.first { it.blockId == "msg_1_b0" } as ContentBlock.Markdown
        assertEquals("Block 0 initial content updated", updatedBlock0.markdown)

        runtime.destroy()
    }

    private class TestCloseable : Closeable {
        var isClosed = false
        override fun close() {
            isClosed = true
        }
    }

    private class TestPlugin : BlockPlugin {
        override val pluginId: String = "test_plugin"
        override fun registerRenderers(registry: ContentBlockRendererRegistry) {}
        override fun getCapabilities(block: ContentBlock): Set<BlockCapability> {
            return setOf(BlockCapability.REFRESHABLE)
        }
    }
}
