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
                val result = parseValidationResponse(aiResponseJson)
                result
            } catch (e: Exception) {
                fallbackValidation(request)
            }
        }
    }
    private fun buildPrompt(request: ExtensionRequest): String {
        return """
            Evaluate app extension request. User answered correctly but needs reason validation.

            App: ${request.appName}
            Reason: "${request.questionResponse.reason}"
            Requested: ${request.requestedMinutes} minutes

            Criteria for approval:
            - Specific purpose (study, work, communication)
            - Not vague ("bored", "want to use", "need more time")
            - Shows mindful usage intent
            - Educational or productive value

            Response: ONLY JSON, no markdown, no explanation:

            {"approved":true,"confidence":0.8,"feedback":"Valid educational purpose","suggested_time":5}

            Evaluation rules:
            - approved: true if reason shows specific intent
            - confidence: 0.1-1.0 based on reason quality  
            - feedback: brief explanation (max 10 words)
            - suggested_time: 0 if denied, 1-${request.requestedMinutes} if approved
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
                put("model", "meta-llama/llama-4-maverick-17b-128e-instruct")
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are a validation service. Respond only with valid JSON. No markdown, no explanations, no extra text.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("max_tokens", 80)
                put("temperature", 0.1)
                put("top_p", 0.8)
            }
            
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonPayload.toString())
            }
            
            val responseCode = connection.responseCode
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    val responseText = reader.readText()
                    Log.d(TAG, "Raw response: $responseText")
                    
                    val responseJson = JSONObject(responseText)
                    response = responseJson.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }
            } else {
                val errorStream = BufferedReader(InputStreamReader(connection.errorStream)).readText()
                throw Exception("HTTP Error: $responseCode, Message: $errorStream")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Err: ${e.message}", e)
            throw e
        } finally {
            connection.disconnect()
        }
        return response
    }
    private fun parseValidationResponse(response: String): ValidationResult {
        return try {
            val cleanedResponse = response
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val json = JSONObject(cleanedResponse)
            
            val result = ValidationResult(
                approved = json.getBoolean("approved"),
                confidence = json.getDouble("confidence"),
                feedback = json.getString("feedback"),
                suggestedTimeMinutes = json.optInt("suggested_time", 0)
            )
            result
        } catch (e: Exception) {
            ValidationResult(
                approved = false, 
                confidence = 0.0, 
                feedback = "Error processing AI response: ${e.message}", 
                suggestedTimeMinutes = 0
            )
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