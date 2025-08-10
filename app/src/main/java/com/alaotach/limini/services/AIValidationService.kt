package com.alaotach.limini.services

import android.content.Context
import android.util.Log
import com.alaotach.limini.data.ExtensionRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AIValidationService(private val context: Context) {

    companion object {
        private const val TAG = "AIValidServ"
        private const val AI_ENDPOINT = "https://ai.hackclub.com/chat/completions"
    }

    suspend fun validateExtensionRequest(request: ExtensionRequest): ValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = buildPrompt(request)
                val aiResponseJson = makeAIRequest(prompt)
                parseValidationResponse(aiResponseJson)
            } catch (e: Exception) {
                Log.e(TAG, "Error validating extension request with AI, using fallback.", e)
                fallbackValidation(request)
            }
        }
    }
    private fun buildPrompt(request: ExtensionRequest): String {
        return """
            Analyze this app usage extension request. The user answered a question correctly and provided a reason for wanting to extend their app usage time.

            App: ${request.appName}
            Question was answered correctly: ${request.questionResponse.isCorrect}
            User's reason: "${request.questionResponse.reason}"
            Requested extension: ${request.requestedMinutes} minutes

            Evaluate if the reason is:
            1. Sensible and legitimate (not just "I want to use it" or similar lazy responses).
            2. Shows some thought or valid purpose.
            3. Not obviously trying to game the system.

            Respond with ONLY a JSON object in the format:
            {
                "approved": true/false,
                "confidence": 0.0-1.0,
                "feedback": "A brief explanation for your decision.",
                "suggested_time": <integer>
            }
            
            The 'suggested_time' can be less than requested if the reason is weak but not entirely invalid. Be reasonably lenient; the goal is to promote mindful usage, not to be overly strict.
        """.trimIndent()
    }

    private fun makeAIRequest(prompt: String): String {
        val url = URL(AI_ENDPOINT)
        val connection = url.openConnection() as HttpURLConnection
        var response = ""

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            val jsonPayload = JSONObject().apply {
                put("model", "gpt-3.5-turbo")
                put("messages", JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
                put("response_format", JSONObject().apply { 
                    put("type", "json_object")
                })
            }
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonPayload.toString())
            }
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    val responseJson = JSONObject(reader.readText())
                    response = responseJson.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }
            } else {
                val errorStream = BufferedReader(InputStreamReader(connection.errorStream)).readText()
                throw Exception("HTTP Error: $responseCode, Message: $errorStream")
            }
        } finally {
            connection.disconnect()
        }
        return response
    }
    private fun parseValidationResponse(response: String): ValidationResult {
        return try {
            val json = JSONObject(response)
            ValidationResult(
                approved = json.getBoolean("approved"),
                confidence = json.getDouble("confidence"),
                feedback = json.getString("feedback"),
                suggestedTimeMinutes = json.getInt("suggested_time")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing AI response: $response", e)
            ValidationResult(approved = false, confidence = 0.0, feedback = "Error processing AI response.", suggestedTimeMinutes = 0)
        }
    }
    private fun fallbackValidation(request: ExtensionRequest): ValidationResult {
        val isApproved = request.questionResponse.isCorrect && request.questionResponse.reason.isNotBlank()
        
        return ValidationResult(
            approved = isApproved,
            confidence = 0.4,
            feedback = if (isApproved) "Approved by fallback: AI service unavailable." else "Request could not be validated.",
            suggestedTimeMinutes = if (isApproved) 3 else 0
        )
    }
}
data class ValidationResult(
    val approved: Boolean,
    val confidence: Double,
    val feedback: String,
    val suggestedTimeMinutes: Int
)