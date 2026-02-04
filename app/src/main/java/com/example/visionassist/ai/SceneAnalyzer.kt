package com.example.visionassist.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.visionassist.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Result of scene analysis
 */
sealed class AnalysisResult {
    data class Success(val description: String) : AnalysisResult()
    data class Error(val message: String, val exception: Exception? = null) : AnalysisResult()
}

/**
 * Analyzes camera frames to describe scenes for blind users
 * Supports both local ML Kit analysis and cloud-based Gemini API
 */
class SceneAnalyzer {

    // ML Kit image labeler
    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.7f)
            .build()
    )

    // ML Kit text recognizer
    private val textRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.Builder().build()
    )

    // Gemini model (optional, requires API key)
    private val geminiModel: GenerativeModel? = try {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isNotBlank()) {
            GenerativeModel(
                modelName = "gemini-1.5-flash",
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.4f
                    topK = 32
                    topP = 1f
                    maxOutputTokens = 256
                }
            )
        } else {
            null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Gemini model not initialized: ${e.message}")
        null
    }

    // Mock descriptions for demo purposes
    private val mockDescriptions = listOf(
        "I see a laptop and a cup of coffee on the table. The laptop appears to be open and there's a notebook beside it.",
        "There's a person standing about 3 meters ahead. They appear to be looking at their phone.",
        "I can see a door on your left side, approximately 2 meters away. There's also a chair near the wall.",
        "The scene shows a kitchen counter with some dishes and a fruit bowl. I can see apples and bananas.",
        "There's a window ahead with natural light coming through. I notice a plant on the windowsill.",
        "I see a bookshelf filled with books on your right side. There's also a desk lamp that's turned on.",
        "The area appears to be clear for walking. I can see a hallway extending about 5 meters ahead.",
        "There's a table with a laptop and a cup of coffee in front of you. Papers are scattered nearby."
    )

    companion object {
        private const val TAG = "SceneAnalyzer"
        private const val USE_MOCK = true // Set to false for production
    }

    /**
     * Analyzes the scene and returns a description
     * Uses mock data in development, real AI in production
     */
    suspend fun analyzeScene(bitmap: Bitmap?): AnalysisResult = withContext(Dispatchers.IO) {
        if (USE_MOCK || bitmap == null) {
            // Return mock description for demo
            return@withContext AnalysisResult.Success(
                mockDescriptions.random()
            )
        }

        try {
            // Try Gemini first if available
            geminiModel?.let { model ->
                return@withContext analyzeWithGemini(model, bitmap)
            }

            // Fall back to ML Kit
            return@withContext analyzeWithMLKit(bitmap)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing scene", e)
            return@withContext AnalysisResult.Error(
                "Failed to analyze the scene",
                e
            )
        }
    }

    /**
     * Analyzes image using Gemini Vision API
     */
    private suspend fun analyzeWithGemini(
        model: GenerativeModel,
        bitmap: Bitmap
    ): AnalysisResult = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                You are an AI assistant helping a blind person understand their surroundings.
                Describe what you see in this image in a clear, concise, and helpful way.
                Focus on:
                1. Important objects and their approximate positions (left, right, ahead)
                2. People and their activities if present
                3. Text that might be important to read
                4. Potential obstacles or hazards
                5. Navigation cues (doors, stairs, paths)
                
                Keep the description under 100 words and speak naturally as if talking to someone.
            """.trimIndent()

            val response = model.generateContent(
                content {
                    image(bitmap)
                    text(prompt)
                }
            )

            val description = response.text ?: "I couldn't generate a description."
            return@withContext AnalysisResult.Success(description)
            
        } catch (e: Exception) {
            Log.e(TAG, "Gemini analysis failed", e)
            // Fall back to ML Kit
            return@withContext analyzeWithMLKit(bitmap)
        }
    }

    /**
     * Analyzes image using local ML Kit
     */
    private suspend fun analyzeWithMLKit(bitmap: Bitmap): AnalysisResult {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        
        // Get image labels
        val labels = getImageLabels(inputImage)
        
        // Get any visible text
        val text = recognizeText(inputImage)
        
        // Build description from ML Kit results
        val description = buildDescription(labels, text)
        
        return if (description.isNotBlank()) {
            AnalysisResult.Success(description)
        } else {
            AnalysisResult.Success("I can see the scene but couldn't identify specific objects. Try moving closer or adjusting the angle.")
        }
    }

    /**
     * Gets image labels from ML Kit
     */
    private suspend fun getImageLabels(image: InputImage): List<String> = 
        suspendCancellableCoroutine { continuation ->
            imageLabeler.process(image)
                .addOnSuccessListener { labels ->
                    val labelTexts = labels
                        .sortedByDescending { it.confidence }
                        .take(5)
                        .map { it.text }
                    continuation.resume(labelTexts)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Image labeling failed", e)
                    continuation.resume(emptyList())
                }
        }

    /**
     * Recognizes text in the image
     */
    private suspend fun recognizeText(image: InputImage): String = 
        suspendCancellableCoroutine { continuation ->
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    continuation.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                    continuation.resume("")
                }
        }

    /**
     * Builds a natural language description from ML Kit results
     */
    private fun buildDescription(labels: List<String>, text: String): String {
        val parts = mutableListOf<String>()
        
        if (labels.isNotEmpty()) {
            val objectList = when (labels.size) {
                1 -> labels[0]
                2 -> "${labels[0]} and ${labels[1]}"
                else -> "${labels.dropLast(1).joinToString(", ")}, and ${labels.last()}"
            }
            parts.add("I can see $objectList in the scene.")
        }
        
        if (text.isNotBlank()) {
            val cleanedText = text.take(100).replace("\n", " ").trim()
            parts.add("There is some text that reads: \"$cleanedText\"")
        }
        
        return parts.joinToString(" ")
    }

    /**
     * Performs OCR on the image and returns the text
     */
    suspend fun readText(bitmap: Bitmap?): AnalysisResult = withContext(Dispatchers.IO) {
        if (bitmap == null) {
            return@withContext AnalysisResult.Error("No image available")
        }
        
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val text = recognizeText(inputImage)
            
            return@withContext if (text.isNotBlank()) {
                AnalysisResult.Success("I can read the following text: $text")
            } else {
                AnalysisResult.Success("I don't see any readable text in the image.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed", e)
            return@withContext AnalysisResult.Error("Failed to read text", e)
        }
    }

    /**
     * Releases resources
     */
    fun close() {
        imageLabeler.close()
        textRecognizer.close()
    }
}
