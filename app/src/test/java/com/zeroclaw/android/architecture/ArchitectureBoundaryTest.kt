/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchitectureBoundaryTest {
    @Test
    fun productionUiAndViewModelsDoNotImportGeneratedFfiDirectly() {
        val violations = findImportsUnderProductionUiAndViewModels("import com.zeroclaw.ffi")
        assertTrue(
            violations.isEmpty(),
            "UI and ViewModel generated FFI imports must go behind a service or bridge first:\n" +
                violations.sorted().joinToString(separator = "\n"),
        )
    }

    @Test
    fun uiDoesNotImportRoomOrDatabaseDirectly() {
        val forbiddenImports =
            listOf(
                "import androidx.room",
                "import com.zeroclaw.android.data.local.",
            )

        val violations = forbiddenImports.flatMap(::findImportsUnderUi)

        assertTrue(
            violations.isEmpty(),
            "UI code must use ViewModels/repositories instead of direct Room/database imports:\n" +
                violations.sorted().joinToString(separator = "\n"),
        )
    }

    @Test
    fun applicationStartsEstopPollingDuringStartup() {
        val sourceRoot = findMainSourceRoot()
        val applicationSource =
            Files
                .readAllLines(sourceRoot.resolve("com/zeroclaw/android/ZeroClawApplication.kt"))
                .joinToString(separator = "\n")

        assertTrue(
            applicationSource.contains("estopRepository.startPolling()"),
            "ZeroClawApplication must start E-stop polling so native safety state stays fresh.",
        )
    }

    private fun findImportsUnderUi(importPrefix: String): Set<String> {
        val sourceRoot = findMainSourceRoot()
        val uiRoot = sourceRoot.resolve("com/zeroclaw/android/ui")
        return findImportsUnderRoots(sourceRoot, listOf(uiRoot), importPrefix)
    }

    private fun findImportsUnderProductionUiAndViewModels(importPrefix: String): Set<String> {
        val sourceRoot = findMainSourceRoot()
        val roots =
            listOf(
                sourceRoot.resolve("com/zeroclaw/android/ui"),
                sourceRoot.resolve("com/zeroclaw/android/viewmodel"),
            )
        return findImportsUnderRoots(sourceRoot, roots, importPrefix)
    }

    private fun findImportsUnderRoots(
        sourceRoot: Path,
        roots: List<Path>,
        importPrefix: String,
    ): Set<String> =
        roots
            .filter { root -> Files.exists(root) }
            .flatMap { root ->
                Files
                    .walk(root)
                    .use { paths ->
                        paths
                            .iterator()
                            .asSequence()
                            .filter { path -> path.isRegularFile() && path.extension == "kt" }
                            .flatMap { path ->
                                Files
                                    .readAllLines(path)
                                    .asSequence()
                                    .map { it.trim() }
                                    .filter { it.startsWith(importPrefix) }
                                    .map { importLine -> formatImport(sourceRoot, path, importLine) }
                            }.toList()
                    }
            }
            .toSet()

    private fun formatImport(
        sourceRoot: Path,
        path: Path,
        importLine: String,
    ): String {
        val relativePath =
            sourceRoot
                .relativize(path)
                .toString()
                .replace('\\', '/')
        return "$relativePath:$importLine"
    }

    private fun findMainSourceRoot(): Path {
        val cwd = Paths.get("").toAbsolutePath()
        val candidates =
            generateSequence(cwd) { it.parent }
                .flatMap { path ->
                    sequenceOf(
                        path.resolve("app/src/main/java"),
                        path.resolve("src/main/java"),
                    )
                }

        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("Could not locate Android main source root from $cwd")
    }
}
