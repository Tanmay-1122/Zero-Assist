package com.zeroclaw.android.service

/** Forward-compatible seam for the future Rhai runtime bridge. */
interface ScriptRuntimeBridge {
    suspend fun execute(request: ScriptExecutionRequest): ScriptExecutionResult
    suspend fun isReady(): Boolean = false
}

data class ScriptExecutionRequest(
    val script: String,
    val workingDirectory: String? = null,
    val requestedCapabilities: Set<String> = emptySet(),
)

data class ScriptExecutionResult(
    val output: String,
    val error: String? = null,
    val exitCode: Int = 0,
)
