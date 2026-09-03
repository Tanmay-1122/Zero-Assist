/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.CustomVoicePhonemizerType
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PHONEME_TYPE_TEXT = "text"
private const val PHONEME_TYPE_ESPEAK = "espeak"
private const val ESPEAK_PHONEMIZER_MISSING =
    "This Piper voice needs an offline eSpeak phonemizer or a local package lexicon before it can speak locally."
private const val ENGLISH_RULE_PHONEMIZER_MISSING =
    "This Piper English voice needs standard eSpeak phoneme ids before it can speak locally."

private val PIPER_PHONE_ALTERNATIVES =
    mapOf(
        "ə" to listOf("ə", "ʌ", "a", "ɛ"),
        "ɛ" to listOf("ɛ", "e", "æ"),
        "ɪ" to listOf("ɪ", "i", "e"),
        "æ" to listOf("æ", "a", "ɛ"),
        "ɑ" to listOf("ɑ", "a", "ɔ", "o"),
        "ɔ" to listOf("ɔ", "o", "ɑ", "a"),
        "ʌ" to listOf("ʌ", "ə", "a"),
        "ʊ" to listOf("ʊ", "u", "o"),
        "ɹ" to listOf("ɹ", "r"),
        "j" to listOf("j", "y"),
        "g" to listOf("\u0261"),
        "\u0261" to listOf("g"),
    )

private val PIPER_PHONE_COMPONENTS =
    mapOf(
        "ʃ" to listOf("s", "h"),
        "ʒ" to listOf("z", "h"),
        "θ" to listOf("t", "h"),
        "ð" to listOf("d"),
        "ŋ" to listOf("n", "g"),
    )

private fun appendResolvedPiperPhone(
    phone: String,
    config: PiperVoiceConfig,
    separatorIds: List<Long>,
    encoded: MutableList<Long>,
): Boolean {
    val directAlternatives = PIPER_PHONE_ALTERNATIVES[phone].orEmpty() + phone
    directAlternatives.forEach { alternative ->
        val ids = config.phonemeIdMap[alternative]
        if (ids != null) {
            encoded += ids
            encoded += separatorIds
            return true
        }
    }

    val components = PIPER_PHONE_COMPONENTS[phone] ?: return false
    components.forEach { component ->
        if (!appendResolvedPiperPhone(component, config, separatorIds, encoded)) {
            return false
        }
    }
    return true
}

private fun PiperVoiceConfig.hasResolvedPiperPhone(phone: String): Boolean {
    val directAlternatives = PIPER_PHONE_ALTERNATIVES[phone].orEmpty() + phone
    if (directAlternatives.any { alternative -> phonemeIdMap[alternative] != null }) {
        return true
    }
    return PIPER_PHONE_COMPONENTS[phone]?.all { component -> hasResolvedPiperPhone(component) } == true
}

internal sealed interface PiperPhonemeEncodeResult {
    data class Success(val inputIds: LongArray) : PiperPhonemeEncodeResult {
        override fun equals(other: Any?): Boolean =
            other is Success && inputIds.contentEquals(other.inputIds)

        override fun hashCode(): Int = inputIds.contentHashCode()
    }

    data class Failure(val message: String) : PiperPhonemeEncodeResult
}

internal sealed interface PiperPhonemizerReadiness {
    data object Ready : PiperPhonemizerReadiness

    data class Unavailable(val message: String) : PiperPhonemizerReadiness
}

