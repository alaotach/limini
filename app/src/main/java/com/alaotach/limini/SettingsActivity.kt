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
    
    private lateinit var choice: SharedPreferences
    private lateinit var questionManager: QuestionManager
    private lateinit var back: ImageButton
    private lateinit var title: TextView
    private lateinit var card: CardView
    private lateinit var toggle: Switch
    private lateinit var categories: LinearLayout
    private val categoryCheckboxes = mutableMapOf<String, CheckBox>()
    private lateinit var notifi: CardView
    private lateinit var notifiToggle: Switch
    private lateinit var reminder: Spinner
    private lateinit var toggleeee: Switch
    private lateinit var aiCard: CardView
    private lateinit var aiValid: Switch
    private lateinit var strictness: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_new)
        
        choice = getSharedPreferences("settings", MODE_PRIVATE)
        questionManager = QuestionManager(this)
        
        initializeViews()
        setupListeners()
        loadSettings()
    }
    
    private fun initializeViews() {
        back = findViewById(R.id.back)
        title = findViewById(R.id.title)
        card = findViewById(R.id.card)
        toggle = findViewById(R.id.toggle)
        categories = findViewById(R.id.categories)
        notifi = findViewById(R.id.notifi)
        notifiToggle = findViewById(R.id.notifiToggle)
        reminder = findViewById(R.id.reminder)
        toggleeee = findViewById(R.id.toggleeee)
        aiCard = findViewById(R.id.aiCard)
        aiValid = findViewById(R.id.aiValid)
        strictness = findViewById(R.id.strictness)
        
        setupCategoryCheckboxes()
        setupSpinners()
    }
    
    private fun setupCategoryCheckboxes() {
        categories.removeAllViews()
        
        for (category in QuestionManager.CATEGORIES) {
            val checkBox = CheckBox(this).apply {
                text = "${category.icon} ${category.name}"
                textSize = 16f
                setPadding(16, 12, 16, 12)
                
                setOnCheckedChangeListener { _, isChecked ->
                    updateCategorySelection(category.id, isChecked)
                }
            }
            val description = TextView(this).apply {
                text = category.description
                textSize = 12f
                setTextColor(getColor(android.R.color.darker_gray))
                setPadding(48, 0, 16, 16)
            }
            
            categories.addView(checkBox)
            categories.addView(description)
            categoryCheckboxes[category.id] = checkBox
        }
    }
    
    private fun setupSpinners() {
        val frequencies = arrayOf("Never", "Every 30 minutes", "Every hour", "Every 2 hours", "Daily")
        val frequencyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, frequencies)
        frequencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        reminder.adapter = frequencyAdapter

        val strictness = arrayOf("Lenient", "Moderate", "Strict")
        val strictnessAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, strictness)
        strictnessAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        strictness.adapter = strictnessAdapter
    }
    
    private fun setupListeners() {
        back.setOnClickListener {
            finish()
        }
        
        toggle.setOnCheckedChangeListener { _, isChecked ->
            saveSettings()
            updatecategoriesVisibility(isChecked)
        }
        
        notifiToggle.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }
        
        aiValid.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }
        
        toggleeee.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }
        
        reminder.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                saveSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        strictness.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                saveSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun updateCategorySelection(categoryId: String, isSelected: Boolean) {
        val currentCategories = questionManager.getEnabledCategories().toMutableSet()
        
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
        Log.d("SettingsActivity", "Updated categories: $currentCategories")
    }
    
    private fun updatecategoriesVisibility(enabled: Boolean) {
        categories.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
    }
    
    private fun loadSettings() {
        val questionsEnabled = choice.getBoolean("questions_enabled", true)
        toggle.isChecked = questionsEnabled
        updatecategoriesVisibility(questionsEnabled)
        val enabledCategories = questionManager.getEnabledCategories()
        for ((categoryId, checkBox) in categoryCheckboxes) {
            checkBox.isChecked = categoryId in enabledCategories
        }
        notifiToggle.isChecked = choice.getBoolean("notifications_enabled", true)
        toggleeee.isChecked = choice.getBoolean("encouragement_enabled", true)
        reminder.setSelection(choice.getInt("reminder_frequency", 2))
        aiValid.isChecked = choice.getBoolean("ai_validation_enabled", true)
        strictness.setSelection(choice.getInt("ai_strictness", 1))
    }
    
    private fun saveSettings() {
        choice.edit().apply {
            putBoolean("questions_enabled", toggle.isChecked)
            putBoolean("notifications_enabled", notifiToggle.isChecked)
            putBoolean("encouragement_enabled", toggleeee.isChecked)
            putInt("reminder_frequency", reminder.selectedItemPosition)
            putBoolean("ai_validation_enabled", aiValid.isChecked)
            putInt("ai_strictness", strictness.selectedItemPosition)
            apply()
        }
        
        Log.d("SettingsActivity", "Settings saved")
    }
}
