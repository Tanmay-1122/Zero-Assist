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
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

@DisplayName("HttpTermuxExecutionClient")
class TermuxExecutionClientTest {
    @Test
    fun `posts argv execution with raw Zero Assist token and parses output`() =
        runTest {
            val interceptor =
                RecordingExecutionInterceptor(
                    code = 200,
                    body =
                        """
                        {
                          "success": true,
                          "id": "zatx_123",
                          "status": "completed",
                          "argv": ["python3", "--version"],
                          "working_directory": "/data/data/com.termux/files/home/.zero-assist/workspace",
                          "exit_code": 0,
                          "stdout": "Python 3.13.13\n",
                          "stderr": "",
                          "duration_ms": 42,
                          "stdout_truncated": false,
                          "stderr_truncated": false
                        }
                        """.trimIndent(),
                )
            val client =
                HttpTermuxExecutionClient(
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

            val result =
                client.execute(
                    TermuxExecutionRequest(
                        argv = listOf("python3", "--version"),
                        timeoutSeconds = 15,
                    ),
                )

            assertTrue(result is TermuxExecutionResult.Success)
            val snapshot = (result as TermuxExecutionResult.Success).snapshot
            assertEquals(true, snapshot.success)
            assertEquals(listOf("python3", "--version"), snapshot.argv)
            assertEquals("Python 3.13.13\n", snapshot.stdout)
            assertEquals(0, snapshot.exitCode)
            assertEquals("bridge-token", interceptor.zeroAssistTokenHeader)
            assertEquals("POST", interceptor.requestMethod)
            assertEquals("python3", JSONObject(interceptor.requestBody).getJSONArray("argv").getString(0))
            assertEquals(15, JSONObject(interceptor.requestBody).getInt("timeout_seconds"))
        }

    @Test
    fun `tries fallback endpoint after connection failure`() =
        runTest {
            val client =
                HttpTermuxExecutionClient(
                    httpClient = OkHttpClient.Builder().addInterceptor(FailFirstExecutionInterceptor()).build(),
                    endpoints =
                        listOf(
                            TermuxBridgeEndpoint("http://127.0.0.1:8787"),
                            TermuxBridgeEndpoint("http://localhost:8787"),
                        ),
                )

            val result = client.execute(TermuxExecutionRequest(argv = listOf("pwd")))

            assertTrue(result is TermuxExecutionResult.Success)
            assertEquals("ok\n", (result as TermuxExecutionResult.Success).snapshot.stdout)
        }

    @Test
    fun `posts approved execution fingerprint when provided`() =
        runTest {
            val interceptor =
                RecordingExecutionInterceptor(
                    code = 200,
                    body =
                        """
                        {"success":true,"argv":["pkg","install","git"],"status":"completed","stdout":"ok\n","stderr":"","exit_code":0}
                        """.trimIndent(),
                )
            val client =
                HttpTermuxExecutionClient(
                    httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
                    endpoints = listOf(TermuxBridgeEndpoint("http://localhost:8787")),
                )

            client.execute(
                TermuxExecutionRequest(
                    argv = listOf("pkg", "install", "git"),
                    workingDirectory = "/data/data/com.termux/files/home/.zero-assist/workspace",
                    approval =
                        TermuxExecutionApproval(
                            fingerprint = "fingerprint",
                            risk = TermuxCommandRisk.HIGH,
                        ),
                ),
            )

            val body = JSONObject(interceptor.requestBody)
            val approval = body.getJSONObject("approval")
            assertEquals(true, approval.getBoolean("approved"))
            assertEquals("fingerprint", approval.getString("fingerprint"))
            assertEquals("HIGH", approval.getString("risk"))
        }

    @Test
    fun `returns bridge error message for rejected command`() =
        runTest {
            val client =
                HttpTermuxExecutionClient(
                    httpClient =
                        OkHttpClient.Builder()
                            .addInterceptor(
                                RecordingExecutionInterceptor(
                                    code = 400,
                                    body = """{"success":false,"error":{"message":"only bounded low-risk diagnostic commands are enabled"}}""",
                                ),
                            )
                            .build(),
                    endpoints = listOf(TermuxBridgeEndpoint("http://localhost:8787")),
                )

            val result = client.execute(TermuxExecutionRequest(argv = listOf("rm", "-rf", "/")))

            assertTrue(result is TermuxExecutionResult.Failure)
            assertTrue((result as TermuxExecutionResult.Failure).reason.contains("low-risk diagnostic commands"))
        }

    @Test
    fun `rejects blank argv before network call`() =
        runTest {
            val client =
                HttpTermuxExecutionClient(
                    httpClient = OkHttpClient(),
                    endpoints = listOf(TermuxBridgeEndpoint("http://localhost:8787")),
                )

            val result = client.execute(TermuxExecutionRequest(argv = listOf(" ")))

            assertTrue(result is TermuxExecutionResult.Failure)
            assertTrue((result as TermuxExecutionResult.Failure).reason.contains("non-empty argv"))
        }

    private class RecordingExecutionInterceptor(
        private val code: Int,
        private val body: String,
    ) : Interceptor {
        lateinit var requestMethod: String
            private set
        lateinit var requestBody: String
            private set
        var zeroAssistTokenHeader: String? = null
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requestMethod = request.method
            requestBody = request.body?.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }.orEmpty()
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

    private class FailFirstExecutionInterceptor : Interceptor {
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
                .body(
                    """
                    {"success":true,"argv":["pwd"],"status":"completed","stdout":"ok\n","stderr":"","exit_code":0}
                    """.trimIndent().toResponseBody("application/json".toMediaType()),
                )
                .build()
        }
    }
}