internal interface PiperPhonemizer {
    fun readiness(
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemizerReadiness

    fun encode(
        text: String,
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemeEncodeResult
}

internal class LocalPiperPhonemizer(
    private val textPhonemizer: PiperPhonemizer = PiperTextPhonemeEncoder(),
    private val packageLexiconPhonemizer: PiperPhonemizer = PiperPackageLexiconPhonemizer(),
    private val englishRulePhonemizer: PiperPhonemizer = PiperEnglishRulePhonemizer(),
    private val espeakPhonemizer: PiperPhonemizer = NativePiperEspeakPhonemizer(),
) : PiperPhonemizer {
    fun readiness(config: PiperVoiceConfig): PiperPhonemizerReadiness =
        readiness(config, voicePackage = null)

    override fun readiness(
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemizerReadiness =
        when (config.phonemeType.normalizedPhonemeType()) {
            PHONEME_TYPE_TEXT -> textPhonemizer.readiness(config, voicePackage)
            PHONEME_TYPE_ESPEAK -> espeakReadiness(config, voicePackage)
            else ->
                PiperPhonemizerReadiness.Unavailable(
                    unsupportedPhonemeTypeMessage(config.phonemeType),
                )
        }

    fun encode(
        text: String,
        config: PiperVoiceConfig,
    ): PiperPhonemeEncodeResult =
        encode(
            text = text,
            config = config,
            voicePackage = null,
        )

    override fun encode(
        text: String,
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemeEncodeResult =
        when (config.phonemeType.normalizedPhonemeType()) {
            PHONEME_TYPE_TEXT -> textPhonemizer.encode(text, config, voicePackage)
            PHONEME_TYPE_ESPEAK -> encodeEspeak(text, config, voicePackage)
            else ->
                PiperPhonemeEncodeResult.Failure(
                    unsupportedPhonemeTypeMessage(config.phonemeType),
                )
        }

    private fun espeakReadiness(
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemizerReadiness {
        val lexiconReadiness = packageLexiconPhonemizer.readiness(config, voicePackage)
        if (lexiconReadiness is PiperPhonemizerReadiness.Ready) {
            return lexiconReadiness
        }

        val ruleReadiness = englishRulePhonemizer.readiness(config, voicePackage)
        if (ruleReadiness is PiperPhonemizerReadiness.Ready) {
            return ruleReadiness
        }

        val nativeReadiness = espeakPhonemizer.readiness(config, voicePackage)
        if (nativeReadiness is PiperPhonemizerReadiness.Ready) {
            return nativeReadiness
        }

        return if (voicePackage?.manifest?.phonemizer != null) {
            lexiconReadiness
        } else if (config.espeakVoice.isEnglishEspeakVoice()) {
            ruleReadiness
        } else {
            nativeReadiness
        }
    }

    private fun encodeEspeak(
        text: String,
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemeEncodeResult {
        var lexiconFailure: PiperPhonemeEncodeResult.Failure? = null
        when (val lexiconReadiness = packageLexiconPhonemizer.readiness(config, voicePackage)) {
            PiperPhonemizerReadiness.Ready -> {
                when (val lexiconResult = packageLexiconPhonemizer.encode(text, config, voicePackage)) {
                    is PiperPhonemeEncodeResult.Success -> return lexiconResult
                    is PiperPhonemeEncodeResult.Failure -> lexiconFailure = lexiconResult
                }
            }
            is PiperPhonemizerReadiness.Unavailable ->
                if (voicePackage?.manifest?.phonemizer != null) {
                    lexiconFailure = PiperPhonemeEncodeResult.Failure(lexiconReadiness.message)
                }
        }

        if (englishRulePhonemizer.readiness(config, voicePackage) is PiperPhonemizerReadiness.Ready) {
            return englishRulePhonemizer.encode(text, config, voicePackage)
        }

        val nativeResult = espeakPhonemizer.encode(text, config, voicePackage)
        return if (nativeResult is PiperPhonemeEncodeResult.Failure && lexiconFailure != null) {
            lexiconFailure
        } else {
            nativeResult
        }
    }
}

internal interface PiperEspeakPhonemizerBridge {
    fun readiness(config: PiperVoiceConfig): PiperPhonemizerReadiness

    fun phonemize(
        text: String,
        config: PiperVoiceConfig,
    ): PiperPhonemeEncodeResult
}

internal object MissingPiperEspeakPhonemizerBridge : PiperEspeakPhonemizerBridge {
    override fun readiness(config: PiperVoiceConfig): PiperPhonemizerReadiness =
        PiperPhonemizerReadiness.Unavailable(ESPEAK_PHONEMIZER_MISSING)

    override fun phonemize(
        text: String,
        config: PiperVoiceConfig,
    ): PiperPhonemeEncodeResult =
        PiperPhonemeEncodeResult.Failure(ESPEAK_PHONEMIZER_MISSING)
}

internal class NativePiperEspeakPhonemizer(
    private val bridge: PiperEspeakPhonemizerBridge = FfiPiperEspeakPhonemizerBridge(),
) : PiperPhonemizer {
    fun readiness(config: PiperVoiceConfig): PiperPhonemizerReadiness =
        readiness(config, voicePackage = null)

    override fun readiness(
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemizerReadiness {
        if (!config.phonemeType.equals(PHONEME_TYPE_ESPEAK, ignoreCase = true)) {
            return PiperPhonemizerReadiness.Unavailable(
                "Native eSpeak phonemizer only supports Piper phoneme_type = espeak.",
            )
        }
        if (config.espeakVoice.isNullOrBlank()) {
            return PiperPhonemizerReadiness.Unavailable(
                "Piper eSpeak voice config is missing its eSpeak voice.",
            )
        }
        return runCatching { bridge.readiness(config) }
            .getOrElse { error ->
                PiperPhonemizerReadiness.Unavailable(
                    "Piper eSpeak phonemizer is unavailable locally: ${error.readableMessage()}",
                )
            }
    }

    fun encode(
        text: String,
        config: PiperVoiceConfig,
    ): PiperPhonemeEncodeResult =
        encode(
            text = text,
            config = config,
            voicePackage = null,
        )

    override fun encode(
        text: String,
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemeEncodeResult {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return PiperPhonemeEncodeResult.Failure("Speech text is blank.")
        }

        when (val readiness = readiness(config, voicePackage)) {
            PiperPhonemizerReadiness.Ready -> Unit
            is PiperPhonemizerReadiness.Unavailable ->
                return PiperPhonemeEncodeResult.Failure(readiness.message)
        }

        val bridgeResult =
            runCatching { bridge.phonemize(normalizedText, config) }
                .getOrElse { error ->
                    PiperPhonemeEncodeResult.Failure(
                        "Piper eSpeak phonemizer failed locally: ${error.readableMessage()}",
                    )
                }
        return when (val result = bridgeResult) {
            is PiperPhonemeEncodeResult.Failure -> result
            is PiperPhonemeEncodeResult.Success ->
                if (result.inputIds.isEmpty()) {
                    PiperPhonemeEncodeResult.Failure(
                        "Piper eSpeak phonemizer returned no phoneme ids.",
                    )
                } else {
                    result
                }
        }
    }
}

internal class PiperPackageLexiconPhonemizer(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
        },
) : PiperPhonemizer {
    private val lexiconCache = ConcurrentHashMap<String, PiperLexiconLoadResult>()

    override fun readiness(
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemizerReadiness {
        if (!config.phonemeType.equals(PHONEME_TYPE_ESPEAK, ignoreCase = true)) {
            return PiperPhonemizerReadiness.Unavailable(
                "Package lexicon phonemizer only supports Piper phoneme_type = espeak.",
            )
        }
        val phonemizer = voicePackage?.manifest?.phonemizer
            ?: return PiperPhonemizerReadiness.Unavailable(ESPEAK_PHONEMIZER_MISSING)
        if (!phonemizer.type.trim()
                .equals(CustomVoicePhonemizerType.PIPER_LEXICON_V1, ignoreCase = true)
        ) {
            return PiperPhonemizerReadiness.Unavailable(
                "This Piper voice package declares an unsupported local phonemizer.",
            )
        }
        val lexiconFile =
            voicePackage.phonemizerFile
                ?: return PiperPhonemizerReadiness.Unavailable(
                    "This Piper voice package is missing its local phonemizer file.",
                )
        return when (val lexicon = loadLexicon(lexiconFile)) {
            is PiperLexiconLoadResult.Failure ->
                PiperPhonemizerReadiness.Unavailable(lexicon.message)
            is PiperLexiconLoadResult.Success ->
                if (lexicon.wordsByText.isEmpty()) {
                    PiperPhonemizerReadiness.Unavailable("Piper lexicon phonemizer has no words.")
                } else {
                    PiperPhonemizerReadiness.Ready
                }
        }
    }

    override fun encode(
        text: String,
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemeEncodeResult {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return PiperPhonemeEncodeResult.Failure("Speech text is blank.")
        }
        when (val readiness = readiness(config, voicePackage)) {
            PiperPhonemizerReadiness.Ready -> Unit
            is PiperPhonemizerReadiness.Unavailable ->
                return PiperPhonemeEncodeResult.Failure(readiness.message)
        }

        val lexiconFile =
            voicePackage?.phonemizerFile
                ?: return PiperPhonemeEncodeResult.Failure(
                    "This Piper voice package is missing its local phonemizer file.",
                )
        val lexicon =
            when (val loaded = loadLexicon(lexiconFile)) {
                is PiperLexiconLoadResult.Failure ->
                    return PiperPhonemeEncodeResult.Failure(loaded.message)
                is PiperLexiconLoadResult.Success -> loaded
            }
        val words =
            WORD_PATTERN
                .findAll(normalizedText.lowercase(Locale.US))
                .map { match -> match.value.trim('\'') }
                .filter { word -> word.isNotBlank() }
                .toList()
        if (words.isEmpty()) {
            return PiperPhonemeEncodeResult.Failure(
                "Speech text has no English words the local phonemizer can read.",
            )
        }

        val separatorIds =
            config.phonemeIdMap[PHONEME_SEPARATOR]
                ?: return PiperPhonemeEncodeResult.Failure(
                    "Piper voice config is missing separator phoneme id.",
                )
        val startIds =
            config.phonemeIdMap[PHONEME_START]
                ?: return PiperPhonemeEncodeResult.Failure(
                    "Piper voice config is missing start phoneme id.",
                )
        val endIds =
            config.phonemeIdMap[PHONEME_END]
                ?: return PiperPhonemeEncodeResult.Failure(
                    "Piper voice config is missing end phoneme id.",
                )

        val encoded = mutableListOf<Long>()
        encoded += startIds
        encoded += separatorIds
        for (word in words) {
            val phonemes =
                lexicon.wordsByText[word]
                    ?: return PiperPhonemeEncodeResult.Failure(
                        "Piper lexicon cannot encode '$word' locally.",
                    )
            for (phoneme in phonemes) {
                if (!appendResolvedPiperPhone(phoneme, config, separatorIds, encoded)) {
                    return PiperPhonemeEncodeResult.Failure(
                        "Piper voice config is missing phoneme id for '$phoneme'.",
                    )
                }
            }
        }
        encoded += endIds
        return PiperPhonemeEncodeResult.Success(encoded.toLongArray())
    }

    private fun loadLexicon(file: File): PiperLexiconLoadResult =
        lexiconCache.computeIfAbsent(file.absolutePath) {
            val root =
                try {
                    json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
                } catch (e: SerializationException) {
                    return@computeIfAbsent PiperLexiconLoadResult.Failure(
                        "Piper lexicon phonemizer file is not valid JSON.",
                    )
                } catch (e: IllegalArgumentException) {
                    return@computeIfAbsent PiperLexiconLoadResult.Failure(
                        "Piper lexicon phonemizer file is not valid JSON.",
                    )
                }
            val words =
                root["words"]?.jsonObjectOrNull()
                    ?: return@computeIfAbsent PiperLexiconLoadResult.Failure(
                        "Piper lexicon phonemizer file is missing words.",
                    )
            PiperLexiconLoadResult.Success(
                wordsByText =
                    words.mapNotNull { (word, value) ->
                        val phonemes =
                            (value as? JsonArray)
                                ?.mapNotNull { entry ->
                                    entry.jsonPrimitive.contentOrNull
                                        ?.trim()
                                        ?.takeIf { it.isNotBlank() }
                                }
                                ?.takeIf { it.isNotEmpty() }
                                ?: return@mapNotNull null
                        word.lowercase(Locale.US).trim() to phonemes
                    }.toMap(),
            )
        }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    private sealed interface PiperLexiconLoadResult {
        data class Success(val wordsByText: Map<String, List<String>>) : PiperLexiconLoadResult

        data class Failure(val message: String) : PiperLexiconLoadResult
    }

    private companion object {
        private const val PHONEME_SEPARATOR = "_"
        private const val PHONEME_START = "^"
        private const val PHONEME_END = "$"
        private val WORD_PATTERN = Regex("[a-z']+")
    }
}

internal class PiperEnglishRulePhonemizer : PiperPhonemizer {
    override fun readiness(
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemizerReadiness {
        if (!config.phonemeType.equals(PHONEME_TYPE_ESPEAK, ignoreCase = true)) {
            return PiperPhonemizerReadiness.Unavailable(
                "Rule-based English phonemizer only supports Piper phoneme_type = espeak.",
            )
        }
        if (!config.espeakVoice.isEnglishEspeakVoice()) {
            return PiperPhonemizerReadiness.Unavailable(
                "Rule-based Piper phonemizer only supports English eSpeak voices.",
            )
        }
        if (config.phonemeIdMap[PHONEME_SEPARATOR] == null) {
            return PiperPhonemizerReadiness.Unavailable(
                "Piper voice config is missing separator phoneme id.",
            )
        }
        if (config.phonemeIdMap[PHONEME_START] == null) {
            return PiperPhonemizerReadiness.Unavailable(
                "Piper voice config is missing start phoneme id.",
            )
        }
        if (config.phonemeIdMap[PHONEME_END] == null) {
            return PiperPhonemizerReadiness.Unavailable(
                "Piper voice config is missing end phoneme id.",
            )
        }
        val hasCoreConsonants =
            listOf("h", "l", "m", "n", "s", "t", "g").all { symbol ->
                config.hasPhone(symbol)
            }
        val hasReadableVowels =
            listOf("ə", "ɛ", "ɪ", "i", "æ", "ɑ", "ɔ", "o", "u", "ʌ", "a", "e").any { symbol ->
                config.hasPhone(symbol)
            }
        return if (hasCoreConsonants && hasReadableVowels) {
            PiperPhonemizerReadiness.Ready
        } else {
            PiperPhonemizerReadiness.Unavailable(ENGLISH_RULE_PHONEMIZER_MISSING)
        }
    }

    override fun encode(
        text: String,
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemeEncodeResult {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return PiperPhonemeEncodeResult.Failure("Speech text is blank.")
        }
        when (val readiness = readiness(config, voicePackage)) {
            PiperPhonemizerReadiness.Ready -> Unit
            is PiperPhonemizerReadiness.Unavailable ->
                return PiperPhonemeEncodeResult.Failure(readiness.message)
        }

        val words =
            WORD_PATTERN
                .findAll(normalizedText.lowercase(Locale.US))
                .map { match -> match.value.trim('\'') }
                .filter { word -> word.isNotBlank() }
                .toList()
        if (words.isEmpty()) {
            return PiperPhonemeEncodeResult.Failure(
                "Speech text has no English words the local phonemizer can read.",
            )
        }

        val separatorIds =
            config.phonemeIdMap[PHONEME_SEPARATOR]
                ?: return PiperPhonemeEncodeResult.Failure(
                    "Piper voice config is missing separator phoneme id.",
                )
        val startIds =
            config.phonemeIdMap[PHONEME_START]
                ?: return PiperPhonemeEncodeResult.Failure(
                    "Piper voice config is missing start phoneme id.",
                )
        val endIds =
            config.phonemeIdMap[PHONEME_END]
                ?: return PiperPhonemeEncodeResult.Failure(
                    "Piper voice config is missing end phoneme id.",
                )

        val encoded = mutableListOf<Long>()
        encoded += startIds
        encoded += separatorIds
        for (word in words) {
            val phones = pronounce(word)
            if (phones.isEmpty()) {
                return PiperPhonemeEncodeResult.Failure(
                    "Rule-based Piper phonemizer cannot encode '$word' locally.",
                )
            }
            for (phone in phones) {
                if (!appendPhone(phone, config, separatorIds, encoded)) {
                    return PiperPhonemeEncodeResult.Failure(
                        "Piper voice config is missing phoneme id for '$phone'.",
                    )
                }
            }
        }
        encoded += endIds
        return PiperPhonemeEncodeResult.Success(encoded.toLongArray())
    }

    private fun pronounce(word: String): List<String> {
        WORDS[word]?.let { return it }
        if (word.endsWith("'s") && word.length > 2) {
            val base = word.removeSuffix("'s")
            val basePhones = WORDS[base] ?: pronounceUnknown(base)
            return basePhones + "s"
        }
        return pronounceUnknown(word.replace("'", ""))
    }

    private fun pronounceUnknown(word: String): List<String> {
        val phones = mutableListOf<String>()
        var index = 0
        while (index < word.length) {
            val remaining = word.substring(index)
            val matched =
                DIGRAPH_RULES.firstOrNull { (letters, _) ->
                    remaining.startsWith(letters)
                }
            if (matched != null) {
                phones += matched.second
                index += matched.first.length
                continue
            }

            val letter = word[index]
            if (letter == 'e' && index == word.lastIndex && word.length > 2) {
                index += 1
                continue
            }
            val longVowel =
                if (index + 2 == word.lastIndex && word[word.lastIndex] == 'e') {
                    LONG_VOWELS[letter]
                } else {
                    null
                }
            if (longVowel != null) {
                phones += longVowel
                index += 1
                continue
            }
            val phone = LETTERS[letter]
            if (phone != null) {
                phones += phone
            }
            index += 1
        }
        return phones
    }

    private fun appendPhone(
        phone: String,
        config: PiperVoiceConfig,
        separatorIds: List<Long>,
        encoded: MutableList<Long>,
    ): Boolean = appendResolvedPiperPhone(phone, config, separatorIds, encoded)

    private fun PiperVoiceConfig.hasPhone(phone: String): Boolean = hasResolvedPiperPhone(phone)

    private companion object {
        private const val PHONEME_SEPARATOR = "_"
        private const val PHONEME_START = "^"
        private const val PHONEME_END = "$"
        private val WORD_PATTERN = Regex("[a-z]+(?:'[a-z]+)?")
        private val WORDS =
            mapOf(
                // Articles / determiners
                "a" to listOf("ə"),
                "an" to listOf("ə", "n"),
                "the" to listOf("ð", "ə"),
                "this" to listOf("ð", "ɪ", "s"),
                "that" to listOf("ð", "æ", "t"),
                "these" to listOf("ð", "i", "z"),
                "those" to listOf("ð", "o", "ʊ", "z"),
                "some" to listOf("s", "ʌ", "m"),
                "any" to listOf("ɛ", "n", "i"),
                "all" to listOf("ɔ", "l"),
                "each" to listOf("i", "t", "ʃ"),
                "every" to listOf("ɛ", "v", "ɹ", "i"),
                // Pronouns
                "i" to listOf("a", "ɪ"),
                "i'm" to listOf("a", "ɪ", "m"),
                "i've" to listOf("a", "ɪ", "v"),
                "i'll" to listOf("a", "ɪ", "l"),
                "i'd" to listOf("a", "ɪ", "d"),
                "me" to listOf("m", "i"),
                "my" to listOf("m", "a", "ɪ"),
                "we" to listOf("w", "i"),
                "we're" to listOf("w", "ɪ", "ɹ"),
                "we've" to listOf("w", "i", "v"),
                "our" to listOf("a", "ʊ", "ɹ"),
                "you" to listOf("j", "u"),
                "your" to listOf("j", "ɔ", "ɹ"),
                "you're" to listOf("j", "ɔ", "ɹ"),
                "you've" to listOf("j", "u", "v"),
                "he" to listOf("h", "i"),
                "she" to listOf("ʃ", "i"),
                "it" to listOf("ɪ", "t"),
                "it's" to listOf("ɪ", "t", "s"),
                "they" to listOf("ð", "e", "ɪ"),
                "them" to listOf("ð", "ɛ", "m"),
                "their" to listOf("ð", "ɛ", "ɹ"),
                "there" to listOf("ð", "ɛ", "ɹ"),
                "what" to listOf("w", "ʌ", "t"),
                "what's" to listOf("w", "ʌ", "t", "s"),
                "whats" to listOf("w", "ʌ", "t", "s"),
                "which" to listOf("w", "ɪ", "t", "ʃ"),
                "who" to listOf("h", "u"),
                "where" to listOf("w", "ɛ", "ɹ"),
                "when" to listOf("w", "ɛ", "n"),
                "how" to listOf("h", "a", "ʊ"),
                "why" to listOf("w", "a", "ɪ"),
                // Common verbs
                "am" to listOf("æ", "m"),
                "are" to listOf("ɑ", "ɹ"),
                "is" to listOf("ɪ", "z"),
                "was" to listOf("w", "ʌ", "z"),
                "were" to listOf("w", "ɹ"),
                "be" to listOf("b", "i"),
                "been" to listOf("b", "ɪ", "n"),
                "being" to listOf("b", "i", "ɪ", "ŋ"),
                "have" to listOf("h", "æ", "v"),
                "has" to listOf("h", "æ", "z"),
                "had" to listOf("h", "æ", "d"),
                "having" to listOf("h", "æ", "v", "ɪ", "ŋ"),
                "do" to listOf("d", "u"),
                "does" to listOf("d", "ʌ", "z"),
                "did" to listOf("d", "ɪ", "d"),
                "done" to listOf("d", "ʌ", "n"),
                "doing" to listOf("d", "u", "ɪ", "ŋ"),
                "will" to listOf("w", "ɪ", "l"),
                "would" to listOf("w", "ʊ", "d"),
                "can" to listOf("k", "æ", "n"),
                "could" to listOf("k", "ʊ", "d"),
                "should" to listOf("ʃ", "ʊ", "d"),
                "may" to listOf("m", "e", "ɪ"),
                "might" to listOf("m", "a", "ɪ", "t"),
                "must" to listOf("m", "ʌ", "s", "t"),
                "shall" to listOf("ʃ", "æ", "l"),
                "need" to listOf("n", "i", "d"),
                "needs" to listOf("n", "i", "d", "z"),
                "get" to listOf("g", "ɛ", "t"),
                "got" to listOf("g", "ɑ", "t"),
                "go" to listOf("g", "o", "ʊ"),
                "going" to listOf("g", "o", "ɪ", "ŋ"),
                "gone" to listOf("g", "ɔ", "n"),
                "make" to listOf("m", "e", "ɪ", "k"),
                "made" to listOf("m", "e", "ɪ", "d"),
                "take" to listOf("t", "e", "ɪ", "k"),
                "took" to listOf("t", "ʊ", "k"),
                "come" to listOf("k", "ʌ", "m"),
                "came" to listOf("k", "e", "ɪ", "m"),
                "know" to listOf("n", "o", "ʊ"),
                "known" to listOf("n", "o", "ʊ", "n"),
                "think" to listOf("θ", "ɪ", "ŋ", "k"),
                "thought" to listOf("θ", "ɔ", "t"),
                "see" to listOf("s", "i"),
                "seen" to listOf("s", "i", "n"),
                "look" to listOf("l", "ʊ", "k"),
                "say" to listOf("s", "e", "ɪ"),
                "said" to listOf("s", "ɛ", "d"),
                "tell" to listOf("t", "ɛ", "l"),
                "told" to listOf("t", "o", "ʊ", "l", "d"),
                "find" to listOf("f", "a", "ɪ", "n", "d"),
                "found" to listOf("f", "a", "ʊ", "n", "d"),
                "give" to listOf("g", "ɪ", "v"),
                "given" to listOf("g", "ɪ", "v", "ə", "n"),
                "use" to listOf("j", "u", "z"),
                "used" to listOf("j", "u", "z", "d"),
                "call" to listOf("k", "ɔ", "l"),
                "show" to listOf("ʃ", "o", "ʊ"),
                "work" to listOf("w", "ɹ", "k"),
                "help" to listOf("h", "ɛ", "l", "p"),
                "try" to listOf("t", "ɹ", "a", "ɪ"),
                "start" to listOf("s", "t", "ɑ", "ɹ", "t"),
                "stop" to listOf("s", "t", "ɑ", "p"),
                "open" to listOf("o", "ʊ", "p", "ə", "n"),
                "close" to listOf("k", "l", "o", "ʊ", "z"),
                "check" to listOf("t", "ʃ", "ɛ", "k"),
                "run" to listOf("ɹ", "ʌ", "n"),
                "set" to listOf("s", "ɛ", "t"),
                "add" to listOf("æ", "d"),
                "send" to listOf("s", "ɛ", "n", "d"),
                "create" to listOf("k", "ɹ", "i", "e", "ɪ", "t"),
                "delete" to listOf("d", "ɪ", "l", "i", "t"),
                "update" to listOf("ʌ", "p", "d", "e", "ɪ", "t"),
                "search" to listOf("s", "ɹ", "t", "ʃ"),
                "play" to listOf("p", "l", "e", "ɪ"),
                "pause" to listOf("p", "ɔ", "z"),
                "read" to listOf("ɹ", "ɛ", "d"),
                "write" to listOf("ɹ", "a", "ɪ", "t"),
                "save" to listOf("s", "e", "ɪ", "v"),
                "load" to listOf("l", "o", "ʊ", "d"),
                // Common adjectives
                "good" to listOf("g", "ʊ", "d"),
                "great" to listOf("g", "ɹ", "e", "ɪ", "t"),
                "new" to listOf("n", "j", "u"),
                "old" to listOf("o", "ʊ", "l", "d"),
                "big" to listOf("b", "ɪ", "g"),
                "small" to listOf("s", "m", "ɔ", "l"),
                "long" to listOf("l", "ɔ", "ŋ"),
                "short" to listOf("ʃ", "ɔ", "ɹ", "t"),
                "right" to listOf("ɹ", "a", "ɪ", "t"),
                "left" to listOf("l", "ɛ", "f", "t"),
                "first" to listOf("f", "ɹ", "s", "t"),
                "last" to listOf("l", "æ", "s", "t"),
                "next" to listOf("n", "ɛ", "k", "s", "t"),
                "sure" to listOf("ʃ", "ʊ", "ɹ"),
                "ok" to listOf("o", "ʊ", "k", "e", "ɪ"),
                "okay" to listOf("o", "ʊ", "k", "e", "ɪ"),
                "true" to listOf("t", "ɹ", "u"),
                "false" to listOf("f", "ɔ", "l", "s"),
                "available" to listOf("ə", "v", "e", "ɪ", "l", "ə", "b", "ə", "l"),
                "current" to listOf("k", "ɹ", "ɛ", "n", "t"),
                "local" to listOf("l", "o", "ʊ", "k", "ə", "l"),
                "online" to listOf("ɑ", "n", "l", "a", "ɪ", "n"),
                "offline" to listOf("ɔ", "f", "l", "a", "ɪ", "n"),
                "active" to listOf("æ", "k", "t", "ɪ", "v"),
                "complete" to listOf("k", "ə", "m", "p", "l", "i", "t"),
                "done" to listOf("d", "ʌ", "n"),
                "ready" to listOf("ɹ", "ɛ", "d", "i"),
                "busy" to listOf("b", "ɪ", "z", "i"),
                "free" to listOf("f", "ɹ", "i"),
                "easy" to listOf("i", "z", "i"),
                "hard" to listOf("h", "ɑ", "ɹ", "d"),
                "fast" to listOf("f", "æ", "s", "t"),
                "slow" to listOf("s", "l", "o", "ʊ"),
                // Prepositions / conjunctions
                "in" to listOf("ɪ", "n"),
                "on" to listOf("ɑ", "n"),
                "at" to listOf("æ", "t"),
                "by" to listOf("b", "a", "ɪ"),
                "for" to listOf("f", "ɔ", "ɹ"),
                "of" to listOf("ə", "v"),
                "to" to listOf("t", "u"),
                "from" to listOf("f", "ɹ", "ʌ", "m"),
                "with" to listOf("w", "ɪ", "ð"),
                "about" to listOf("ə", "b", "a", "ʊ", "t"),
                "after" to listOf("æ", "f", "t", "ɹ"),
                "before" to listOf("b", "ɪ", "f", "ɔ", "ɹ"),
                "between" to listOf("b", "ɪ", "t", "w", "i", "n"),
                "into" to listOf("ɪ", "n", "t", "u"),
                "through" to listOf("θ", "ɹ", "u"),
                "without" to listOf("w", "ɪ", "ð", "a", "ʊ", "t"),
                "within" to listOf("w", "ɪ", "ð", "ɪ", "n"),
                "up" to listOf("ʌ", "p"),
                "down" to listOf("d", "a", "ʊ", "n"),
                "out" to listOf("a", "ʊ", "t"),
                "over" to listOf("o", "ʊ", "v", "ɹ"),
                "under" to listOf("ʌ", "n", "d", "ɹ"),
                "or" to listOf("ɔ", "ɹ"),
                "and" to listOf("æ", "n", "d"),
                "but" to listOf("b", "ʌ", "t"),
                "so" to listOf("s", "o", "ʊ"),
                "if" to listOf("ɪ", "f"),
                "as" to listOf("æ", "z"),
                "than" to listOf("ð", "æ", "n"),
                "not" to listOf("n", "ɑ", "t"),
                "also" to listOf("ɔ", "l", "s", "o", "ʊ"),
                "just" to listOf("d", "ʒ", "ʌ", "s", "t"),
                "only" to listOf("o", "ʊ", "n", "l", "i"),
                "even" to listOf("i", "v", "ə", "n"),
                "still" to listOf("s", "t", "ɪ", "l"),
                "already" to listOf("ɔ", "l", "ɹ", "ɛ", "d", "i"),
                "now" to listOf("n", "a", "ʊ"),
                "then" to listOf("ð", "ɛ", "n"),
                "here" to listOf("h", "ɪ", "ɹ"),
                "there" to listOf("ð", "ɛ", "ɹ"),
                "because" to listOf("b", "ɪ", "k", "ɔ", "z"),
                "since" to listOf("s", "ɪ", "n", "s"),
                "while" to listOf("w", "a", "ɪ", "l"),
                "please" to listOf("p", "l", "i", "z"),
                // Assistant-specific words
                "hello" to listOf("h", "ɛ", "l", "o", "ʊ"),
                "hey" to listOf("h", "e", "ɪ"),
                "hi" to listOf("h", "a", "ɪ"),
                "yes" to listOf("j", "ɛ", "s"),
                "no" to listOf("n", "o", "ʊ"),
                "name" to listOf("n", "e", "ɪ", "m"),
                "zero" to listOf("z", "i", "ɹ", "o", "ʊ"),
                "assist" to listOf("ə", "s", "ɪ", "s", "t"),
                "assistant" to listOf("ə", "s", "ɪ", "s", "t", "ə", "n", "t"),
                "alarm" to listOf("ə", "l", "ɑ", "ɹ", "m"),
                "timer" to listOf("t", "a", "ɪ", "m", "ɹ"),
                "reminder" to listOf("ɹ", "ɪ", "m", "a", "ɪ", "n", "d", "ɹ"),
                "message" to listOf("m", "ɛ", "s", "ɪ", "d", "ʒ"),
                "call" to listOf("k", "ɔ", "l"),
                "contact" to listOf("k", "ɑ", "n", "t", "æ", "k", "t"),
                "phone" to listOf("f", "o", "ʊ", "n"),
                "number" to listOf("n", "ʌ", "m", "b", "ɹ"),
                "email" to listOf("i", "m", "e", "ɪ", "l"),
                "time" to listOf("t", "a", "ɪ", "m"),
                "date" to listOf("d", "e", "ɪ", "t"),
                "today" to listOf("t", "ə", "d", "e", "ɪ"),
                "tomorrow" to listOf("t", "ə", "m", "ɑ", "ɹ", "o", "ʊ"),
                "yesterday" to listOf("j", "ɛ", "s", "t", "ɹ", "d", "e", "ɪ"),
                "morning" to listOf("m", "ɔ", "ɹ", "n", "ɪ", "ŋ"),
                "evening" to listOf("i", "v", "n", "ɪ", "ŋ"),
                "night" to listOf("n", "a", "ɪ", "t"),
                "hour" to listOf("a", "ʊ", "ɹ"),
                "minute" to listOf("m", "ɪ", "n", "ɪ", "t"),
                "second" to listOf("s", "ɛ", "k", "ə", "n", "d"),
                "week" to listOf("w", "i", "k"),
                "month" to listOf("m", "ʌ", "n", "θ"),
                "year" to listOf("j", "ɪ", "ɹ"),
                "information" to listOf("ɪ", "n", "f", "ɹ", "m", "e", "ɪ", "ʃ", "ə", "n"),
                "question" to listOf("k", "w", "ɛ", "s", "t", "ʃ", "ə", "n"),
                "answer" to listOf("æ", "n", "s", "ɹ"),
                "result" to listOf("ɹ", "ɪ", "z", "ʌ", "l", "t"),
                "task" to listOf("t", "æ", "s", "k"),
                "app" to listOf("æ", "p"),
                "data" to listOf("d", "e", "ɪ", "t", "ə"),
                "file" to listOf("f", "a", "ɪ", "l"),
                "list" to listOf("l", "ɪ", "s", "t"),
                "item" to listOf("a", "ɪ", "t", "ə", "m"),
                "note" to listOf("n", "o", "ʊ", "t"),
                "notes" to listOf("n", "o", "ʊ", "t", "s"),
                // Greetings / responses
                "thank" to listOf("θ", "æ", "ŋ", "k"),
                "thanks" to listOf("θ", "æ", "ŋ", "k", "s"),
                "welcome" to listOf("w", "ɛ", "l", "k", "ə", "m"),
                "sorry" to listOf("s", "ɑ", "ɹ", "i"),
                "please" to listOf("p", "l", "i", "z"),
                "ok" to listOf("o", "ʊ", "k", "e", "ɪ"),
                "okay" to listOf("o", "ʊ", "k", "e", "ɪ"),
                "great" to listOf("g", "ɹ", "e", "ɪ", "t"),
                "perfect" to listOf("p", "ɹ", "f", "ɪ", "k", "t"),
                "certainly" to listOf("s", "ɹ", "t", "ə", "n", "l", "i"),
                "absolutely" to listOf("æ", "b", "s", "ə", "l", "u", "t", "l", "i"),
                "exactly" to listOf("ɪ", "g", "z", "æ", "k", "t", "l", "i"),
                "understand" to listOf("ʌ", "n", "d", "ɹ", "s", "t", "æ", "n", "d"),
                "understood" to listOf("ʌ", "n", "d", "ɹ", "s", "t", "ʊ", "d"),
                "let" to listOf("l", "ɛ", "t"),
                "however" to listOf("h", "a", "ʊ", "ɛ", "v", "ɹ"),
                "that's" to listOf("ð", "æ", "t", "s"),
                // Numbers
                "one" to listOf("w", "ʌ", "n"),
                "two" to listOf("t", "u"),
                "three" to listOf("θ", "ɹ", "i"),
                "four" to listOf("f", "ɔ", "ɹ"),
                "five" to listOf("f", "a", "ɪ", "v"),
                "six" to listOf("s", "ɪ", "k", "s"),
                "seven" to listOf("s", "ɛ", "v", "ə", "n"),
                "eight" to listOf("e", "ɪ", "t"),
                "nine" to listOf("n", "a", "ɪ", "n"),
                "ten" to listOf("t", "ɛ", "n"),
            )
        private val DIGRAPH_RULES =
            listOf(
                "tion" to listOf("ʃ", "ə", "n"),
                "sion" to listOf("ʒ", "ə", "n"),
                "ch" to listOf("t", "ʃ"),
                "sh" to listOf("ʃ"),
                "th" to listOf("θ"),
                "ph" to listOf("f"),
                "ng" to listOf("ŋ"),
                "qu" to listOf("k", "w"),
                "ck" to listOf("k"),
                "ee" to listOf("i"),
                "ea" to listOf("i"),
                "oo" to listOf("u"),
                "ou" to listOf("a", "ʊ"),
                "ow" to listOf("a", "ʊ"),
                "ai" to listOf("e", "ɪ"),
                "ay" to listOf("e", "ɪ"),
                "oi" to listOf("ɔ", "ɪ"),
                "oy" to listOf("ɔ", "ɪ"),
            )
        private val LONG_VOWELS =
            mapOf(
                'a' to listOf("e", "ɪ"),
                'e' to listOf("i"),
                'i' to listOf("a", "ɪ"),
                'o' to listOf("o", "ʊ"),
                'u' to listOf("j", "u"),
            )
        private val LETTERS =
            mapOf(
                'a' to listOf("æ"),
                'b' to listOf("b"),
                'c' to listOf("k"),
                'd' to listOf("d"),
                'e' to listOf("ɛ"),
                'f' to listOf("f"),
                'g' to listOf("g"),
                'h' to listOf("h"),
                'i' to listOf("ɪ"),
                'j' to listOf("d", "ʒ"),
                'k' to listOf("k"),
                'l' to listOf("l"),
                'm' to listOf("m"),
                'n' to listOf("n"),
                'o' to listOf("ɑ"),
                'p' to listOf("p"),
                'q' to listOf("k"),
                'r' to listOf("ɹ"),
                's' to listOf("s"),
                't' to listOf("t"),
                'u' to listOf("ʌ"),
                'v' to listOf("v"),
                'w' to listOf("w"),
                'x' to listOf("k", "s"),
                'y' to listOf("j"),
                'z' to listOf("z"),
            )
    }
}

internal class PiperTextPhonemeEncoder : PiperPhonemizer {
    fun readiness(config: PiperVoiceConfig): PiperPhonemizerReadiness =
        readiness(config, voicePackage = null)

    override fun readiness(
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemizerReadiness =
        if (config.phonemeType.equals(PHONEME_TYPE_TEXT, ignoreCase = true)) {
            PiperPhonemizerReadiness.Ready
        } else {
            PiperPhonemizerReadiness.Unavailable(ESPEAK_PHONEMIZER_MISSING)
        }

    fun encode(
        text: String,
        config: PiperVoiceConfig,
    ): PiperPhonemeEncodeResult =
        encode(
            text = text,
            config = config,
            voicePackage = null,
        )

    override fun encode(
        text: String,
        config: PiperVoiceConfig,
        voicePackage: ResolvedCustomVoicePackage?,
    ): PiperPhonemeEncodeResult {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return PiperPhonemeEncodeResult.Failure("Speech text is blank.")
        }

        when (val readiness = readiness(config, voicePackage)) {
            PiperPhonemizerReadiness.Ready -> Unit
            is PiperPhonemizerReadiness.Unavailable ->
                return PiperPhonemeEncodeResult.Failure(readiness.message)
        }

        val separatorIds =
            config.phonemeIdMap[PHONEME_SEPARATOR]
                ?: return PiperPhonemeEncodeResult.Failure(
                    "Piper voice config is missing separator phoneme id.",
                )
        val startIds =
            config.phonemeIdMap[PHONEME_START]
                ?: return PiperPhonemeEncodeResult.Failure(
                    "Piper voice config is missing start phoneme id.",
                )
        val endIds =
            config.phonemeIdMap[PHONEME_END]
                ?: return PiperPhonemeEncodeResult.Failure(
                    "Piper voice config is missing end phoneme id.",
                )

        val encoded = mutableListOf<Long>()
        encoded += startIds
        encoded += separatorIds

        var mappedTextSymbols = 0
        normalizedText.codePoints().forEach { codePoint ->
            val symbol = String(Character.toChars(codePoint))
            val ids =
                config.phonemeIdMap[symbol]
                    ?: config.phonemeIdMap[symbol.lowercase()]
            if (ids != null) {
                encoded += ids
                encoded += separatorIds
                mappedTextSymbols += 1
            }
        }

        if (mappedTextSymbols == 0) {
            return PiperPhonemeEncodeResult.Failure(
                "Piper voice config cannot encode this text locally.",
            )
        }

        encoded += endIds
        return PiperPhonemeEncodeResult.Success(encoded.toLongArray())
    }

    private companion object {
        private const val PHONEME_SEPARATOR = "_"
        private const val PHONEME_START = "^"
        private const val PHONEME_END = "$"
    }
}

private fun String.normalizedPhonemeType(): String = trim().lowercase()

private fun String?.isEnglishEspeakVoice(): Boolean =
    this
        ?.trim()
        ?.lowercase(Locale.US)
        ?.let { voice ->
            voice == "en" || voice.startsWith("en-") || voice.startsWith("en_")
        } == true

private fun unsupportedPhonemeTypeMessage(phonemeType: String): String =
    "Unsupported Piper phoneme type '${phonemeType.trim()}' for local playback."

private fun Throwable.readableMessage(): String = message ?: javaClass.simpleName
