package com.example.visionassist.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Manages Text-to-Speech functionality for the app
 */
class TextToSpeechManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
    
    private val speechQueue = Channel<String>(Channel.UNLIMITED)

    companion object {
        private const val TAG = "TextToSpeechManager"
        private const val DEFAULT_SPEECH_RATE = 0.9f
        private const val DEFAULT_PITCH = 1.0f
    }

    /**
     * Initializes the TTS engine
     */
    fun initialize(onInitialized: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                
                if (result == TextToSpeech.LANG_MISSING_DATA || 
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Language not supported")
                    isInitialized = false
                    onInitialized(false)
                } else {
                    isInitialized = true
                    configureTts()
                    setupUtteranceListener()
                    Log.d(TAG, "TTS initialized successfully")
                    onInitialized(true)
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                isInitialized = false
                onInitialized(false)
            }
        }
    }

    /**
     * Configures TTS settings for accessibility
     */
    private fun configureTts() {
        tts?.apply {
            setSpeechRate(DEFAULT_SPEECH_RATE)
            setPitch(DEFAULT_PITCH)
        }
    }

    /**
     * Sets up utterance progress listener
     */
    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                Log.d(TAG, "Started speaking: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                Log.d(TAG, "Finished speaking: $utteranceId")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                Log.e(TAG, "TTS error for: $utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
                Log.e(TAG, "TTS error for: $utteranceId, code: $errorCode")
            }
        })
    }

    /**
     * Speaks the given text
     * @param text The text to speak
     * @param interrupt If true, stops current speech and speaks immediately
     */
    fun speak(text: String, interrupt: Boolean = true) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot speak")
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val queueMode = if (interrupt) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }

        tts?.speak(text, queueMode, params, utteranceId)
        Log.d(TAG, "Speaking: $text")
    }

    /**
     * Speaks text with a specific speech rate
     */
    fun speakWithRate(text: String, rate: Float, interrupt: Boolean = true) {
        tts?.setSpeechRate(rate)
        speak(text, interrupt)
        // Reset to default after speaking
        tts?.setSpeechRate(DEFAULT_SPEECH_RATE)
    }

    /**
     * Stops any current speech
     */
    fun stop() {
        if (isInitialized) {
            tts?.stop()
            _isSpeaking.value = false
            Log.d(TAG, "TTS stopped")
        }
    }

    /**
     * Pauses TTS (stops current speech)
     */
    fun pause() {
        stop()
    }

    /**
     * Resumes TTS (ready for new speech)
     */
    fun resume() {
        // TTS is stateless for resume, just ensure it's ready
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized on resume")
        }
    }

    /**
     * Sets the speech rate
     * @param rate Value between 0.1 and 2.0 (1.0 is normal)
     */
    fun setSpeechRate(rate: Float) {
        val clampedRate = rate.coerceIn(0.1f, 2.0f)
        tts?.setSpeechRate(clampedRate)
        Log.d(TAG, "Speech rate set to: $clampedRate")
    }

    /**
     * Sets the pitch
     * @param pitch Value between 0.5 and 2.0 (1.0 is normal)
     */
    fun setPitch(pitch: Float) {
        val clampedPitch = pitch.coerceIn(0.5f, 2.0f)
        tts?.setPitch(clampedPitch)
        Log.d(TAG, "Pitch set to: $clampedPitch")
    }

    /**
     * Checks if TTS is currently speaking
     */
    fun isSpeakingNow(): Boolean {
        return tts?.isSpeaking == true
    }

    /**
     * Gets available languages
     */
    fun getAvailableLanguages(): Set<Locale>? {
        return tts?.availableLanguages
    }

    /**
     * Sets the language
     */
    fun setLanguage(locale: Locale): Boolean {
        val result = tts?.setLanguage(locale)
        return result != TextToSpeech.LANG_MISSING_DATA && 
               result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * Shuts down the TTS engine
     */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS shutdown complete")
    }
}
