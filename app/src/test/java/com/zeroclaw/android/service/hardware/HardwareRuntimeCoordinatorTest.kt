/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.hardware

import android.content.Context
import com.zeroclaw.android.model.HardwareCommand
import com.zeroclaw.android.model.HardwareDevice
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Hardware runtime coordinator")
class HardwareRuntimeCoordinatorTest {
    @Test
    fun `http transport posts dual-compatible envelope and parses firmware result`() =
        runTest {
            val firmware = RecordingFirmwareInterceptor(
                responseBody = """{"id":"cmd-42","ok":true,"result":{"pin":12,"state":"high"}}""",
            )
            val coordinator = HardwareRuntimeCoordinator(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient.Builder()
                    .addInterceptor(firmware)
                    .build(),
            )

            val result = coordinator.execute(
                device = httpDevice("http://hardware.local/hardware/command"),
                command = HardwareCommand(
                    commandName = "gpio_write",
                    params = JSONObject()
                        .put("pin", 12)
                        .put("value", 1),
                    id = "cmd-42",
                ),
            )

            assertTrue(result.ok)
            assertEquals("network_http", result.transportType)
            assertEquals("cmd-42", result.id)
            assertEquals("POST", firmware.requestMethod)
            assertEquals("http://hardware.local/hardware/command", firmware.requestUrl)
            assertEquals("application/json; charset=utf-8", firmware.requestContentType)

            val requestJson = JSONObject(firmware.requestBody)
            assertEquals("cmd-42", requestJson.getString("id"))
            assertEquals("gpio_write", requestJson.getString("cmd"))
            assertEquals(12, requestJson.getJSONObject("args").getInt("pin"))
            assertEquals(1, requestJson.getJSONObject("args").getInt("value"))
            assertEquals(12, requestJson.getJSONObject("params").getInt("pin"))
            assertEquals(1, requestJson.getJSONObject("params").getInt("value"))

            val responseData = JSONObject(result.dataJson ?: error("Expected firmware result payload"))
            assertEquals(12, responseData.getInt("pin"))
            assertEquals("high", responseData.getString("state"))
        }

    @Test
    fun `http transport blocks public cleartext endpoints before network call`() =
        runTest {
            val firmware = RecordingFirmwareInterceptor(responseBody = """{"ok":true}""")
            val coordinator = HardwareRuntimeCoordinator(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient.Builder()
                    .addInterceptor(firmware)
                    .build(),
            )

            val result = coordinator.execute(
                device = httpDevice("http://example.com/hardware/command"),
                command = HardwareCommand(commandName = "ping", id = "cmd-99"),
            )

            assertFalse(result.ok)
            assertEquals("network_http", result.transportType)
            assertTrue(result.error?.contains("blocked cleartext") == true)
            assertEquals(0, firmware.callCount)
        }

    private fun httpDevice(commandEndpoint: String): HardwareDevice =
        HardwareDevice(
            id = "device-1",
            workspaceId = "default",
            name = "Bench ESP32",
            type = "esp32",
            firmwareVersion = "test",
            connectionStatus = "connected",
            capabilities = "{}",
            configJson = JSONObject()
                .put("transportType", "network_http")
                .put("commandEndpoint", commandEndpoint)
                .toString(),
        )

    private class RecordingFirmwareInterceptor(
        private val responseBody: String,
    ) : Interceptor {
        lateinit var requestMethod: String
            private set
        lateinit var requestUrl: String
            private set
        lateinit var requestContentType: String
            private set
        lateinit var requestBody: String
            private set
        var callCount: Int = 0
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            callCount += 1
            val request = chain.request()
            requestMethod = request.method
            requestUrl = request.url.toString()
            requestContentType = request.body?.contentType().toString()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            requestBody = buffer.readUtf8()

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
