// Copyright 2026 ZeroClaw Community, MIT License

package com.zeroclaw.android.service

import com.zeroclaw.android.model.ToolSpec
import com.zeroclaw.ffi.FfiException
import com.zeroclaw.ffi.FfiToolSpec
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [ToolsBridge].
 *
 * Uses MockK to mock the static UniFFI-generated functions so that tests
 * run without loading the native library.
 */
@DisplayName("ToolsBridge")
@OptIn(ExperimentalCoroutinesApi::class)
class ToolsBridgeTest {
    private lateinit var bridge: ToolsBridge

    /** Sets up mocks and creates a [ToolsBridge] with an unconfined dispatcher. */
    @BeforeEach
    fun setUp() {
        mockkStatic("com.zeroclaw.ffi.Zeroclaw_androidKt")
        bridge = ToolsBridge(ioDispatcher = UnconfinedTestDispatcher())
    }

    /** Tears down all mocks after each test. */
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    @DisplayName("listTools converts FFI records to ToolSpec models")
    fun `listTools converts FFI records to ToolSpec models`() =
        runTest {
            val ffiTools =
                listOf(
                    makeFfiToolSpec(name = "shell", source = "built-in"),
                    makeFfiToolSpec(name = "custom_tool", source = "my-skill"),
                )
            every { com.zeroclaw.ffi.listTools() } returns ffiTools

            val result = bridge.listTools()

            assertEquals(2, result.size)
            assertEquals("shell", result[0].name)
            assertEquals("built-in", result[0].source)
            assertEquals("custom_tool", result[1].name)
            assertEquals("my-skill", result[1].source)
        }

    @Test
    @DisplayName("listTools returns empty list when no tools exist")
    fun `listTools returns empty list when no tools exist`() =
        runTest {
            every { com.zeroclaw.ffi.listTools() } returns emptyList()

            val result = bridge.listTools()

            assertTrue(result.isEmpty())
        }

    @Test
    @DisplayName("listTools propagates FfiException")
    fun `listTools propagates FfiException`() =
        runTest {
            every {
                com.zeroclaw.ffi.listTools()
            } throws FfiException.StateException("daemon not running")

            assertThrows<FfiException> {
                bridge.listTools()
            }
        }

    @Test
    @DisplayName("listTools preserves parametersJson field")
    fun `listTools preserves parametersJson field`() =
        runTest {
            val schema = """{"type":"object","properties":{"path":{"type":"string"}}}"""
            val ffiTools =
                listOf(
                    makeFfiToolSpec(name = "file_read", parametersJson = schema),
                )
            every { com.zeroclaw.ffi.listTools() } returns ffiTools

            val result = bridge.listTools()

            assertEquals(schema, result[0].parametersJson)
        }

    @Test
    @DisplayName("listTools maps isActive and inactiveReason fields")
    fun `listTools maps isActive and inactiveReason fields`() =
        runTest {
            val daemonPolicyReason =
                "Requires daemon-channel security policy; Android device commands use Termux approval"
            val ffiTools =
                listOf(
                    makeFfiToolSpec(
                        name = "memory_store",
                        isActive = true,
                        inactiveReason = "",
                    ),
                    makeFfiToolSpec(
                        name = "shell",
                        isActive = false,
                        inactiveReason = daemonPolicyReason,
                    ),
                )
            every { com.zeroclaw.ffi.listTools() } returns ffiTools

            val result = bridge.listTools()

            assertTrue(result[0].isActive)
            assertEquals("", result[0].inactiveReason)
            assertTrue(!result[1].isActive)
            assertEquals(daemonPolicyReason, result[1].inactiveReason)
        }

