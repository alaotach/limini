package com.alaotach.limini.utils

import android.content.Context
import com.alaotach.limini.data.*
import kotlin.random.Random

class QuestionManager(private val context: Context) {
    
    private val sharedPrefs = context.getSharedPreferences("questions", Context.MODE_PRIVATE)
    
    companion object {
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
        // General Knowledge
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
        
        // Mathematics
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
        
        // Science
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
        
        // Technology
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
        
        // Entertainment
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
        
        // Anime
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
    
    private var usedQuestions = mutableSetOf<String>()
    
    fun getEnabledCategories(): Set<String> {
        return sharedPrefs.getStringSet("enabled_categories", setOf("gk", "maths", "science")) ?: setOf("gk", "maths", "science")
    }
    
    fun setEnabledCategories(categories: Set<String>) {
        sharedPrefs.edit().putStringSet("enabled_categories", categories).apply()
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
            // Reset used questions if all are used
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
}
