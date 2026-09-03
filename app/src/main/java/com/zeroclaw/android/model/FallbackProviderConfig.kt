package com.zeroclaw.android.model

import org.json.JSONArray
import org.json.JSONObject

data class FallbackProviderConfig(
    val id: String,
    val apiKey: String = "",
    val dailyLimitUsd: Float? = null,
    val monthlyLimitUsd: Float? = null,
) {
    fun normalized(): FallbackProviderConfig {
        val normalizedId = id.trim()
        return copy(
            id = normalizedId,
            apiKey = apiKey.trim(),
            dailyLimitUsd = dailyLimitUsd?.takeIf { it >= 0f },
            monthlyLimitUsd = monthlyLimitUsd?.takeIf { it >= 0f },
        )
    }

    fun toJsonObject(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("api_key", apiKey)
            if (dailyLimitUsd != null) {
                put("daily_limit_usd", dailyLimitUsd.toDouble())
            }
            if (monthlyLimitUsd != null) {
                put("monthly_limit_usd", monthlyLimitUsd.toDouble())
            }
        }

    companion object {
        fun fromJson(json: String): List<FallbackProviderConfig> =
            runCatching {
                val array = JSONArray(json.ifBlank { "[]" })
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optString("id").trim()
                        if (id.isEmpty()) continue
                        add(
                            FallbackProviderConfig(
                                id = id,
                                apiKey = item.optString("api_key"),
                                dailyLimitUsd =
                                    item
                                        .optDouble("daily_limit_usd", Double.NaN)
                                        .takeIf { !it.isNaN() }
                                        ?.toFloat(),
                                monthlyLimitUsd =
                                    item
                                        .optDouble("monthly_limit_usd", Double.NaN)
                                        .takeIf { !it.isNaN() }
                                        ?.toFloat(),
                            ).normalized(),
                        )
                    }
                }
            }.getOrDefault(emptyList())

        fun toJson(configs: List<FallbackProviderConfig>): String =
            JSONArray().apply {
                configs
                    .map { it.normalized() }
                    .filter { it.id.isNotBlank() }
                    .forEach { put(it.toJsonObject()) }
            }.toString()

        fun toCsv(configs: List<FallbackProviderConfig>): String =
            configs
                .map { it.normalized().id }
                .filter { it.isNotBlank() }
                .joinToString(",")

        fun toApiKeysJson(configs: List<FallbackProviderConfig>): String =
            JSONObject().apply {
                configs
                    .map { it.normalized() }
                    .filter { it.id.isNotBlank() && it.apiKey.isNotBlank() }
                    .forEach { put(it.id, it.apiKey) }
            }.toString()

        fun fromCsv(csv: String): List<FallbackProviderConfig> =
            csv
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { FallbackProviderConfig(id = it) }
    }
}
