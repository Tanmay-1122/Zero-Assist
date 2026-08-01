/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.ToolSpec
import com.zeroclaw.ffi.FfiException
import com.zeroclaw.ffi.FfiToolSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-owned tool catalog contract used by services that need tool metadata
 * without depending on generated FFI bindings.
 */
interface ToolCatalogBridge {
    suspend fun listTools(): List<ToolSpec>
}

/**
 * App-owned tool catalog entries layered on top of the native daemon tool list.
 */
interface AppOwnedToolCatalog {
    suspend fun listTools(): List<ToolSpec>
}

object EmptyAppOwnedToolCatalog : AppOwnedToolCatalog {
    override suspend fun listTools(): List<ToolSpec> = emptyList()
}

class CompositeAppOwnedToolCatalog(
    private vararg val catalogs: AppOwnedToolCatalog,
) : AppOwnedToolCatalog {
    override suspend fun listTools(): List<ToolSpec> = catalogs.flatMap { it.listTools() }
}

internal object AppOwnedToolPolicy {
    private val allowedToolNames =
        setOf(
            "termux_get_capabilities",
            "termux_run",
            "sandbox_execute",
            "sandbox_manage_process",
            "device_control",
        )

    fun filterAllowed(tools: List<ToolSpec>): List<ToolSpec> =
        tools.filter { tool -> tool.name in allowedToolNames }
}

/**
 * Bridge between the Android UI layer and the Rust tools browsing FFI.
 *
 * Wraps the tools-related UniFFI-generated function in a coroutine-safe
 * suspend function dispatched to [Dispatchers.IO].
 *
 * @param ioDispatcher Dispatcher for blocking FFI calls. Defaults to [Dispatchers.IO].
 */
class ToolsBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val appOwnedToolCatalog: AppOwnedToolCatalog = EmptyAppOwnedToolCatalog,
) : ToolCatalogBridge {
    /**
     * Lists all available tools based on daemon config and installed skills.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @return List of all [ToolSpec] instances.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    override suspend fun listTools(): List<ToolSpec> {
        val nativeTools =
            withContext(ioDispatcher) {
                com.zeroclaw.ffi
                    .listTools()
                    .map { it.toModel() }
            }
        val appOwnedTools = AppOwnedToolPolicy.filterAllowed(appOwnedToolCatalog.listTools())
        val appOwnedToolsByName = appOwnedTools.associateBy { it.name }
        val mergedNativeTools =
            nativeTools.map { nativeTool ->
                appOwnedToolsByName[nativeTool.name] ?: nativeTool
            }
        val appOwnedOnlyTools =
            appOwnedTools.filterNot { appOwnedTool ->
                nativeTools.any { nativeTool -> nativeTool.name == appOwnedTool.name }
            }
        return mergedNativeTools + appOwnedOnlyTools
    }
}

/**
 * Converts an FFI tool spec record to the domain model.
 *
 * @receiver FFI-generated [FfiToolSpec] record from the native layer.
 * @return Domain [ToolSpec] model with identical field values.
 */
private fun FfiToolSpec.toModel(): ToolSpec =
    ToolSpec(
        name = name,
        description = description,
        source = source,
        parametersJson = parametersJson,
        isActive = isActive,
        inactiveReason = inactiveReason,
    )
