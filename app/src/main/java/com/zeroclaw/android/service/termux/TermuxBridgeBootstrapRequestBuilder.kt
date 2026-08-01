/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

data class TermuxBridgeBootstrapConfig(
    val port: Int = TermuxRuntimeContract.DEFAULT_BRIDGE_PORT,
    val token: String,
    val host: String = TermuxRuntimeContract.DEFAULT_BRIDGE_HOST,
    val pythonPath: String = TermuxBridgeBootstrapRequestBuilder.DEFAULT_PYTHON_PATH,
    val scriptPath: String = TermuxBridgeBootstrapRequestBuilder.DEFAULT_BRIDGE_SCRIPT_PATH,
    val workingDirectory: String = TermuxBridgeBootstrapRequestBuilder.DEFAULT_WORKING_DIRECTORY,
)

class TermuxBridgeBootstrapRequestBuilder {
    fun build(config: TermuxBridgeBootstrapConfig): TermuxCommandIntentRequest {
        require(config.port in 1..65535) {
            "Termux bridge port must be between 1 and 65535."
        }
        require(config.token.isNotBlank()) {
            "Termux bridge token is required."
        }
        require(config.pythonPath.isNotBlank()) {
            "Termux Python path is required."
        }
        require(config.scriptPath.isNotBlank()) {
            "Termux bridge script path is required."
        }
        require(config.workingDirectory.isNotBlank()) {
            "Termux bridge working directory is required."
        }

        return TermuxCommandIntentRequest(
            commandPath = config.pythonPath,
            arguments =
                listOf(
                    config.scriptPath,
                    "--host",
                    config.host,
                    "--port",
                    config.port.toString(),
                    "--token",
                    config.token,
                ),
            workingDirectory = config.workingDirectory,
            background = true,
            commandLabel = "Zero-Assist Termux bridge",
        )
    }

    companion object {
        const val DEFAULT_PYTHON_PATH = "/data/data/com.termux/files/usr/bin/python3"
        const val DEFAULT_BRIDGE_SCRIPT_PATH = "/data/data/com.termux/files/home/.zero-assist/termux_bridge.py"
        const val DEFAULT_WORKING_DIRECTORY = "/data/data/com.termux/files/home"
    }
}
