/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelSource
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** Android TextToSpeech driver that allows only phone-local/offline voices. */
class AndroidLocalSpeechOutputDriver(
    context: Context,
) : LocalSpeechOutputDriver {
    private val appContext = context.applicationContext
    private val voiceCache = ConcurrentHashMap<String, Voice>()
    private val _status =
        MutableStateFlow<LocalSpeechEngineStatus>(
            LocalSpeechEngineStatus.Initializing,
        )

    override val status = _status.asStateFlow()

    override val engine: LocalSpeechOutputEngine = LocalSpeechOutputEngine.ANDROID_TTS

    @Volatile
    private var textToSpeech: TextToSpeech? = null

    init {
        textToSpeech =
            TextToSpeech(appContext) { initStatus ->
                handleInit(initStatus)
            }
    }

    override fun findVoice(voice: VoiceModel): LocalSpeechOutputVoice? {
        val engine = textToSpeech ?: return null
        if (status.value != LocalSpeechEngineStatus.Ready) {
            return null
        }
        if (voice.source == VoiceModelSource.IMPORTED &&
            !voice.modelUri.orEmpty().startsWith(ANDROID_TTS_URI_PREFIX)
        ) {
            return null
        }

        val locale = Locale.forLanguageTag(voice.localeTag)
        val androidVoice = findOfflineVoice(engine, locale) ?: return null
        voiceCache[androidVoice.name] = androidVoice
        return LocalSpeechOutputVoice(
            id = androidVoice.name,
            localeTag = androidVoice.locale.toLanguageTag(),
            requiresNetwork = androidVoice.isNetworkConnectionRequired,
        )
    }

    override suspend fun speak(
        text: String,
        voice: LocalSpeechOutputVoice,
    ): SpeechSynthesisResult =
        speak(text, voice, VoiceTurnTrace.noop())

    override suspend fun speak(
        text: String,
        voice: LocalSpeechOutputVoice,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult {
        if (voice.requiresNetwork) {
            return SpeechSynthesisResult.Failed(
                "Network-backed Android TTS voices are blocked in local voice mode.",
            )
        }

        val engine =
            textToSpeech
                ?: return SpeechSynthesisResult.Failed("Android TTS is not initialized.")
        val prepareResult = prepareAndroidVoice(engine, voice)
        if (prepareResult != SpeechSynthesisResult.Completed) {
            return prepareResult
        }
        trace.mark("android_tts_voice_selected", "voice=${voice.id}")
        return speakWithAndroidTts(engine, text, trace)
    }

    override suspend fun prepare(voice: LocalSpeechOutputVoice): SpeechSynthesisResult {
        if (voice.requiresNetwork) {
            return SpeechSynthesisResult.Failed(
                "Network-backed Android TTS voices are blocked in local voice mode.",
            )
        }

        val engine =
            textToSpeech
                ?: return SpeechSynthesisResult.Failed("Android TTS is not initialized.")
        return prepareAndroidVoice(engine, voice)
    }

    override fun stop() {
        textToSpeech?.stop()
    }

    private fun handleInit(initStatus: Int) {
        val engine = textToSpeech
        if (initStatus != TextToSpeech.SUCCESS || engine == null) {
            _status.value = LocalSpeechEngineStatus.Unavailable("Android TTS is unavailable.")
            return
        }

        val offlineEnglishVoices =
            engine.voices.orEmpty().filter { voice ->
                !voice.isNetworkConnectionRequired && voice.locale.language == "en"
            }
        _status.value =
            if (offlineEnglishVoices.isEmpty()) {
                LocalSpeechEngineStatus.MissingModel
            } else {
                LocalSpeechEngineStatus.Ready
            }
    }

    private fun findOfflineVoice(
        engine: TextToSpeech,
        locale: Locale,
    ): Voice? {
        val offlineVoices =
            engine.voices.orEmpty().filter { voice ->
                !voice.isNetworkConnectionRequired &&
                    voice.locale.language.equals(locale.language, ignoreCase = true)
            }
        if (offlineVoices.isEmpty()) {
            return null
        }
        val requestedTag = locale.toLanguageTag()
        return offlineVoices.firstOrNull { voice ->
            voice.locale.toLanguageTag().equals(requestedTag, ignoreCase = true)
        } ?: offlineVoices.first()
    }

    private fun prepareAndroidVoice(
        engine: TextToSpeech,
        voice: LocalSpeechOutputVoice,
    ): SpeechSynthesisResult {
        val androidVoice =
            voiceCache[voice.id]
                ?: engine.voices?.firstOrNull { it.name == voice.id }
                ?: return SpeechSynthesisResult.Failed("Offline Android TTS voice is unavailable.")
        if (androidVoice.isNetworkConnectionRequired) {
            return SpeechSynthesisResult.Failed(
                "Network-backed Android TTS voices are blocked in local voice mode.",
            )
        }

        val setVoiceResult = engine.setVoice(androidVoice)
        if (setVoiceResult != TextToSpeech.SUCCESS) {
            return SpeechSynthesisResult.Failed("Android TTS rejected the offline voice.")
        }

        engine.setSpeechRate(LOCAL_ANDROID_TTS_SPEECH_RATE)
        engine.setPitch(LOCAL_ANDROID_TTS_PITCH)
        return SpeechSynthesisResult.Completed
    }

    private suspend fun speakWithAndroidTts(
        engine: TextToSpeech,
        text: String,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult =
        suspendCancellableCoroutine { continuation ->
            val utteranceId = "zero-assist-${System.nanoTime()}"
            val completed = AtomicBoolean(false)
            fun complete(result: SpeechSynthesisResult) {
                if (completed.compareAndSet(false, true)) {
                    continuation.resume(result)
                }
            }

            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        trace.markOnce("first_tts_audio_ready", "driver=android_tts")
                        trace.markOnce("playback_started", "driver=android_tts")
                    }

                    override fun onDone(utteranceId: String?) {
                        trace.markOnce("playback_completed", "driver=android_tts")
                        complete(SpeechSynthesisResult.Completed)
                    }

                    @Deprecated("Deprecated Android callback")
                    override fun onError(utteranceId: String?) {
                        trace.mark("playback_failed", "driver=android_tts")
                        complete(SpeechSynthesisResult.Failed("Android TTS playback failed."))
                    }

                    override fun onError(
                        utteranceId: String?,
                        errorCode: Int,
                    ) {
                        trace.mark("playback_failed", "driver=android_tts code=$errorCode")
                        complete(
                            SpeechSynthesisResult.Failed(
                                "Android TTS playback failed with code $errorCode.",
                            ),
                        )
                    }

                    override fun onStop(
                        utteranceId: String?,
                        interrupted: Boolean,
                    ) {
                        trace.mark("playback_cancelled", "driver=android_tts interrupted=$interrupted")
                        complete(SpeechSynthesisResult.Cancelled)
                    }
                },
            )

            continuation.invokeOnCancellation {
                engine.stop()
            }

            val result =
                engine.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    Bundle(),
                    utteranceId,
            )
            if (result != TextToSpeech.SUCCESS) {
                trace.mark("playback_failed", "driver=android_tts refused=true")
                complete(SpeechSynthesisResult.Failed("Android TTS refused playback."))
            }
        }

    companion object {
        const val ANDROID_TTS_URI_PREFIX = "android-tts://"
        private const val LOCAL_ANDROID_TTS_SPEECH_RATE = 1.02f
        private const val LOCAL_ANDROID_TTS_PITCH = 0.93f
    }
}
