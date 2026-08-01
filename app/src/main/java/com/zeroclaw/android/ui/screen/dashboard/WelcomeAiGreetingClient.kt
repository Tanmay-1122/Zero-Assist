package com.zeroclaw.android.ui.screen.dashboard

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * One-shot OkHttp client for generating AI welcome greetings.
 * Uses the OpenAI-compatible chat/completions format.
 */
internal class WelcomeAiGreetingClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generateGreeting(name: String, hour: Int): String = suspendCoroutine { continuation ->
        val prompt = buildString {
            append("Generate a single short welcome greeting for a user named \"$name\". ")
            append("Current time: ${hour}:00. ")
            append("Rules: exactly one line, no quotes, no emoji, no punctuation at end, under 15 words. ")
            append("Be warm and varied — do not always start with \"Good morning/afternoon/evening\". ")
            append("Just output the greeting text, nothing else.")
        }

        val requestUrl = if (baseUrl.endsWith("/chat/completions")) baseUrl
        else if (baseUrl.endsWith("/")) "${baseUrl}chat/completions"
        else "$baseUrl/chat/completions"

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.9)
            put("max_tokens", 60)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(requestUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.w(TAG, "AI greeting request failed: ${e.message}")
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!response.isSuccessful) {
                        continuation.resumeWithException(IOException("API error ${response.code}"))
                        return
                    }
                    val text = response.body?.string() ?: run {
                        continuation.resumeWithException(IOException("Empty response"))
                        return
                    }
                    try {
                        val content = JSONObject(text)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()
                        continuation.resume(content)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    companion object {
        private const val TAG = "WelcomeAiGreeting"
    }
}
