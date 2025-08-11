package com.alaotach.limini

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import android.util.Log
import com.alaotach.limini.utils.QuestionManager
import com.alaotach.limini.data.QuestionCategory

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var questionManager: QuestionManager
    private lateinit var backButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var questionCard: CardView
    private lateinit var enableQuestionsSwitch: Switch
    private lateinit var regenerateQuestionSwitch: Switch
    private lateinit var categoryContainer: LinearLayout
    private val categoryCheckboxes = mutableMapOf<String, CheckBox>()
    private lateinit var notificationCard: CardView
    private lateinit var enableNotificationsSwitch: Switch
    private lateinit var reminderFrequencySpinner: Spinner
    private lateinit var encouragementSwitch: Switch
    private lateinit var aiCard: CardView
    private lateinit var enableAIValidationSwitch: Switch
    private lateinit var aiStrictnessSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_new)
        
        sharedPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        questionManager = QuestionManager(this)        
        initializeViews()
        setupListeners()
        runOnUiThread {
            loadSettings()
        }
    }
    
    private fun initializeViews() {
        backButton = findViewById(R.id.backButton)
        titleText = findViewById(R.id.titleText)
        questionCard = findViewById(R.id.questionCard)
        enableQuestionsSwitch = findViewById(R.id.enableQuestionsSwitch)
        regenerateQuestionSwitch = findViewById(R.id.regenerateQuestionSwitch)
        categoryContainer = findViewById(R.id.categoryContainer)
        notificationCard = findViewById(R.id.notificationCard)
        enableNotificationsSwitch = findViewById(R.id.enableNotificationsSwitch)
        reminderFrequencySpinner = findViewById(R.id.reminderFrequencySpinner)
        encouragementSwitch = findViewById(R.id.encouragementSwitch)
        aiCard = findViewById(R.id.aiCard)
        enableAIValidationSwitch = findViewById(R.id.enableAIValidationSwitch)
        aiStrictnessSpinner = findViewById(R.id.aiStrictnessSpinner)
        
        setupCategoryCheckboxes()
        setupSpinners()
    }
    
    private fun setupCategoryCheckboxes() {
        categoryContainer.removeAllViews()
        
        for (category in QuestionManager.CATEGORIES) {
            val checkBox = CheckBox(this).apply {
                text = "${category.icon} ${category.name}"
                textSize = 16f
                setPadding(16, 12, 16, 12)
                
                setOnCheckedChangeListener { _, isChecked ->
                    Log.d("SettingsActivity", "Category ${category.id} changed to $isChecked")
                    updateCategorySelection(category.id, isChecked)
                    saveSettings()
                }
            }
            
            val description = TextView(this).apply {
                text = category.description
                textSize = 12f
                setTextColor(getColor(android.R.color.darker_gray))
                setPadding(48, 0, 16, 16)
            }
            
            categoryContainer.addView(checkBox)
            categoryContainer.addView(description)
            categoryCheckboxes[category.id] = checkBox
        }
    }
    
    private fun setupSpinners() {
        val frequencies = arrayOf("Never", "Every 30 minutes", "Every hour", "Every 2 hours", "Daily")
        val frequencyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, frequencies)
        frequencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        reminderFrequencySpinner.adapter = frequencyAdapter
        val strictness = arrayOf("Lenient", "Moderate", "Strict")
        val strictnessAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, strictness)
        strictnessAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        aiStrictnessSpinner.adapter = strictnessAdapter
    }
    
    private fun setupListeners() {
        backButton.setOnClickListener {
            finish()
        }
        
        enableQuestionsSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSettings()
            updateCategoryContainerVisibility(isChecked)
        }
        
        regenerateQuestionSwitch.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }
        
        enableNotificationsSwitch.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }
        
        enableAIValidationSwitch.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }
        
        encouragementSwitch.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }
        
        reminderFrequencySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                saveSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        aiStrictnessSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                saveSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun updateCategorySelection(categoryId: String, isSelected: Boolean) {
        val currentCategories = questionManager.getEnabledCategories().toMutableSet()
        
        Log.d("SettingsActivity", "Before update - Current categories: $currentCategories")
        Log.d("SettingsActivity", "Updating category $categoryId to $isSelected")
        
        if (isSelected) {
            currentCategories.add(categoryId)
        } else {
            currentCategories.remove(categoryId)
        }
        if (currentCategories.isEmpty()) {
            currentCategories.add("gk")
            categoryCheckboxes["gk"]?.isChecked = true
        }
        questionManager.setEnabledCategories(currentCategories)
        val categoriesString = currentCategories.joinToString(",")
        sharedPrefs.edit().putString("enabled_categories_list", categoriesString).apply()
    }
    
    private fun updateCategoryContainerVisibility(enabled: Boolean) {
        categoryContainer.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
    }
    
    private fun loadSettings() {
        val questionsEnabled = sharedPrefs.getBoolean("questions_enabled", true)
        val regenerateEnabled = sharedPrefs.getBoolean("regenerate_question_on_switch", false)
        enableQuestionsSwitch.setOnCheckedChangeListener(null)
        enableQuestionsSwitch.isChecked = questionsEnabled
        enableQuestionsSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSettings()
            updateCategoryContainerVisibility(isChecked)
        }
        
        regenerateQuestionSwitch.setOnCheckedChangeListener(null)
        regenerateQuestionSwitch.isChecked = regenerateEnabled
        regenerateQuestionSwitch.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }
        
        updateCategoryContainerVisibility(questionsEnabled)
        
        val enabledCategories = questionManager.getEnabledCategories()
        for ((categoryId, checkBox) in categoryCheckboxes) {
            checkBox.setOnCheckedChangeListener(null)
            val isEnabled = categoryId in enabledCategories
            checkBox.isChecked = isEnabled
        }
        
        for (category in QuestionManager.CATEGORIES) {
            categoryCheckboxes[category.id]?.setOnCheckedChangeListener { _, isChecked ->
                updateCategorySelection(category.id, isChecked)
                saveSettings()
            }
        }
        val notificationsEnabled = sharedPrefs.getBoolean("notifications_enabled", true)
        val encouragementEnabled = sharedPrefs.getBoolean("encouragement_enabled", true)
        val reminderFreq = sharedPrefs.getInt("reminder_frequency", 2)
        val aiValidationEnabled = sharedPrefs.getBoolean("ai_validation_enabled", true)
        val aiStrictness = sharedPrefs.getInt("ai_strictness", 1)
        enableNotificationsSwitch.setOnCheckedChangeListener(null)
        enableNotificationsSwitch.isChecked = notificationsEnabled
        enableNotificationsSwitch.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }
        
        encouragementSwitch.setOnCheckedChangeListener(null)
        encouragementSwitch.isChecked = encouragementEnabled
        encouragementSwitch.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }
        
        enableAIValidationSwitch.setOnCheckedChangeListener(null)
        enableAIValidationSwitch.isChecked = aiValidationEnabled
        enableAIValidationSwitch.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }
        
        reminderFrequencySpinner.setSelection(reminderFreq)
        aiStrictnessSpinner.setSelection(aiStrictness)
    }
    
    private fun saveSettings() {
        val questionsEnabled = enableQuestionsSwitch.isChecked
        val regenerateEnabled = regenerateQuestionSwitch.isChecked
        val notificationsEnabled = enableNotificationsSwitch.isChecked
        val encouragementEnabled = encouragementSwitch.isChecked
        val reminderFreq = reminderFrequencySpinner.selectedItemPosition
        val aiValidationEnabled = enableAIValidationSwitch.isChecked
        val aiStrictness = aiStrictnessSpinner.selectedItemPosition
        sharedPrefs.edit().apply {
            putBoolean("questions_enabled", questionsEnabled)
            putBoolean("regenerate_question_on_switch", regenerateEnabled)
            putBoolean("notifications_enabled", notificationsEnabled)
            putBoolean("encouragement_enabled", encouragementEnabled)
            putInt("reminder_frequency", reminderFreq)
            putBoolean("ai_validation_enabled", aiValidationEnabled)
            putInt("ai_strictness", aiStrictness)
            val enabledCategories = mutableSetOf<String>()
            for ((categoryId, checkBox) in categoryCheckboxes) {
                if (checkBox.isChecked) {
                    enabledCategories.add(categoryId)
                }
            }
            val categoriesString = enabledCategories.joinToString(",")
            putString("enabled_categories_list", categoriesString)            
            commit()
        }
        val finalEnabledCategories = mutableSetOf<String>()
        for ((categoryId, checkBox) in categoryCheckboxes) {
            if (checkBox.isChecked) {
                finalEnabledCategories.add(categoryId)
            }
        }
        if (finalEnabledCategories.isEmpty()) {
            finalEnabledCategories.add("gk")
        }
        
        questionManager.setEnabledCategories(finalEnabledCategories)
    }
    
    override fun onResume() {
        super.onResume()
        loadSettings()
    }
    
    override fun onPause() {
        super.onPause()
        saveSettings()
    }
    
    override fun onStop() {
        super.onStop()
        saveSettings()
    }
}

