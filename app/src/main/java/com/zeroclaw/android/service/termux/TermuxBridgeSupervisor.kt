/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import android.content.Context
import java.util.Base64

interface TermuxBridgeScriptSource {
    fun readScript(): String
}

class AndroidAssetTermuxBridgeScriptSource(
    private val context: Context,
    private val assetPath: String = DEFAULT_ASSET_PATH,
) : TermuxBridgeScriptSource {
    override fun readScript(): String =
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText()
        }

    companion object {
        const val DEFAULT_ASSET_PATH = "termux/termux_bridge.py"
    }
}

data class TermuxBridgeStartConfig(
    val token: String,
    val scriptContent: String,
    val port: Int = TermuxRuntimeContract.DEFAULT_BRIDGE_PORT,
    val host: String = TermuxRuntimeContract.DEFAULT_BRIDGE_HOST,
    val shellPath: String = TermuxBridgeStartRequestBuilder.DEFAULT_SHELL_PATH,
    val pythonPath: String = TermuxBridgeBootstrapRequestBuilder.DEFAULT_PYTHON_PATH,
    val scriptPath: String = TermuxBridgeBootstrapRequestBuilder.DEFAULT_BRIDGE_SCRIPT_PATH,
    val workingDirectory: String = TermuxBridgeBootstrapRequestBuilder.DEFAULT_WORKING_DIRECTORY,
)

class TermuxBridgeStartRequestBuilder {
    fun build(config: TermuxBridgeStartConfig): TermuxCommandIntentRequest {
        require(config.port in 1..65535) {
            "Termux bridge port must be between 1 and 65535."
        }
        require(config.token.isNotBlank()) {
            "Termux bridge token is required."
        }
        require(config.scriptContent.isNotBlank()) {
            "Termux bridge script content is required."
        }
        require(config.shellPath.isNotBlank()) {
            "Termux shell path is required."
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
            commandPath = config.shellPath,
            arguments = listOf("-lc", installAndStartScript(config)),
            workingDirectory = config.workingDirectory,
            background = true,
            commandLabel = "Zero-Assist Termux bridge",
        )
    }

    private fun installAndStartScript(config: TermuxBridgeStartConfig): String {
        val bridgePayload =
            Base64.getEncoder().encodeToString(config.scriptContent.toByteArray(Charsets.UTF_8))
        val scriptDirectory = config.scriptPath.substringBeforeLast('/', config.workingDirectory)
        val payloadPath = "${config.scriptPath}.b64"
        val termuxConfigDirectory = "${config.workingDirectory}/.termux"
        val termuxPropertiesPath = "$termuxConfigDirectory/termux.properties"
        return """
            set -u
            export PATH='/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/system/bin:/system/xbin'
            mkdir -p ${shellQuote(config.workingDirectory)} ${shellQuote(termuxConfigDirectory)} ${shellQuote(scriptDirectory)} ${shellQuote("${config.workingDirectory}/.zero-assist/workspace")}
            touch ${shellQuote(termuxPropertiesPath)}
            if command -v sed >/dev/null 2>&1; then
              sed -i '/^allow-external-apps[[:space:]]*=/d' ${shellQuote(termuxPropertiesPath)} || true
            fi
            printf 'allow-external-apps = true\n' >> ${shellQuote(termuxPropertiesPath)}
            if command -v termux-reload-settings >/dev/null 2>&1; then
              termux-reload-settings || true
            fi
            if [ ! -x ${shellQuote(config.pythonPath)} ]; then
              if command -v pkg >/dev/null 2>&1; then
                export DEBIAN_FRONTEND=noninteractive
                pkg update -y || true
                pkg install -y python || true
              fi
            fi
            if [ ! -x ${shellQuote(config.pythonPath)} ]; then
              echo 'Zero-Assist Termux setup could not find python3 after automatic recovery.' >&2
              exit 127
            fi
            cat > ${shellQuote(payloadPath)} <<'ZERO_ASSIST_TERMUX_BRIDGE_B64'
            $bridgePayload
            ZERO_ASSIST_TERMUX_BRIDGE_B64
            ${shellQuote(config.pythonPath)} - ${shellQuote(config.scriptPath)} ${shellQuote(payloadPath)} <<'ZERO_ASSIST_TERMUX_BRIDGE_INSTALL'
            import base64
            import pathlib
            import sys
            script_path = pathlib.Path(sys.argv[1])
            payload_path = pathlib.Path(sys.argv[2])
            script_path.write_bytes(base64.b64decode(payload_path.read_text().encode("ascii")))
            script_path.chmod(0o700)
            ZERO_ASSIST_TERMUX_BRIDGE_INSTALL
            for zero_assist_cmdline in /proc/[0-9]*/cmdline; do
              zero_assist_pid="${'$'}{zero_assist_cmdline%/cmdline}"
              zero_assist_pid="${'$'}{zero_assist_pid##*/}"
              [ "${'$'}zero_assist_pid" = "${'$'}${'$'}" ] && continue
              if tr '\000' ' ' < "${'$'}zero_assist_cmdline" 2>/dev/null | grep -F ${shellQuote(config.scriptPath)} >/dev/null 2>&1; then
                kill "${'$'}zero_assist_pid" 2>/dev/null || true
              fi
            done
            sleep 0.2
            exec ${shellQuote(config.pythonPath)} ${shellQuote(config.scriptPath)} --host ${shellQuote(config.host)} --port ${config.port} --token ${shellQuote(config.token)}
            """.trimIndent()
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    companion object {
        const val DEFAULT_SHELL_PATH = "/data/data/com.termux/files/usr/bin/sh"
    }
}

class TermuxBridgeSupervisor(
    private val launcher: TermuxBootstrapLauncher,
    private val scriptSource: TermuxBridgeScriptSource,
    private val tokenProvider: () -> String,
    private val requestBuilder: TermuxBridgeStartRequestBuilder = TermuxBridgeStartRequestBuilder(),
    private val intentBoundary: TermuxIntentBoundary = TermuxIntentBoundary(),
) {
    fun ensureStarted(): TermuxBootstrapLaunchResult {
        val request =
            requestBuilder.build(
                TermuxBridgeStartConfig(
                    token = tokenProvider(),
                    scriptContent = scriptSource.readScript(),
                ),
            )
        return launcher.launchRunCommandIntent(intentBoundary.runCommandIntent(request))
    }
}
