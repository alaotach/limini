package com.alaotach.limini.services

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.alaotach.limini.R
import com.alaotach.limini.data.*
import com.alaotach.limini.utils.QuestionManager
import kotlinx.coroutines.*
import android.content.pm.ServiceInfo

class QuestionOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var blockedPackageName: String? = null
    private var appName: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null
    private var isOverlayShowing = false
    private var isServiceDestroying = false
    private var currentQuestion: Question? = null
    
    private lateinit var questionManager: QuestionManager
    private lateinit var aiValidationService: AIValidationService
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // UI Components
    private var questionText: TextView? = null
    private var answerOptions: RadioGroup? = null
    private var reasonInput: EditText? = null
    private var submitButton: Button? = null
    private var statusMessage: TextView? = null
    private var appNameText: TextView? = null
    private var categoryBadge: TextView? = null
    
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.alaotach.limini.DISMISS_OVERLAY") {
                cleanupAndStop()
            }
        }
    }

    companion object {
        private const val TAG = "QuestionOverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "question_overlay_channel"
        private const val NOTIFICATION_ID = 3
    }

    override fun onCreate() {
        super.onCreate()
        questionManager = QuestionManager(this)
        aiValidationService = AIValidationService(this)
        createNotificationChannel()
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(dismissReceiver, IntentFilter("com.alaotach.limini.DISMISS_OVERLAY"), flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isOverlayShowing) {
            return START_NOT_STICKY
        }

        appName = intent?.getStringExtra("appName") ?: "Unknown App"
        val timeLimitMinutes = intent?.getIntExtra("timeLimitMinutes", 60) ?: 60
        blockedPackageName = intent?.getStringExtra("blockedPackageName")

        if (blockedPackageName == null) {
            cleanupAndStop()
            return START_NOT_STICKY
        }

        // Check if questions are enabled
        val sharedPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val questionsEnabled = sharedPrefs.getBoolean("questions_enabled", true)
        
        if (!questionsEnabled) {
            // Fall back to simple blocking
            showSimpleBlock()
        } else {
            showQuestionChallenge()
        }

        val notification = createNotification(appName!!)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Question Challenge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Question challenge for app time limits"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(appName: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Question Challenge Active")
            .setContentText("Answer question to extend $appName usage")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showQuestionChallenge() {
        if (isOverlayShowing || isServiceDestroying) {
            return
        }

        try {
            isOverlayShowing = true
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_question, null)
            
            initializeViews()
            setupQuestion()
            setupListeners()
            
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )

            windowManager?.addView(overlayView, params)
            startMonitoringCurrentApp()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing question overlay: ${e.message}", e)
            isOverlayShowing = false
            cleanupAndStop()
        }
    }

    private fun initializeViews() {
        questionText = overlayView?.findViewById(R.id.questionText)
        answerOptions = overlayView?.findViewById(R.id.answerOptions)
        reasonInput = overlayView?.findViewById(R.id.reasonInput)
        submitButton = overlayView?.findViewById(R.id.submitAnswer)
        statusMessage = overlayView?.findViewById(R.id.statusMessage)
        appNameText = overlayView?.findViewById(R.id.appNameText)
        categoryBadge = overlayView?.findViewById(R.id.categoryBadge)
    }

    private fun setupQuestion() {
        currentQuestion = questionManager.getRandomQuestion()
        
        if (currentQuestion == null) {
            showError("No questions available. Please check settings.")
            return
        }

        val question = currentQuestion!!
        
        // Update UI with question
        questionText?.text = question.question
        appNameText?.text = "Extend time for: $appName"
        
        val category = questionManager.getCategoryById(question.category.id)
        categoryBadge?.text = "${category?.icon} ${category?.name}"
        
        // Setup answer options
        answerOptions?.removeAllViews()
        question.options?.forEachIndexed { index, option ->
            val radioButton = RadioButton(this).apply {
                text = option
                textSize = 14f
                setPadding(16, 12, 16, 12)
                id = View.generateViewId()
            }
            answerOptions?.addView(radioButton)
        }
    }

    private fun setupListeners() {
        submitButton?.setOnClickListener {
            handleAnswerSubmission()
        }
        
        overlayView?.findViewById<Button>(R.id.skipButton)?.setOnClickListener {
            goHome()
            cleanupAndStop()
        }
        
        overlayView?.findViewById<Button>(R.id.settingsButton)?.setOnClickListener {
            openQuestionSettings()
        }
    }

    private fun handleAnswerSubmission() {
        val question = currentQuestion ?: return
        
        // Get selected answer
        val selectedId = answerOptions?.checkedRadioButtonId ?: -1
        if (selectedId == -1) {
            showStatus("Please select an answer", isError = true)
            return
        }
        
        val selectedButton = overlayView?.findViewById<RadioButton>(selectedId)
        val userAnswer = selectedButton?.text?.toString() ?: ""
        val reason = reasonInput?.text?.toString()?.trim() ?: ""
        
        if (reason.isEmpty()) {
            showStatus("Please provide a reason", isError = true)
            return
        }
        
        if (reason.length < 10) {
            showStatus("Please provide a more detailed reason (at least 10 characters)", isError = true)
            return
        }
        
        // Disable submit button during processing
        submitButton?.isEnabled = false
        showStatus("Checking answer...", isError = false)
        
        // Validate answer
        val isCorrect = questionManager.validateAnswer(question, userAnswer)
        
        if (!isCorrect) {
            submitButton?.isEnabled = true
            showStatus("Incorrect answer. Try again!", isError = true)
            
            // Generate new question to prevent cheating
            handler.postDelayed({
                setupQuestion()
                reasonInput?.setText("")
            }, 2000)
            return
        }
        
        // Answer is correct, now validate reason with AI
        showStatus("Answer correct! Validating reason...", isError = false)
        
        val questionResponse = QuestionResponse(
            questionId = question.id,
            userAnswer = userAnswer,
            reason = reason,
            isCorrect = true
        )
        
        val extensionRequest = ExtensionRequest(
            packageName = blockedPackageName!!,
            appName = appName!!,
            questionResponse = questionResponse,
            requestedMinutes = 5
        )
        
        serviceScope.launch {
            try {
                val validationResult = aiValidationService.validateExtensionRequest(extensionRequest)
                handleValidationResult(validationResult)
            } catch (e: Exception) {
                Log.e(TAG, "Error validating reason", e)
                // Fallback validation
                val fallbackResult = ValidationResult(
                    approved = reason.length > 20,
                    confidence = 0.5,
                    feedback = "Fallback validation",
                    suggestedTimeMinutes = 3
                )
                handleValidationResult(fallbackResult)
            }
        }
    }

    private fun handleValidationResult(result: ValidationResult) {
        handler.post {
            if (result.approved) {
                showStatus("Extension approved! You have ${result.suggestedTimeMinutes} extra minutes.", isError = false)
                
                // Grant extension time
                grantExtension(result.suggestedTimeMinutes)
                
                handler.postDelayed({
                    cleanupAndStop()
                }, 2000)
            } else {
                submitButton?.isEnabled = true
                showStatus("Reason not sufficient: ${result.feedback}", isError = true)
                
                // Clear reason field for retry
                reasonInput?.setText("")
            }
        }
    }

    private fun grantExtension(minutes: Int) {
        // Send broadcast to extend time limit
        val intent = Intent("com.alaotach.limini.EXTEND_TIME_LIMIT").apply {
            putExtra("packageName", blockedPackageName)
            putExtra("extensionMinutes", minutes)
        }
        sendBroadcast(intent)
        
        Log.d(TAG, "Granted $minutes minute extension for $blockedPackageName")
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusMessage?.text = message
        statusMessage?.visibility = View.VISIBLE
        statusMessage?.setTextColor(
            if (isError) getColor(android.R.color.holo_red_light)
            else getColor(android.R.color.white)
        )
    }

    private fun showError(message: String) {
        statusMessage?.text = message
        statusMessage?.visibility = View.VISIBLE
        statusMessage?.setTextColor(getColor(android.R.color.holo_red_light))
    }

    private fun showSimpleBlock() {
        // Fallback to simple blocking overlay if questions are disabled
        // This would use the original blocking layout
        cleanupAndStop()
    }

    private fun startMonitoringCurrentApp() {
        if (blockedPackageName.isNullOrEmpty() || isServiceDestroying) {
            return
        }

        checkRunnable = object : Runnable {
            override fun run() {
                if (isServiceDestroying) {
                    return
                }

                try {
                    val currentApp = getCurrentForegroundApp()
                    if (currentApp != blockedPackageName && currentApp != "com.alaotach.limini") {
                        // User switched apps, generate new question
                        if (currentQuestion != null) {
                            setupQuestion()
                            reasonInput?.setText("")
                            showStatus("New question generated (app switch detected)", isError = false)
                            handler.postDelayed({
                                statusMessage?.visibility = View.GONE
                            }, 3000)
                        }
                    }
                    handler.postDelayed(this, 3000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring current app: ${e.message}")
                    handler.postDelayed(this, 5000)
                }
            }
        }
        handler.postDelayed(checkRunnable!!, 3000)
    }

    private fun getCurrentForegroundApp(): String? {
        // Implementation similar to original OverlayService
        // ... (reuse the getCurrentForegroundApp logic)
        return null // Simplified for now
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    private fun openQuestionSettings() {
        val settingsIntent = Intent(this, com.alaotach.limini.SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(settingsIntent)
    }

    private fun cleanupAndStop() {
        isServiceDestroying = true
        isOverlayShowing = false
        
        checkRunnable?.let { handler.removeCallbacks(it) }
        
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay view", e)
        }
        
        overlayView = null
        windowManager = null
        
        serviceScope.cancel()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(dismissReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
        cleanupAndStop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
