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
import com.alaotach.limini.services.AIValidationService
import kotlinx.coroutines.*
import android.content.pm.ServiceInfo
import android.app.usage.UsageStatsManager

class OverlayService : Service() {

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
    private var questionText: TextView? = null
    private var answerOptions: RadioGroup? = null
    private var reasonInput: EditText? = null
    private var submitButton: Button? = null
    private var statusMessage: TextView? = null
    private var appNameText: TextView? = null
    private var categoryBadge: TextView? = null
    
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.alaotach.limini.DISMISS_OVERLAY" -> {
                    cleanupAndStop()
                }
                "com.alaotach.limini.EXTENSION_APPLIED" -> {
                    val packageName = intent.getStringExtra("packageName")
                    val extensionMinutes = intent.getIntExtra("extensionMinutes", 0)
                    if (packageName == blockedPackageName) {
                        showStatus("Extension applied successfully! Enjoy your extra time!", isError = false)
                        handler.postDelayed({
                            cleanupAndStop()
                        }, 1000)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "OverlayService"
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
        val filter = IntentFilter().apply {
            addAction("com.alaotach.limini.DISMISS_OVERLAY")
            addAction("com.alaotach.limini.EXTENSION_APPLIED")
        }
        registerReceiver(dismissReceiver, filter, flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {        
        if (isOverlayShowing) {
            Log.d(TAG, "⚠️ Overlay already showing, ignoring request")
            return START_NOT_STICKY
        }

        appName = intent?.getStringExtra("appName") ?: "Unknown App"
        val timeLimitMinutes = intent?.getIntExtra("timeLimitMinutes", 60) ?: 60
        blockedPackageName = intent?.getStringExtra("blockedPackageName")

        if (blockedPackageName == null) {
            cleanupAndStop()
            return START_NOT_STICKY
        }

        // Log.d(TAG, "Blocking $appName")
        val sharedPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val questionsEnabled = sharedPrefs.getBoolean("questions_enabled", true)        
        if (!questionsEnabled) {
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
            Log.e(TAG, "Err: ${e.message}", e)
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
            return
        }

        val question = currentQuestion!!
        questionText?.text = question.question
        appNameText?.text = "Extend time for: $appName"
        
        val category = questionManager.getCategoryById(question.category.id)
        categoryBadge?.text = "${category?.icon} ${category?.name}"
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
            showStatus("Please provide a more detailed reason (at least 10 chars)", isError = true)
            return
        }
        submitButton?.isEnabled = false
        showStatus("Checking answer...", isError = false)
        val isCorrect = questionManager.validateAnswer(question, userAnswer)
        
        if (!isCorrect) {
            submitButton?.isEnabled = true
            showStatus("Incorrect answer. Try again!", isError = true)
            handler.postDelayed({
                setupQuestion()
                reasonInput?.setText("")
            }, 2000)
            return
        }
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
                Log.e(TAG, "Err: ${e.message}", e)
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
                grantExtension(result.suggestedTimeMinutes)
                handler.postDelayed({
                    cleanupAndStop()
                }, 1500)
            } else {
                submitButton?.isEnabled = true
                showStatus("Reason not sufficient: ${result.feedback}", isError = true)
                reasonInput?.setText("")
            }
        }
    }

    private fun grantExtension(minutes: Int) {
        val intent = Intent("com.alaotach.limini.EXTEND_TIME_LIMIT").apply {
            putExtra("packageName", blockedPackageName)
            putExtra("extensionMinutes", minutes)
            setPackage(packageName)
        }
        
        sendBroadcast(intent)
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
        if (isOverlayShowing || isServiceDestroying) {
            return
        }

        try {
            isOverlayShowing = true
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_simple_block, null)

            val appNameText = overlayView?.findViewById<TextView>(R.id.blockedAppName)
            val messageText = overlayView?.findViewById<TextView>(R.id.blockMessage)
            val homeButton = overlayView?.findViewById<Button>(R.id.goHomeButton)
            val settingsButton = overlayView?.findViewById<Button>(R.id.settingsButton)
            
            appNameText?.text = appName
            messageText?.text = "Time limit reached for $appName\nTake a break and return later!"
            
            homeButton?.setOnClickListener {
                goHome()
                cleanupAndStop()
            }
            
            settingsButton?.setOnClickListener {
                val settingsIntent = Intent(this, com.alaotach.limini.SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(settingsIntent)
                cleanupAndStop()
            }
            
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
            startSimpleMonitoring()
            
        } catch (e: Exception) {
            isOverlayShowing = false
            cleanupAndStop()
        }
    }
    
    private fun startSimpleMonitoring() {
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
                        cleanupAndStop()
                        return
                    }
                    handler.postDelayed(this, 2000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in simple monitoring: ${e.message}")
                    handler.postDelayed(this, 3000)
                }
            }
        }
        handler.postDelayed(checkRunnable!!, 2000)
    }

    private fun startMonitoringCurrentApp() {
        if (blockedPackageName.isNullOrEmpty() || isServiceDestroying) {
            return
        }
        val sharedPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val regenerateOnSwitch = sharedPrefs.getBoolean("regenerate_question_on_switch", false) // Disabled by default

        if (!regenerateOnSwitch) {
            Log.d(TAG, "Question regeneration on app switch is disabled - skipping monitoring")
            return
        }

        var consecutiveSwitchCount = 0
        var lastDetectedApp: String? = null

        checkRunnable = object : Runnable {
            override fun run() {
                if (isServiceDestroying) {
                    return
                }

                try {
                    val currentApp = getCurrentForegroundApp()
                    Log.d(TAG, "Monitoring check - Current app: '$currentApp', Blocked: '$blockedPackageName', Last: '$lastDetectedApp'")
                    if (currentApp != null && currentApp != blockedPackageName && 
                        currentApp != "com.alaotach.limini" && currentApp != "HOME_SCREEN") {
                        
                        if (currentApp == lastDetectedApp) {
                            consecutiveSwitchCount++
                        } else {
                            consecutiveSwitchCount = 1
                            lastDetectedApp = currentApp
                        }
                        if (consecutiveSwitchCount >= 2 && currentQuestion != null) {
                            setupQuestion()
                            reasonInput?.setText("")
                            showStatus("New question (genuine app switch)", isError = false)
                            handler.postDelayed({
                                statusMessage?.visibility = View.GONE
                            }, 3000)
                            consecutiveSwitchCount = 0
                        }
                    } else {
                        consecutiveSwitchCount = 0
                        lastDetectedApp = currentApp
                    }
                    
                    handler.postDelayed(this, 5000)
                } catch (e: Exception) {
                    handler.postDelayed(this, 5000)
                }
            }
        }
        handler.postDelayed(checkRunnable!!, 5000)
    }

    private fun getCurrentForegroundApp(): String? {
        return try {
            val currentApp = getCurrentAppFromUsageStats()
            
            if (currentApp == null) {
                return null
            }
            
            if (isLauncherApp(currentApp)) {
                return "HOME_SCREEN"
            }

            if (currentApp == "com.alaotach.limini") {
                return currentApp
            }

            Log.d(TAG, "Current app: '$currentApp'")
            currentApp
        } catch (e: Exception) {
            Log.e(TAG, "Err: ${e.message}")
            null
        }
    }

    private fun getCurrentAppFromUsageStats(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val start = now - 5000L

            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                start,
                now
            )
            val recentApps = usageStats?.filter { stat ->
                stat.lastTimeUsed > 0 &&
                stat.totalTimeInForeground > 0 &&
                !isSystemApp(stat.packageName) &&
                !(stat.packageName == "com.alaotach.limini" && (now - stat.lastTimeUsed) < 3000) &&
                stat.totalTimeInForeground > 100 &&
                (now - stat.lastTimeUsed) < 4000
            }?.sortedByDescending { it.lastTimeUsed }

            val recentApp = recentApps?.firstOrNull()?.packageName

            if (recentApp != null) {
                Log.d(TAG, "Usage stats app: $recentApp (from ${recentApps?.size ?: 0} candidates)")
            } else {
                Log.d(TAG, "UsageStats: No foreground app")
            }

            recentApp
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app from usage stats: ${e.message}")
            null
        }
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val packageManager = packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            
            if (isUpdatedSystemApp) {
                return false
            }

            val isCoreSystemApp = packageName.startsWith("android.") ||
                                 packageName.startsWith("com.android.") ||
                                 packageName == "com.google.android.gms" ||
                                 packageName == "com.google.android.gsf" ||
                                 packageName.startsWith("com.google.android.tts") ||
                                 packageName.startsWith("com.google.android.inputmethod")

            isSystemApp && isCoreSystemApp
        } catch (e: Exception) {
            Log.e(TAG, "Err: ${e.message}")
            false
        }
    }

    private fun isLauncherApp(packageName: String): Boolean {
        return try {
            val packageManager = packageManager

            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)

            val launcherApps = packageManager.queryIntentActivities(intent, 0)
            val isLauncher = launcherApps.any { it.activityInfo.packageName == packageName }

            val isCommonLauncher = packageName.contains("launcher", ignoreCase = true) ||
                                 packageName.contains("home", ignoreCase = true) ||
                                 packageName == "com.android.launcher" ||
                                 packageName == "com.android.launcher2" ||
                                 packageName == "com.android.launcher3" ||
                                 packageName.startsWith("com.google.android.apps.nexuslauncher") ||
                                 packageName.startsWith("com.google.android.launcher") ||
                                 packageName == "com.google.android.apps.pixel.launcher" ||
                                 packageName.startsWith("com.samsung.android.app.launcher") ||
                                 packageName.startsWith("com.huawei.android.launcher") ||
                                 packageName.startsWith("com.miui.home") ||
                                 packageName.startsWith("com.oneplus.launcher") ||
                                 packageName.startsWith("com.oppo.launcher") ||
                                 packageName.startsWith("com.vivo.launcher") ||
                                 packageName.contains("trebuchet") ||
                                 packageName.contains("pixel.launcher") ||
                                 packageName.endsWith(".home") ||
                                 packageName.endsWith(".launcher")

            val result = isLauncher || isCommonLauncher
            if (result) {
                Log.d(TAG, "current app: $packageName")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Err: ${e.message}")
            false
        }
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
            Log.e(TAG, "Err: ${e.message}", e)
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
            Log.w(TAG, "Err: ${e.message}")
        }
        cleanupAndStop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
