package com.alaotach.limini.services

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.BroadcastReceiver
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.content.ComponentName
import com.alaotach.limini.MainActivity
import com.alaotach.limini.R

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var blockedPackageName: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null
    private var isOverlayShowing = false
    private var overlayStartTime = 0L
    private var isServiceDestroying = false
    private var lastDetectedApp: String? = null
    private var consecutiveHomeDetections = 0
    private var stableAppDetections = 0 
    private var userDismissed = false
    
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.alaotach.limini.DISMISS_OVERLAY" -> {
                    android.util.Log.d("OverlayService", "📡 Received manual dismiss broadcast")
                    userDismissed = true
                    cleanupAndStop()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("OverlayService", "🚀 Overlay service created")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(dismissReceiver, IntentFilter("com.alaotach.limini.DISMISS_OVERLAY"), flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isOverlayShowing) {
            android.util.Log.d("OverlayService", "🔒 Overlay already showing - ignoring duplicate start command")
            return START_NOT_STICKY
        }

        val appName = intent?.getStringExtra("appName") ?: "Unknown App"
        val timeLimitMinutes = intent?.getIntExtra("timeLimitMinutes", 60) ?: 60
        blockedPackageName = intent?.getStringExtra("blockedPackageName")
        userDismissed = false

        android.util.Log.d("OverlayService", "🔒 Creating overlay for $appName (blocked: $blockedPackageName)")

        showOverlay(appName, timeLimitMinutes)
        startMonitoringCurrentApp()

        return START_NOT_STICKY
    }

    private fun showOverlay(appName: String, timeLimitMinutes: Int) {
        if (isOverlayShowing || isServiceDestroying) {
            android.util.Log.d("OverlayService", "🔒 Overlay already showing or service destroying - skipping")
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayView = LayoutInflater.from(this).inflate(R.layout.activity_lock, null)
            
            overlayView?.findViewById<TextView>(R.id.lockTitle)?.text = "⏰ Time Limit Reached"
            overlayView?.findViewById<TextView>(R.id.lockMessage)?.text =
                "$appName has exceeded the $timeLimitMinutes minute limit.\n\nTake a break and come back later!"
            
            overlayView?.findViewById<Button>(R.id.backToHomeButton)?.setOnClickListener {
                android.util.Log.d("OverlayService", "🏠 Go Home button clicked - user dismissed")
                userDismissed = true
                try {
                    goHome()
                    cleanupAndStop()
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Error going home: ${e.message}")
                    cleanupAndStop()
                }
            }

            // Limini button click handler
            overlayView?.findViewById<Button>(R.id.openLiminiButton)?.setOnClickListener {
                android.util.Log.d("OverlayService", "📱 Open Limini button clicked - user dismissed")
                userDismissed = true
                try {
                    openLimini()
                    cleanupAndStop()
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Error opening Limini: ${e.message}")
                    cleanupAndStop()
                }
            }

            // Set up window layout parameters - IMPROVED FLAGS
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
                // FIXED: Removed conflicting flags that cause flickering
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                // Removed FLAG_FULLSCREEN as it can cause conflicts
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0

            windowManager?.addView(overlayView, params)
            isOverlayShowing = true
            overlayStartTime = System.currentTimeMillis()

            android.util.Log.d("OverlayService", "🔒 Overlay shown successfully")

            // Enable touch after a short delay with better error handling
            overlayView?.postDelayed({
                try {
                    if (!isServiceDestroying && overlayView != null && windowManager != null) {
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                        windowManager?.updateViewLayout(overlayView, params)
                        android.util.Log.d("OverlayService", "✅ Overlay touch enabled")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Error enabling touch: ${e.message}")
                }
            }, 500) // Increased delay for better stability

        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "❌ Error creating overlay: ${e.message}", e)
            isOverlayShowing = false
            fallbackToActivity(appName, timeLimitMinutes)
        }
    }

    private fun startMonitoringCurrentApp() {
        if (blockedPackageName.isNullOrEmpty() || isServiceDestroying) {
            android.util.Log.d("OverlayService", "No blocked package or service destroying, skipping monitoring")
            return
        }

        checkRunnable = object : Runnable {
            override fun run() {
                if (isServiceDestroying || userDismissed) {
                    android.util.Log.d("OverlayService", "Service destroying or user dismissed, stopping monitoring")
                    return
                }

                try {
                    val currentApp = getCurrentForegroundApp()
                    val overlayAge = System.currentTimeMillis() - overlayStartTime

                    // Auto-dismiss after 3 minutes for safety (increased from 2)
                    if (overlayAge > 180000) {
                        android.util.Log.d("OverlayService", "⏰ Overlay timeout after ${overlayAge}ms - auto-dismissing")
                        cleanupAndStop()
                        return
                    }

                    android.util.Log.d("OverlayService", "🔍 Current app: '$currentApp', Blocked: '$blockedPackageName', Stable: $stableAppDetections")

                    val shouldDismiss = when {
                        currentApp == "HOME_SCREEN" -> {
                            consecutiveHomeDetections++
                            stableAppDetections = 0 // Reset stable counter
                            // INCREASED threshold for more stability
                            if (consecutiveHomeDetections >= 5) {
                                android.util.Log.d("OverlayService", "🏠 HOME_SCREEN confirmed ($consecutiveHomeDetections detections) - dismissing")
                                true
                            } else {
                                android.util.Log.d("OverlayService", "🏠 HOME_SCREEN detected (${consecutiveHomeDetections}/5) - waiting for confirmation")
                                false
                            }
                        }
                        currentApp != null && 
                        currentApp != blockedPackageName && 
                        currentApp != "com.alaotach.limini" -> {
                            // NEW: Require stable detection of new app
                            if (currentApp == lastDetectedApp) {
                                stableAppDetections++
                            } else {
                                stableAppDetections = 1
                            }
                            consecutiveHomeDetections = 0
                            
                            // Require 3 stable detections of the same non-blocked app
                            if (stableAppDetections >= 3) {
                                android.util.Log.d("OverlayService", "🔄 App switch to '$currentApp' confirmed ($stableAppDetections detections) - dismissing")
                                true
                            } else {
                                android.util.Log.d("OverlayService", "🔄 App switch to '$currentApp' (${stableAppDetections}/3) - waiting for confirmation")
                                false
                            }
                        }
                        else -> {
                            // Reset counters if we're back to blocked app or in limbo
                            if (currentApp == blockedPackageName) {
                                consecutiveHomeDetections = 0
                                stableAppDetections = 0
                                android.util.Log.d("OverlayService", "⏰ Still in blocked app '$currentApp' - overlay remains")
                            } else if (currentApp == "com.alaotach.limini") {
                                // Don't change counters for Limini
                                android.util.Log.d("OverlayService", "📱 Limini detected - maintaining current state")
                            }
                            false
                        }
                    }

                    lastDetectedApp = currentApp

                    if (shouldDismiss) {
                        android.util.Log.d("OverlayService", "🛑 Dismissing overlay after stable detection")
                        cleanupAndStop()
                        return
                    }

                    // INCREASED interval for better stability
                    handler.postDelayed(this, 2000) // 2 seconds instead of 1.5
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "❌ Error in monitoring: ${e.message}")
                    // Don't immediately stop on error - give it another chance
                    handler.postDelayed(this, 3000)
                }
            }
        }
        // INCREASED initial delay
        handler.postDelayed(checkRunnable!!, 3000) // Start after 3 seconds instead of 2
    }

    private fun getCurrentForegroundApp(): String? {
        return try {
            val currentApp = getCurrentAppFromUsageStats()
            
            // IMPROVED: Better null handling
            if (currentApp == null) {
                android.util.Log.d("OverlayService", "❓ No app detected - maintaining current state")
                // Don't assume blocked app is running, return null to avoid false positives
                return null
            }
            
            if (isLauncherApp(currentApp)) {
                android.util.Log.d("OverlayService", "🏠 Launcher detected: '$currentApp' -> HOME_SCREEN")
                return "HOME_SCREEN"
            }

            if (currentApp == "com.alaotach.limini") {
                android.util.Log.d("OverlayService", "📱 Limini app detected")
                return currentApp
            }

            android.util.Log.d("OverlayService", "✅ Detected app: '$currentApp'")
            currentApp
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "❌ Error getting current app: ${e.message}")
            null
        }
    }

    private fun getCurrentAppFromUsageStats(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val start = now - 3000L

            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                start,
                now
            )

            val recentApp = usageStats?.filter { stat ->
                stat.lastTimeUsed > 0 &&
                stat.totalTimeInForeground > 0 &&
                !isSystemApp(stat.packageName) &&
                !(stat.packageName == "com.alaotach.limini" && (now - stat.lastTimeUsed) < 1000)
            }?.maxByOrNull { stat ->
                stat.lastTimeUsed
            }?.packageName

            if (recentApp != null) {
                android.util.Log.d("OverlayService", "UsageStats found app: $recentApp")
            } else {
                android.util.Log.d("OverlayService", "UsageStats: No valid foreground app found")
            }

            recentApp
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error with UsageStats: ${e.message}")
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
            android.util.Log.e("OverlayService", "Error checking system app: ${e.message}")
            false
        }
    }

    private fun isLauncherApp(packageName: String): Boolean {
        return try {
            val packageManager = packageManager

            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            intent.addCategory(android.content.Intent.CATEGORY_HOME)

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
                android.util.Log.d("OverlayService", "Identified launcher app: $packageName")
            }

            result
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error checking launcher app: ${e.message}")
            false
        }
    }

    private fun fallbackToActivity(appName: String, timeLimitMinutes: Int) {
        android.util.Log.w("OverlayService", "⚠️ Falling back to regular activity")
        val lockIntent = Intent(this, com.alaotach.limini.LockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("appName", appName)
            putExtra("timeLimitMinutes", timeLimitMinutes)
        }
        startActivity(lockIntent)
        cleanupAndStop()
    }

    private fun removeOverlay() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                android.util.Log.d("OverlayService", "🗑️ Overlay removed")
                
                if (!blockedPackageName.isNullOrEmpty() && userDismissed) {
                    val dismissIntent = Intent("com.alaotach.limini.OVERLAY_DISMISSED").apply {
                        putExtra("packageName", blockedPackageName)
                    }
                    sendBroadcast(dismissIntent)
                    android.util.Log.d("OverlayService", "📡 Sent overlay dismissed broadcast for $blockedPackageName")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error removing overlay: ${e.message}")
        } finally {
            overlayView = null
            windowManager = null
            isOverlayShowing = false
        }
    }

    private fun cleanupAndStop() {
        if (isServiceDestroying) {
            android.util.Log.d("OverlayService", "Already cleaning up, ignoring duplicate call")
            return
        }
        
        isServiceDestroying = true
        
        checkRunnable?.let { 
            handler.removeCallbacks(it)
            android.util.Log.d("OverlayService", "🛑 Stopped monitoring")
        }
        checkRunnable = null
        
        removeOverlay()
        
        stopSelf()
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    private fun openLimini() {
        val liminiIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(liminiIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        
        if (!isServiceDestroying) {
            isServiceDestroying = true
        }
        
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = null
        
        try {
            unregisterReceiver(dismissReceiver)
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error unregistering receiver: ${e.message}")
        }

        removeOverlay()
        
        android.util.Log.d("OverlayService", "🛑 Overlay service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}