    @Test
    @DisplayName("listTools prefers app-owned metadata when tool names collide")
    fun `listTools prefers app-owned metadata when tool names collide`() =
        runTest {
            every { com.zeroclaw.ffi.listTools() } returns
                listOf(
                    makeFfiToolSpec(
                        name = "termux_run",
                        description = "Native termux metadata",
                        isActive = false,
                        inactiveReason = "Native worker probe has not refreshed yet.",
                    ),
                    makeFfiToolSpec(name = "memory_store"),
                )
            val appOwnedTool =
                ToolSpec(
                    name = "termux_run",
                    description = "Zero-Assist internal termux runtime",
                    source = "built-in",
                    parametersJson = """{"type":"object"}""",
                    isActive = true,
                    inactiveReason = "",
                )
            bridge =
                ToolsBridge(
                    ioDispatcher = UnconfinedTestDispatcher(),
                    appOwnedToolCatalog =
                        object : AppOwnedToolCatalog {
                            override suspend fun listTools(): List<ToolSpec> = listOf(appOwnedTool)
                        },
                )

            val result = bridge.listTools()

            assertEquals(2, result.size)
            assertEquals("termux_run", result[0].name)
            assertEquals("Zero-Assist internal termux runtime", result[0].description)
            assertTrue(result[0].isActive)
            assertEquals("memory_store", result[1].name)
        }

    @Test
    @DisplayName("listTools appends app-owned tools missing from native catalog")
    fun `listTools appends app-owned tools missing from native catalog`() =
        runTest {
            every { com.zeroclaw.ffi.listTools() } returns
                listOf(makeFfiToolSpec(name = "memory_store"))
            bridge =
                ToolsBridge(
                    ioDispatcher = UnconfinedTestDispatcher(),
                    appOwnedToolCatalog =
                        object : AppOwnedToolCatalog {
                            override suspend fun listTools(): List<ToolSpec> =
                                listOf(
                                    ToolSpec(
                                        name = "termux_run",
                                        description = "Termux local runtime command runner",
                                        source = "termux",
                                        parametersJson = """{"type":"object"}""",
                                        isActive = false,
                                        inactiveReason = "Termux runtime is not ready.",
                                    ),
                                )
                        },
                )

            val result = bridge.listTools()

            assertEquals(listOf("memory_store", "termux_run"), result.map { it.name })
            assertEquals("termux", result[1].source)
            assertEquals("Termux runtime is not ready.", result[1].inactiveReason)
        }

    @Test
    @DisplayName("listTools exposes only approved app-owned tool names")
    fun `listTools exposes only approved app-owned tool names`() =
        runTest {
            every { com.zeroclaw.ffi.listTools() } returns
                listOf(makeFfiToolSpec(name = "shell", source = "built-in"))
            bridge =
                ToolsBridge(
                    ioDispatcher = UnconfinedTestDispatcher(),
                    appOwnedToolCatalog =
                        object : AppOwnedToolCatalog {
                            override suspend fun listTools(): List<ToolSpec> =
                                listOf(
                                    ToolSpec(
                                        name = "termux_run",
                                        description = "Termux local runtime command runner",
                                        source = "termux",
                                        parametersJson = """{"type":"object"}""",
                                        isActive = true,
                                        inactiveReason = "",
                                    ),
                                    ToolSpec(
                                        name = "shell",
                                        description = "Unapproved app-owned shell override",
                                        source = "app-owned",
                                        parametersJson = """{"type":"object"}""",
                                        isActive = true,
                                        inactiveReason = "",
                                    ),
                                    ToolSpec(
                                        name = "unknown_admin",
                                        description = "Unapproved app-owned tool",
                                        source = "app-owned",
                                        parametersJson = """{"type":"object"}""",
                                        isActive = true,
                                        inactiveReason = "",
                                    ),
                                )
                        },
                )

            val result = bridge.listTools()

            assertEquals(listOf("shell", "termux_run"), result.map { it.name })
            assertEquals("built-in", result[0].source)
            assertEquals("termux", result[1].source)
        }

    /** Helper to construct an [FfiToolSpec] with sensible defaults. */
    companion object {
        @Suppress("LongParameterList")
        private fun makeFfiToolSpec(
            name: String = "test-tool",
            description: String = "A test tool",
            source: String = "built-in",
            parametersJson: String = "{}",
            isActive: Boolean = true,
            inactiveReason: String = "",
        ): FfiToolSpec =
            FfiToolSpec(
                name = name,
                description = description,
                source = source,
                parametersJson = parametersJson,
                isActive = isActive,
                inactiveReason = inactiveReason,
            )
    }
}
