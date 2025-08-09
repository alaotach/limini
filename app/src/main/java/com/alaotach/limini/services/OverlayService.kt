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
    
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.alaotach.limini.DISMISS_OVERLAY" -> {
                    android.util.Log.d("OverlayService", "📡 Received manual dismiss broadcast")
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
        // Prevent multiple instances
        if (isOverlayShowing) {
            android.util.Log.d("OverlayService", "🔒 Overlay already showing - ignoring duplicate start command")
            return START_NOT_STICKY
        }

        val appName = intent?.getStringExtra("appName") ?: "Unknown App"
        val timeLimitMinutes = intent?.getIntExtra("timeLimitMinutes", 60) ?: 60
        blockedPackageName = intent?.getStringExtra("blockedPackageName")

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
            
            // Set up the overlay content
            overlayView?.findViewById<TextView>(R.id.lockTitle)?.text = "⏰ Time Limit Reached"
            overlayView?.findViewById<TextView>(R.id.lockMessage)?.text =
                "$appName has exceeded the $timeLimitMinutes minute limit.\n\nTake a break and come back later!"
            
            // Home button click handler
            overlayView?.findViewById<Button>(R.id.backToHomeButton)?.setOnClickListener {
                android.util.Log.d("OverlayService", "🏠 Go Home button clicked - force dismissing")
                try {
                    goHome()
                    // Force immediate cleanup when home button is clicked
                    cleanupAndStop()
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Error going home: ${e.message}")
                    cleanupAndStop()
                }
            }

            // Limini button click handler
            overlayView?.findViewById<Button>(R.id.openLiminiButton)?.setOnClickListener {
                android.util.Log.d("OverlayService", "📱 Open Limini button clicked - force dismissing")
                try {
                    openLimini()
                    // Force immediate cleanup when Limini button is clicked
                    cleanupAndStop()
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Error opening Limini: ${e.message}")
                    cleanupAndStop()
                }
            }

            // Set up window layout parameters
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0

            windowManager?.addView(overlayView, params)
            isOverlayShowing = true
            overlayStartTime = System.currentTimeMillis()

            android.util.Log.d("OverlayService", "🔒 Overlay shown successfully")

            // Enable touch after a short delay to prevent flickering
            overlayView?.postDelayed({
                try {
                    if (!isServiceDestroying && overlayView != null) {
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                        windowManager?.updateViewLayout(overlayView, params)
                        android.util.Log.d("OverlayService", "✅ Overlay touch enabled")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Error enabling touch: ${e.message}")
                }
            }, 200)

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
                if (isServiceDestroying) {
                    android.util.Log.d("OverlayService", "Service destroying, stopping monitoring")
                    return
                }

                try {
                    val currentApp = getCurrentForegroundApp()
                    val overlayAge = System.currentTimeMillis() - overlayStartTime

                    // Auto-dismiss after 2 minutes for safety
                    if (overlayAge > 120000) {
                        android.util.Log.d("OverlayService", "⏰ Overlay timeout after ${overlayAge}ms - auto-dismissing")
                        cleanupAndStop()
                        return
                    }

                    android.util.Log.d("OverlayService", "🔍 Current app: '$currentApp', Blocked: '$blockedPackageName', Last: '$lastDetectedApp'")

                    val shouldDismiss = when {
                        currentApp == "HOME_SCREEN" -> {
                            consecutiveHomeDetections++
                            if (consecutiveHomeDetections >= 3) {
                                android.util.Log.d("OverlayService", "🏠 HOME_SCREEN confirmed ($consecutiveHomeDetections detections) - dismissing")
                                true
                            } else {
                                android.util.Log.d("OverlayService", "🏠 HOME_SCREEN detected (${consecutiveHomeDetections}/3) - waiting for confirmation")
                                false
                            }
                        }
                        currentApp != null && 
                        currentApp != blockedPackageName && 
                        currentApp != "com.alaotach.limini" &&
                        currentApp != lastDetectedApp -> {
                            consecutiveHomeDetections = 0
                            android.util.Log.d("OverlayService", "🔄 App switched to '$currentApp' - dismissing")
                            true
                        }
                        else -> {
                            // Reset counter if we're back to blocked app or Limini
                            if (currentApp == blockedPackageName) {
                                consecutiveHomeDetections = 0
                                android.util.Log.d("OverlayService", "⏰ Still in blocked app '$currentApp' - overlay remains")
                            } else if (currentApp == "com.alaotach.limini") {
                                // Don't count Limini detections toward dismissal unless confirmed
                                android.util.Log.d("OverlayService", "📱 Limini detected - not counting toward dismissal")
                            }
                            false
                        }
                    }

                    lastDetectedApp = currentApp

                    if (shouldDismiss) {
                        android.util.Log.d("OverlayService", "🛑 Dismissing overlay")
                        cleanupAndStop()
                        return
                    }

                    // Schedule next check
                    handler.postDelayed(this, 1500) // Increased to 1.5 seconds for better stability
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "❌ Error in monitoring: ${e.message}")
                    cleanupAndStop()
                }
            }
        }
        handler.postDelayed(checkRunnable!!, 2000) // Start after 2 seconds
    }

    private fun getCurrentForegroundApp(): String? {
        return try {
            val currentApp = getCurrentAppFromUsageStats()
            
            // If we can't detect any app, return the blocked app (assume it's still running)
            if (currentApp == null) {
                android.util.Log.d("OverlayService", "❓ No app detected - assuming blocked app is still running")
                return blockedPackageName
            }
            
            if (isLauncherApp(currentApp)) {
                android.util.Log.d("OverlayService", "🏠 Launcher detected: '$currentApp' -> HOME_SCREEN")
                return "HOME_SCREEN"
            }

            // Don't treat Limini detection as immediate home screen unless we're sure
            if (currentApp == "com.alaotach.limini") {
                android.util.Log.d("OverlayService", "📱 Limini app detected - checking if overlay was manually dismissed")
                // Only treat as home if user clicked a button (we'll handle this in button clicks)
                return currentApp
            }

            android.util.Log.d("OverlayService", "✅ Detected app: '$currentApp'")
            currentApp
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "❌ Error getting current app: ${e.message}")
            // On error, assume blocked app is still running to prevent premature dismissal
            blockedPackageName
        }
    }

    private fun getCurrentAppFromUsageStats(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val start = now - 5000L // Look back 5 seconds for better detection

            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                start,
                now
            )

            // Find the most recently used app, excluding very recent Limini activity
            val recentApp = usageStats?.filter { stat ->
                stat.lastTimeUsed > 0 &&
                stat.totalTimeInForeground > 0 &&
                !isSystemApp(stat.packageName) &&
                // Only ignore Limini if it was accessed very recently (likely overlay interaction)
                !(stat.packageName == "com.alaotach.limini" && (now - stat.lastTimeUsed) < 2000)
            }?.maxByOrNull { stat ->
                maxOf(
                    stat.lastTimeUsed,
                    stat.lastTimeVisible.takeIf { it > 0 } ?: 0,
                    stat.lastTimeForegroundServiceUsed.takeIf { it > 0 } ?: 0
                )
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
            
            // Don't treat updated system apps as system apps
            if (isUpdatedSystemApp) {
                return false
            }

            // Only consider core system components as system apps
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

            // Check if app handles HOME intent
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            intent.addCategory(android.content.Intent.CATEGORY_HOME)

            val launcherApps = packageManager.queryIntentActivities(intent, 0)
            val isLauncher = launcherApps.any { it.activityInfo.packageName == packageName }

            // Check common launcher package names
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
                
                // Send broadcast that overlay was dismissed
                if (!blockedPackageName.isNullOrEmpty()) {
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
        
        // Stop monitoring
        checkRunnable?.let { 
            handler.removeCallbacks(it)
            android.util.Log.d("OverlayService", "🛑 Stopped monitoring")
        }
        checkRunnable = null
        
        // Remove overlay
        removeOverlay()
        
        // Stop service
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
        
        // Clean up monitoring
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = null
        
        // Unregister receiver
        try {
            unregisterReceiver(dismissReceiver)
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error unregistering receiver: ${e.message}")
        }

        // Remove overlay
        removeOverlay()
        
        android.util.Log.d("OverlayService", "🛑 Overlay service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}








