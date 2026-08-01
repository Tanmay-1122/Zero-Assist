/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class PiperVoiceConfig(
    val phonemeType: String,
    val espeakVoice: String? = null,
    val phonemeIdMap: Map<String, List<Long>>,
    val sampleRateHz: Int,
    val noiseScale: Float,
    val lengthScale: Float,
    val noiseW: Float,
    val speakerId: Long?,
)

internal sealed interface PiperVoiceConfigParseResult {
    data class Success(val config: PiperVoiceConfig) : PiperVoiceConfigParseResult

    data class Failure(val message: String) : PiperVoiceConfigParseResult
}

internal class PiperVoiceConfigParser(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
        },
) {
    fun parse(rawConfig: String): PiperVoiceConfigParseResult {
        val root =
            try {
                json.parseToJsonElement(rawConfig).jsonObject
            } catch (e: SerializationException) {
                return PiperVoiceConfigParseResult.Failure("Piper voice config is not valid JSON.")
            } catch (e: IllegalArgumentException) {
                return PiperVoiceConfigParseResult.Failure("Piper voice config is not valid JSON.")
            }

        val phonemeIdMap =
            root["phoneme_id_map"]?.asPhonemeIdMap()
                ?: return PiperVoiceConfigParseResult.Failure(
                    "Piper voice config is missing phoneme_id_map.",
                )
        if (phonemeIdMap.isEmpty()) {
            return PiperVoiceConfigParseResult.Failure("Piper voice config has no phoneme ids.")
        }
        val inference = root["inference"]?.jsonObjectOrNull()

        return PiperVoiceConfigParseResult.Success(
            PiperVoiceConfig(
                phonemeType = root.stringValue("phoneme_type") ?: "espeak",
                espeakVoice = root["espeak"]?.jsonObjectOrNull()?.stringValue("voice"),
                phonemeIdMap = phonemeIdMap,
                sampleRateHz =
                    root["audio"]?.jsonObjectOrNull()?.intValue("sample_rate")
                        ?: DEFAULT_SAMPLE_RATE_HZ,
                noiseScale =
                    root.floatValue("noise_scale")
                        ?: inference?.floatValue("noise_scale")
                        ?: DEFAULT_NOISE_SCALE,
                lengthScale =
                    root.floatValue("length_scale")
                        ?: inference?.floatValue("length_scale")
                        ?: DEFAULT_LENGTH_SCALE,
                noiseW =
                    root.floatValue("noise_w")
                        ?: inference?.floatValue("noise_w")
                        ?: DEFAULT_NOISE_W,
                speakerId = root.defaultSpeakerId(),
            ),
        )
    }

    private fun JsonObject.defaultSpeakerId(): Long? {
        val explicitSpeakerId = longValue("speaker_id")
        if (explicitSpeakerId != null) return explicitSpeakerId

        val speakerMap = this["speaker_id_map"]?.jsonObjectOrNull() ?: return null
        return speakerMap.values.firstOrNull()?.jsonPrimitive?.longOrNull
    }

    private fun JsonObject.stringValue(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.trim()

    private fun JsonObject.intValue(name: String): Int? =
        this[name]?.jsonPrimitive?.intOrNull

    private fun JsonObject.longValue(name: String): Long? =
        this[name]?.jsonPrimitive?.longOrNull

    private fun JsonObject.floatValue(name: String): Float? =
        this[name]?.jsonPrimitive?.floatOrNull

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    private fun kotlinx.serialization.json.JsonElement.asPhonemeIdMap(): Map<String, List<Long>>? {
        val source = this as? JsonObject ?: return null
        return source.mapNotNull { (symbol, value) ->
            val ids =
                (value as? JsonArray)
                    ?.mapNotNull { entry -> entry.jsonPrimitive.longOrNull }
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
            symbol to ids
        }.toMap()
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE_HZ = 22_050
        private const val DEFAULT_NOISE_SCALE = 0.667f
        private const val DEFAULT_LENGTH_SCALE = 1.0f
        private const val DEFAULT_NOISE_W = 0.8f
    }
}
