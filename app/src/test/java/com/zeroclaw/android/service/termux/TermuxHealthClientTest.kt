/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

@DisplayName("HttpTermuxHealthClient")
class TermuxHealthClientTest {
    @Test
    fun `endpoint rejects non-loopback bridge base url`() {
        assertThrows(IllegalArgumentException::class.java) {
            TermuxBridgeEndpoint("http://192.168.1.50:8787")
        }
    }

    @Test
    fun `endpoint rejects unapproved token header name`() {
        assertThrows(IllegalArgumentException::class.java) {
            TermuxBridgeEndpoint(
                baseUrl = "http://127.0.0.1:8787",
                token = "secret-token",
                tokenHeaderName = "X-Injected-Token",
            )
        }
    }

    @Test
    fun `probes health endpoint with bearer token and parses ready details`() =
        runTest {
            val interceptor =
                RecordingHealthInterceptor(
                    code = 200,
                    body =
                        """
                        {
                          "ready": true,
                          "version": "0.2.1",
                          "workspace": "/data/data/com.termux/files/home/zero-assist",
                          "proot": {
                            "available": true,
                            "active_distro": "debian",
                            "distros": ["debian", "ubuntu"]
                          }
                        }
                        """.trimIndent(),
                )
            val client =
                HttpTermuxHealthClient(
                    httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
                    endpoints =
                        listOf(
                            TermuxBridgeEndpoint(
                                baseUrl = "http://127.0.0.1:8765/",
                                token = "health-token",
                            ),
                        ),
                )

            val snapshot = client.checkHealth()

            assertEquals(TermuxHealthStatus.READY, snapshot.status)
            assertEquals("Termux bridge is ready.", snapshot.reason)
            assertEquals("http://127.0.0.1:8765/health", snapshot.details.endpoint)
            assertEquals("0.2.1", snapshot.details.version)
            assertEquals("/data/data/com.termux/files/home/zero-assist", snapshot.details.workspace)
            assertEquals(true, snapshot.details.proot.available)
            assertEquals("debian", snapshot.details.proot.activeDistro)
            assertEquals(listOf("debian", "ubuntu"), snapshot.details.proot.distros)
            assertEquals("GET", interceptor.requestMethod)
            assertEquals("http://127.0.0.1:8765/health", interceptor.requestUrl)
            assertEquals("Bearer health-token", interceptor.authorizationHeader)
        }

    @Test
    fun `can send raw token with Zero Assist bridge token header`() =
        runTest {
            val interceptor =
                RecordingHealthInterceptor(
                    code = 200,
                    body = """{"status":"ready"}""",
                )
            val client =
                HttpTermuxHealthClient(
                    httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
                    endpoints =
                        listOf(
                            TermuxBridgeEndpoint(
                                baseUrl = "http://localhost:8765",
                                token = "raw-token",
                                tokenHeaderName = TermuxRuntimeContract.BRIDGE_TOKEN_HEADER,
                                useBearerPrefix = false,
                            ),
                        ),
                )

            val snapshot = client.checkHealth()

            assertEquals(TermuxHealthStatus.READY, snapshot.status)
            assertEquals("raw-token", interceptor.zeroAssistTokenHeader)
        }

    @Test
    fun `returns unavailable with bridge reason when health response is not ready`() =
        runTest {
            val client =
                HttpTermuxHealthClient(
                    httpClient =
                        OkHttpClient.Builder()
                            .addInterceptor(
                                RecordingHealthInterceptor(
                                    code = 200,
                                    body = """{"ready":false,"reason":"Bootstrap script has not started the bridge."}""",
                                ),
                            )
                            .build(),
                    endpoints = listOf(TermuxBridgeEndpoint("http://localhost:8765")),
                )

            val snapshot = client.checkHealth()

            assertEquals(TermuxHealthStatus.UNAVAILABLE, snapshot.status)
            assertEquals("Bootstrap script has not started the bridge.", snapshot.reason)
        }

    @Test
    fun `returns actionable auth failure on unauthorized health response`() =
        runTest {
            val client =
                HttpTermuxHealthClient(
                    httpClient =
                        OkHttpClient.Builder()
                            .addInterceptor(RecordingHealthInterceptor(code = 401, body = """{"error":"unauthorized"}"""))
                            .build(),
                    endpoints = listOf(TermuxBridgeEndpoint("http://localhost:8765", token = "bad")),
                )

            val snapshot = client.checkHealth()

            assertEquals(TermuxHealthStatus.UNAVAILABLE, snapshot.status)
            assertTrue(snapshot.reason.contains("rejected health probe authentication"))
            assertTrue(snapshot.reason.contains("401"))
        }

    @Test
    fun `returns actionable invalid json failure`() =
        runTest {
            val client =
                HttpTermuxHealthClient(
                    httpClient =
                        OkHttpClient.Builder()
                            .addInterceptor(RecordingHealthInterceptor(code = 200, body = "not-json"))
                            .build(),
                    endpoints = listOf(TermuxBridgeEndpoint("http://localhost:8765")),
                )

            val snapshot = client.checkHealth()

            assertEquals(TermuxHealthStatus.UNAVAILABLE, snapshot.status)
            assertTrue(snapshot.reason.contains("invalid JSON"))
        }

    @Test
    fun `tries later localhost endpoints after connection failure`() =
        runTest {
            val client =
                HttpTermuxHealthClient(
                    httpClient =
                        OkHttpClient.Builder()
                            .addInterceptor(FailFirstHealthInterceptor())
                            .build(),
                    endpoints =
                        listOf(
                            TermuxBridgeEndpoint("http://127.0.0.1:8765"),
                            TermuxBridgeEndpoint("http://localhost:8765"),
                        ),
                )

            val snapshot = client.checkHealth()

            assertEquals(TermuxHealthStatus.READY, snapshot.status)
            assertEquals("http://localhost:8765/health", snapshot.details.endpoint)
        }

    private class RecordingHealthInterceptor(
        private val code: Int,
        private val body: String,
    ) : Interceptor {
        lateinit var requestMethod: String
            private set
        lateinit var requestUrl: String
            private set
        var authorizationHeader: String? = null
            private set
        var zeroAssistTokenHeader: String? = null
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requestMethod = request.method
            requestUrl = request.url.toString()
            authorizationHeader = request.header("Authorization")
            zeroAssistTokenHeader = request.header(TermuxRuntimeContract.BRIDGE_TOKEN_HEADER)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private class FailFirstHealthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.url.host == "127.0.0.1") {
                throw IOException("connection refused")
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"status":"ready"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
