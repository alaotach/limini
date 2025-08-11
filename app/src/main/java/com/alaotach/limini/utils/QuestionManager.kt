package com.alaotach.limini.utils

import android.content.Context
import android.util.Log
import com.alaotach.limini.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class QuestionManager(private val context: Context) {
    
    private val sharedPrefs = context.getSharedPreferences("questions", Context.MODE_PRIVATE)
    
    companion object {
        private const val TAG = "QuestionManager"
        private const val AI_ENDPOINT = "https://ai.hackclub.com/chat/completions"
        
        // Rotate between different models for variety
        private val AI_MODELS = listOf(
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            "openai/gpt-oss-120b", 
            "qwen/qwen3-32b",
            "openai/gpt-oss-20b"
        )
        
        val CATEGORIES = listOf(
            QuestionCategory("gk", "General Knowledge", "🌍", "World facts, history, geography"),
            QuestionCategory("maths", "Mathematics", "🔢", "Arithmetic, algebra, geometry"),
            QuestionCategory("science", "Science", "⚗️", "Physics, chemistry, biology"),
            QuestionCategory("tech", "Technology", "💻", "Computers, programming, gadgets"),
            QuestionCategory("entertainment", "Entertainment", "🎬", "Movies, music, celebrities"),
            QuestionCategory("anime", "Anime & Manga", "🎌", "Japanese animation and comics"),
            QuestionCategory("sports", "Sports", "⚽", "Games, athletes, tournaments"),
            QuestionCategory("literature", "Literature", "📚", "Books, authors, poetry"),
            QuestionCategory("geography", "Geography", "🗺️", "Countries, capitals, landmarks"),
            QuestionCategory("history", "History", "🏛️", "Past events, civilizations, wars")
        )
    }
    
    private val questionBank = mutableMapOf<String, MutableList<Question>>().apply {
        put("gk", mutableListOf(
            Question("gk1", CATEGORIES[0], "What is the capital of Australia?", 
                listOf("Sydney", "Melbourne", "Canberra", "Perth"), "Canberra"),
            Question("gk2", CATEGORIES[0], "Which planet is known as the Red Planet?", 
                listOf("Venus", "Mars", "Jupiter", "Saturn"), "Mars"),
            Question("gk3", CATEGORIES[0], "What is the largest ocean on Earth?", 
                listOf("Atlantic", "Indian", "Arctic", "Pacific"), "Pacific"),
            Question("gk4", CATEGORIES[0], "Who painted the Mona Lisa?", 
                listOf("Van Gogh", "Picasso", "Da Vinci", "Michelangelo"), "Da Vinci"),
            Question("gk5", CATEGORIES[0], "What is the smallest country in the world?", 
                listOf("Monaco", "Vatican City", "San Marino", "Liechtenstein"), "Vatican City")
        ))
        
        put("maths", mutableListOf(
            Question("math1", CATEGORIES[1], "What is 15 × 23?", 
                listOf("345", "335", "355", "325"), "345"),
            Question("math2", CATEGORIES[1], "What is the square root of 144?", 
                listOf("12", "14", "16", "18"), "12"),
            Question("math3", CATEGORIES[1], "If a triangle has angles 60°, 60°, what is the third angle?", 
                listOf("60°", "30°", "90°", "45°"), "60°"),
            Question("math4", CATEGORIES[1], "What is 2^8?", 
                listOf("256", "128", "512", "64"), "256"),
            Question("math5", CATEGORIES[1], "What is the value of π (pi) to 2 decimal places?", 
                listOf("3.14", "3.15", "3.16", "3.13"), "3.14")
        ))
        
        put("science", mutableListOf(
            Question("sci1", CATEGORIES[2], "What is the chemical symbol for Gold?", 
                listOf("Gd", "Au", "Ag", "Go"), "Au"),
            Question("sci2", CATEGORIES[2], "How many bones are in an adult human body?", 
                listOf("206", "208", "204", "210"), "206"),
            Question("sci3", CATEGORIES[2], "What gas makes up about 78% of Earth's atmosphere?", 
                listOf("Oxygen", "Carbon Dioxide", "Nitrogen", "Hydrogen"), "Nitrogen"),
            Question("sci4", CATEGORIES[2], "What is the hardest natural substance?", 
                listOf("Gold", "Iron", "Diamond", "Platinum"), "Diamond"),
            Question("sci5", CATEGORIES[2], "At what temperature does water boil (Celsius)?", 
                listOf("90°C", "100°C", "110°C", "95°C"), "100°C")
        ))
        
        put("tech", mutableListOf(
            Question("tech1", CATEGORIES[3], "What does 'HTTP' stand for?", 
                listOf("HyperText Transfer Protocol", "High Tech Transfer Protocol", "Home Tool Transfer Protocol", "HyperText Translation Protocol"), "HyperText Transfer Protocol"),
            Question("tech2", CATEGORIES[3], "Who founded Microsoft?", 
                listOf("Steve Jobs", "Bill Gates", "Mark Zuckerberg", "Larry Page"), "Bill Gates"),
            Question("tech3", CATEGORIES[3], "What does 'AI' stand for?", 
                listOf("Automated Intelligence", "Artificial Intelligence", "Advanced Intelligence", "Alternative Intelligence"), "Artificial Intelligence"),
            Question("tech4", CATEGORIES[3], "Which company developed Android?", 
                listOf("Apple", "Microsoft", "Google", "Samsung"), "Google"),
            Question("tech5", CATEGORIES[3], "What does 'URL' stand for?", 
                listOf("Universal Resource Locator", "Uniform Resource Locator", "Universal Reference Link", "Uniform Reference Locator"), "Uniform Resource Locator")
        ))
        
        put("entertainment", mutableListOf(
            Question("ent1", CATEGORIES[4], "Which movie won the Oscar for Best Picture in 2020?", 
                listOf("Joker", "1917", "Parasite", "Once Upon a Time in Hollywood"), "Parasite"),
            Question("ent2", CATEGORIES[4], "Who sang 'Bohemian Rhapsody'?", 
                listOf("The Beatles", "Led Zeppelin", "Queen", "Pink Floyd"), "Queen"),
            Question("ent3", CATEGORIES[4], "What is the highest-grossing film of all time?", 
                listOf("Titanic", "Avatar", "Avengers: Endgame", "Star Wars: The Force Awakens"), "Avatar"),
            Question("ent4", CATEGORIES[4], "Which actor played Iron Man in the Marvel movies?", 
                listOf("Chris Evans", "Robert Downey Jr.", "Mark Ruffalo", "Chris Hemsworth"), "Robert Downey Jr."),
            Question("ent5", CATEGORIES[4], "What Netflix series is set in the 1980s in Hawkins, Indiana?", 
                listOf("Dark", "Stranger Things", "The Umbrella Academy", "Ozark"), "Stranger Things")
        ))
        
        put("anime", mutableListOf(
            Question("anime1", CATEGORIES[5], "Who is the main character in 'One Piece'?", 
                listOf("Naruto", "Luffy", "Goku", "Ichigo"), "Luffy"),
            Question("anime2", CATEGORIES[5], "What is the name of the Death Note user in the series?", 
                listOf("Light Yagami", "L", "Near", "Mello"), "Light Yagami"),
            Question("anime3", CATEGORIES[5], "In 'Dragon Ball Z', what is Goku's Saiyan name?", 
                listOf("Vegeta", "Kakarot", "Raditz", "Bardock"), "Kakarot"),
            Question("anime4", CATEGORIES[5], "What is the name of Naruto's favorite ramen shop?", 
                listOf("Ichiraku", "Ramen Yatai", "Teuchi's", "Konoha Ramen"), "Ichiraku"),
            Question("anime5", CATEGORIES[5], "In 'Attack on Titan', what are the giant humanoid creatures called?", 
                listOf("Giants", "Titans", "Colossi", "Behemoths"), "Titans")
        ))
    }
    
    private val recentAIQuestions = mutableListOf<String>()
    private val maxRecentQuestions = 10
    
    private var usedQuestions = mutableSetOf<String>()
    
    fun getEnabledCategories(): Set<String> {
        val categories = sharedPrefs.getStringSet("enabled_categories", setOf("gk", "maths", "science")) ?: setOf("gk", "maths", "science")
        android.util.Log.d("QuestionManager", "Getting enabled categories: $categories")
        return categories
    }
    
    fun setEnabledCategories(categories: Set<String>) {
        android.util.Log.d("QuestionManager", "Setting enabled categories: $categories")
        val result = sharedPrefs.edit().putStringSet("enabled_categories", categories).commit()
        android.util.Log.d("QuestionManager", "Save result: $result")
        val savedCategories = sharedPrefs.getStringSet("enabled_categories", null)
        android.util.Log.d("QuestionManager", "Verified saved categories: $savedCategories")
    }
    
    fun getRandomQuestion(excludeUsed: Boolean = true): Question? {
        val enabledCategories = getEnabledCategories()
        val availableQuestions = mutableListOf<Question>()
        
        for (category in enabledCategories) {
            questionBank[category]?.let { questions ->
                if (excludeUsed) {
                    availableQuestions.addAll(questions.filter { it.id !in usedQuestions })
                } else {
                    availableQuestions.addAll(questions)
                }
            }
        }
        
        if (availableQuestions.isEmpty()) {
            usedQuestions.clear()
            for (category in enabledCategories) {
                questionBank[category]?.let { questions ->
                    availableQuestions.addAll(questions)
                }
            }
        }
        
        return if (availableQuestions.isNotEmpty()) {
            val selectedQuestion = availableQuestions[Random.nextInt(availableQuestions.size)]
            usedQuestions.add(selectedQuestion.id)
            selectedQuestion
        } else null
    }
    
    fun markQuestionAsUsed(questionId: String) {
        usedQuestions.add(questionId)
    }
    
    fun resetUsedQuestions() {
        usedQuestions.clear()
    }
    
    fun clearRecentAIQuestions() {
        recentAIQuestions.clear()
        Log.d(TAG, "🗑️ Cleared recent AI questions cache")
    }
    
    fun validateAnswer(question: Question, userAnswer: String): Boolean {
        return when (question.type) {
            QuestionType.MULTIPLE_CHOICE -> {
                userAnswer.trim().lowercase() == question.correctAnswer.trim().lowercase()
            }
            QuestionType.TRUE_FALSE -> {
                userAnswer.trim().lowercase() in listOf("true", "false") &&
                userAnswer.trim().lowercase() == question.correctAnswer.trim().lowercase()
            }
            QuestionType.SHORT_ANSWER -> {
                val cleanUserAnswer = userAnswer.trim().lowercase()
                val cleanCorrectAnswer = question.correctAnswer.trim().lowercase()
                cleanUserAnswer.contains(cleanCorrectAnswer) || 
                cleanCorrectAnswer.contains(cleanUserAnswer) ||
                cleanUserAnswer == cleanCorrectAnswer
            }
            QuestionType.NUMERICAL -> {
                try {
                    val userNum = userAnswer.trim().toDoubleOrNull()
                    val correctNum = question.correctAnswer.trim().toDoubleOrNull()
                    userNum != null && correctNum != null && 
                    kotlin.math.abs(userNum - correctNum) < 0.001
                } catch (e: Exception) {
                    false
                }
            }
        }
    }
    
    fun getCategoryById(id: String): QuestionCategory? {
        return CATEGORIES.find { it.id == id }
    }
    
    // AI Question Generation Methods
    suspend fun generateAIQuestion(category: QuestionCategory, difficulty: Difficulty = Difficulty.MEDIUM): Question? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🤖 Generating AI question for category: ${category.name}")
                Log.d(TAG, "🌐 AI endpoint: $AI_ENDPOINT")
                
                val prompt = buildQuestionPrompt(category, difficulty)
                Log.d(TAG, "📝 Built prompt: ${prompt.take(200)}...")
                
                val response = makeAIRequest(prompt)
                Log.d(TAG, "📥 Got AI response: ${response.take(200)}...")
                
                val aiQuestion = parseQuestionResponse(response, category)
                
                if (aiQuestion != null) {
                    Log.d(TAG, "✅ AI question generated successfully: ${aiQuestion.question}")
                } else {
                    Log.w(TAG, "⚠️ parseQuestionResponse returned null")
                }
                aiQuestion
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to generate AI question: ${e.message}", e)
                null
            }
        }
    }
    
    private fun buildQuestionPrompt(category: QuestionCategory, difficulty: Difficulty): String {
        val difficultyLevel = when (difficulty) {
            Difficulty.EASY -> "basic level, suitable for general knowledge"
            Difficulty.MEDIUM -> "intermediate level, requires some specific knowledge"
            Difficulty.HARD -> "advanced level, for experts or enthusiasts"
        }
        
        val recentQuestionsContext = if (recentAIQuestions.isNotEmpty()) {
            "\nAvoid creating questions similar to these recent ones:\n" + 
            recentAIQuestions.takeLast(5).joinToString("\n") { "- $it" } + "\n"
        } else ""
        
        val timestamp = System.currentTimeMillis() % 10000 // Add some randomness
        
        return """
Create a ${difficultyLevel} multiple-choice question about ${category.name}: ${category.description}.

Requirements:
- Educational and factual
- Clear, unambiguous question
- Exactly 4 distinct options
- Only one correct answer
- No trick questions
- Must be unique and different from recent questions
- Be creative and vary the topic within the category${recentQuestionsContext}
Session ID: $timestamp

Response format: ONLY valid JSON, no markdown, no explanation, no extra text:

{"question":"What is the capital of France?","options":["London","Paris","Berlin","Madrid"],"correct_answer":"Paris","difficulty":"medium"}

Category: ${category.name}
Difficulty: ${difficulty.name.lowercase()}
        """.trimIndent()
    }
    
    private fun makeAIRequest(prompt: String): String {
        val url = URL(AI_ENDPOINT)
        val connection = url.openConnection() as HttpURLConnection
        var response = ""

        try {
            Log.d(TAG, "🌐 Making AI request for question generation")
            Log.d(TAG, "🔗 URL: $AI_ENDPOINT")
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000 // 15 second timeout
            connection.readTimeout = 15000
            
            val selectedModel = AI_MODELS[Random.nextInt(AI_MODELS.size)]
            Log.d(TAG, "🤖 Using AI model: $selectedModel")
            
            val jsonPayload = JSONObject().apply {
                put("model", selectedModel)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are a question generator. Respond only with valid JSON. No markdown, no explanations, no extra text.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("max_tokens", 150)
                put("temperature", 0.9)
                put("top_p", 0.9)
            }
            
            Log.d(TAG, "📤 Sending payload: ${jsonPayload.toString().take(300)}...")
            
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonPayload.toString())
                writer.flush()
            }
            
            val responseCode = connection.responseCode
            Log.d(TAG, "📡 Response code: $responseCode")
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    val responseText = reader.readText()
                    Log.d(TAG, "📄 Full response: $responseText")
                    
                    val responseJson = JSONObject(responseText)
                    response = responseJson.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        
                    Log.d(TAG, "🔍 AI response content: $response")
                }
            } else {
                val errorStream = connection.errorStream?.let { 
                    BufferedReader(InputStreamReader(it)).readText() 
                } ?: "No error details"
                Log.e(TAG, "HTTP Error: $responseCode, Message: $errorStream")
                throw Exception("HTTP Error: $responseCode - $errorStream")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in makeAIRequest: ${e.message}", e)
            throw e
        } finally {
            connection.disconnect()
        }
        return response
    }
    
    private fun parseQuestionResponse(response: String, category: QuestionCategory): Question? {
        return try {
            // Clean the response by removing markdown code blocks if present
            val cleanedResponse = response
                .replace("```json", "")
                .replace("```", "")
                .trim()
            
            Log.d(TAG, "🧹 Cleaned response: $cleanedResponse")
            
            val json = JSONObject(cleanedResponse)
            
            val questionText = json.getString("question")
            val options = json.getJSONArray("options")
            val optionsList = mutableListOf<String>()
            
            for (i in 0 until options.length()) {
                optionsList.add(options.getString(i))
            }
            
            val correctAnswer = json.getString("correct_answer")
            val difficultyStr = json.optString("difficulty", "medium")
            val difficulty = when (difficultyStr.lowercase()) {
                "easy" -> Difficulty.EASY
                "hard" -> Difficulty.HARD
                else -> Difficulty.MEDIUM
            }
            
            // Generate unique ID for AI question
            val questionId = "ai_${category.id}_${System.currentTimeMillis()}"
            
            val question = Question(
                id = questionId,
                category = category,
                question = questionText,
                options = optionsList,
                correctAnswer = correctAnswer,
                difficulty = difficulty,
                type = QuestionType.MULTIPLE_CHOICE
            )
            
            // Track this question to avoid repetition
            recentAIQuestions.add(questionText)
            if (recentAIQuestions.size > maxRecentQuestions) {
                recentAIQuestions.removeAt(0) // Remove oldest
            }
            
            Log.d(TAG, "✅ AI question created and tracked: '$questionText'")
            question
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse question response: ${e.message}", e)
            Log.e(TAG, "📄 Raw response was: $response")
            null
        }
    }
    
    fun isAIQuestionsEnabled(): Boolean {
        val enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("enable_ai_questions", true) // Default to true for AI questions
        Log.d(TAG, "🤖 AI Questions enabled: $enabled")
        return enabled
    }
    
    // Enhanced getRandomQuestion that can use AI generation
    suspend fun getRandomQuestionWithAI(excludeUsed: Boolean = true, useAI: Boolean = isAIQuestionsEnabled()): Question? {
        val enabledCategories = getEnabledCategories()
        
        Log.d(TAG, "🎲 getRandomQuestionWithAI called - useAI: $useAI, categories: $enabledCategories")
        
        // Try to get AI question first if enabled
        if (useAI && enabledCategories.isNotEmpty()) {
            val randomCategory = CATEGORIES.filter { it.id in enabledCategories }.randomOrNull()
            if (randomCategory != null) {
                Log.d(TAG, "🎯 Attempting AI generation for category: ${randomCategory.name}")
                val aiQuestion = generateAIQuestion(randomCategory)
                if (aiQuestion != null) {
                    Log.d(TAG, "🚀 AI question successfully generated!")
                    return aiQuestion
                } else {
                    Log.w(TAG, "⚠️ AI question generation returned null")
                }
            } else {
                Log.w(TAG, "⚠️ No random category selected for AI generation")
            }
        } else {
            Log.d(TAG, "📝 Skipping AI generation - useAI: $useAI, hasCategories: ${enabledCategories.isNotEmpty()}")
        }
        
        // Fallback to hardcoded questions
        Log.d(TAG, "📚 Falling back to hardcoded questions")
        return getRandomQuestion(excludeUsed)
    }
}
