package com.alaotach.limini.data

data class Question(
    val id: String,
    val category: QuestionCategory,
    val question: String,
    val options: List<String>? = null,
    val correctAnswer: String,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val type: QuestionType = QuestionType.MULTIPLE_CHOICE
)

data class QuestionCategory(
    val id: String,
    val name: String,
    val icon: String,
    val description: String
)

enum class Difficulty {
    EASY, MEDIUM, HARD
}

enum class QuestionType {
    MULTIPLE_CHOICE,
    TRUE_FALSE,
    SHORT_ANSWER,
    NUMERICAL
}

data class QuestionResponse(
    val questionId: String,
    val userAnswer: String,
    val reason: String,
    val isCorrect: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class ExtensionRequest(
    val packageName: String,
    val appName: String,
    val questionResponse: QuestionResponse,
    val requestedMinutes: Int = 5
)
