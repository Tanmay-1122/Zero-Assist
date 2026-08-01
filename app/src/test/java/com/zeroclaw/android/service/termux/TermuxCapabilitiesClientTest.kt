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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

@DisplayName("HttpTermuxCapabilitiesClient")
class TermuxCapabilitiesClientTest {
    @Test
    fun `fetches capabilities with raw Zero Assist token and parses commands`() =
        runTest {
            val interceptor =
                RecordingCapabilitiesInterceptor(
                    code = 200,
                    body =
                        """
                        {
                          "success": true,
                          "bridge": {"version": "0.1.0"},
                          "workspace": {
                            "root": "/data/data/com.termux/files/home/.zero-assist/workspace",
                            "termux_home": "/data/data/com.termux/files/home",
                            "termux_usr": "/data/data/com.termux/files/usr"
                          },
                          "commands": {
                            "python3": {
                              "available": true,
                              "path": "/data/data/com.termux/files/usr/bin/python3",
                              "version": "Python 3.13.13"
                            },
                            "git": {"available": false}
                          },
                          "python": {
                            "available": true,
                            "version": "3.13.13",
                            "executable": "/data/data/com.termux/files/usr/bin/python3"
                          },
                          "proot": {
                            "available": true,
                            "distros": ["debian"]
                          },
                          "limits": {
                            "approval_required": true,
                            "timeout_seconds": 30,
                            "max_timeout_seconds": 120,
                            "max_output_bytes": 65536,
                            "execution_mode": "argv_only_low_risk"
                          }
                        }
                        """.trimIndent(),
                )
            val client =
                HttpTermuxCapabilitiesClient(
                    httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
                    endpoints =
                        listOf(
                            TermuxBridgeEndpoint(
                                baseUrl = "http://127.0.0.1:8787",
                                token = "bridge-token",
                                tokenHeaderName = TermuxRuntimeContract.BRIDGE_TOKEN_HEADER,
                                useBearerPrefix = false,
                            ),
                        ),
                )

            val result = client.fetchCapabilities()

            assertTrue(result is TermuxCapabilitiesResult.Success)
            val snapshot = (result as TermuxCapabilitiesResult.Success).snapshot
            assertEquals("http://127.0.0.1:8787/capabilities", snapshot.endpoint)
            assertEquals("0.1.0", snapshot.bridgeVersion)
            assertEquals("3.13.13", snapshot.pythonVersion)
            assertEquals(true, snapshot.proot.available)
            assertEquals(listOf("debian"), snapshot.proot.distros)
            assertEquals("argv_only_low_risk", snapshot.limits.executionMode)
            assertEquals(true, snapshot.limits.approvalRequired)
            assertEquals(2, snapshot.commands.size)
            assertEquals("bridge-token", interceptor.zeroAssistTokenHeader)
            assertEquals("GET", interceptor.requestMethod)
        }

    @Test
    fun `tries fallback endpoint after connection failure`() =
        runTest {
            val client =
                HttpTermuxCapabilitiesClient(
                    httpClient = OkHttpClient.Builder().addInterceptor(FailFirstCapabilitiesInterceptor()).build(),
                    endpoints =
                        listOf(
                            TermuxBridgeEndpoint("http://127.0.0.1:8787"),
                            TermuxBridgeEndpoint("http://localhost:8787"),
                        ),
                )

            val result = client.fetchCapabilities()

            assertTrue(result is TermuxCapabilitiesResult.Success)
            assertEquals(
                "http://localhost:8787/capabilities",
                (result as TermuxCapabilitiesResult.Success).snapshot.endpoint,
            )
        }

    @Test
    fun `returns authentication failure for unauthorized response`() =
        runTest {
            val client =
                HttpTermuxCapabilitiesClient(
                    httpClient =
                        OkHttpClient.Builder()
                            .addInterceptor(RecordingCapabilitiesInterceptor(code = 401, body = "{}"))
                            .build(),
                    endpoints = listOf(TermuxBridgeEndpoint("http://localhost:8787", token = "bad")),
                )

            val result = client.fetchCapabilities()

            assertTrue(result is TermuxCapabilitiesResult.Failure)
            assertTrue((result as TermuxCapabilitiesResult.Failure).reason.contains("rejected capability authentication"))
        }

    private class RecordingCapabilitiesInterceptor(
        private val code: Int,
        private val body: String,
    ) : Interceptor {
        lateinit var requestMethod: String
            private set
        var zeroAssistTokenHeader: String? = null
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requestMethod = request.method
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

    private class FailFirstCapabilitiesInterceptor : Interceptor {
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
                .body("""{"success":true}""".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
