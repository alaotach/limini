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
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.alaotach.limini.DISMISS_OVERLAY" -> {
                    android.util.Log.d("OverlayService", "ðŸ“¡ Received manual dismiss broadcast")
                    removeOverlay()
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("OverlayService", "ðŸš€ Overlay service created")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(dismissReceiver, IntentFilter("com.alaotach.limini.DISMISS_OVERLAY"), flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appName = intent?.getStringExtra("appName") ?: "Unknown App"
        val timeLimitMinutes = intent?.getIntExtra("timeLimitMinutes", 60) ?: 60
        blockedPackageName = intent?.getStringExtra("blockedPackageName")

        android.util.Log.d("OverlayService", "ðŸ”’ Creating TRUE SYSTEM OVERLAY for $appName (blocked: $blockedPackageName)")

        showOverlay(appName, timeLimitMinutes)
        startMonitoringCurrentApp()

        return START_NOT_STICKY
    }

    private fun showOverlay(appName: String, timeLimitMinutes: Int) {
        if (isOverlayShowing && overlayView != null) {
            android.util.Log.d("OverlayService", "ðŸ”’ Overlay already showing - skipping recreation to prevent flickering")
            return
        }

        try {
            if (!isOverlayShowing) {
                removeOverlay()
            }

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayView = LayoutInflater.from(this).inflate(R.layout.activity_lock, null)
            overlayView?.findViewById<TextView>(R.id.lockTitle)?.text = "â° Time Limit Reached"
            overlayView?.findViewById<TextView>(R.id.lockMessage)?.text =
                "$appName has exceeded the $timeLimitMinutes minute limit.\n\nTake a break and come back later!"
            overlayView?.findViewById<Button>(R.id.backToHomeButton)?.setOnClickListener {
                android.util.Log.d("OverlayService", "ðŸ  Go Home button clicked - dismissing overlay")
                try {
                    goHome()
                    removeOverlay()
                    stopSelf()
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Error going home: ${e.message}")
                    removeOverlay()
                    stopSelf()
                }
            }

            overlayView?.findViewById<Button>(R.id.openLiminiButton)?.setOnClickListener {
                android.util.Log.d("OverlayService", "ðŸ“± Open Limini button clicked - dismissing overlay")
                try {
                    openLimini()
                    removeOverlay()
                    stopSelf()
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Error opening Limini: ${e.message}")
                    removeOverlay()
                    stopSelf()
                }
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

            android.util.Log.d("OverlayService", "ðŸ”’ Overlay shown at ${System.currentTimeMillis()}")
            overlayView?.postDelayed({
                try {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    windowManager?.updateViewLayout(overlayView, params)
                    android.util.Log.d("OverlayService", "âœ… TRUE SYSTEM OVERLAY SHOWN SUCCESSFULLY (No Flickering)")
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Error enabling touch: ${e.message}")
                }
            }, 100)

        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "âŒ Error creating overlay: ${e.message}", e)
            isOverlayShowing = false
            fallbackToActivity(appName, timeLimitMinutes)
        }
    }

    private fun startMonitoringCurrentApp() {
        if (blockedPackageName.isNullOrEmpty()) {
            android.util.Log.d("OverlayService", "No blocked package name provided, skipping monitoring")
            return
        }

        var consecutiveChecks = 0
        checkRunnable = object : Runnable {
            override fun run() {
                try {
                    val currentApp = getCurrentForegroundApp()
                    consecutiveChecks++

                    val overlayAge = System.currentTimeMillis() - overlayStartTime
                    if (overlayAge > 60000) {
                        android.util.Log.d("OverlayService", "â° Overlay timeout after ${overlayAge}ms - auto-dismissing")
                        removeOverlay()
                        stopSelf()
                        return
                    }
                    android.util.Log.d("OverlayService", "ðŸ” Monitor check #$consecutiveChecks: Current app: '$currentApp', Blocked: '$blockedPackageName' (age: ${overlayAge}ms)")
                    val shouldDismiss = when {
                        currentApp == "HOME_SCREEN" -> {
                            android.util.Log.d("OverlayService", "ðŸ  HOME_SCREEN detected - dismissing overlay")
                            true
                        }
                        currentApp != null && currentApp != blockedPackageName -> {
                            android.util.Log.d("OverlayService", "ðŸ”„ App switched from '$blockedPackageName' to '$currentApp' - dismissing overlay")
                            true
                        }
                        currentApp == null -> {
                            if (consecutiveChecks >= 6) {
                                android.util.Log.d("OverlayService", "â“ No app detected for $consecutiveChecks checks - dismissing overlay")
                                true
                            } else {
                                android.util.Log.d("OverlayService", "â“ No app detected (check $consecutiveChecks/6) - waiting...")
                                false
                            }
                        }
                        else -> {
                            android.util.Log.d("OverlayService", "â° Still in blocked app '$currentApp' - overlay remains")
                            false
                        }
                    }

                    if (shouldDismiss) {
                        android.util.Log.d("OverlayService", "ðŸ›‘ Dismissing overlay and stopping service")
                        removeOverlay()
                        stopSelf()
                        return
                    }
                    handler.postDelayed(this, 500)
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "âŒ Error in monitoring: ${e.message}")
                    android.util.Log.d("OverlayService", "ðŸ›‘ Error occurred - dismissing overlay for safety")
                    removeOverlay()
                    stopSelf()
                }
            }
        }
        handler.postDelayed(checkRunnable!!, 1000)
    }

    private fun getCurrentForegroundApp(): String? {
        return try {
            val usageApp = getCurrentAppFromUsageStats()

            android.util.Log.d("OverlayService", "ðŸŽ¯ Simplified detection - Raw app: '$usageApp'")
            if (usageApp != null && isLauncherApp(usageApp)) {
                android.util.Log.d("OverlayService", "ðŸ  Launcher detected: '$usageApp' -> HOME_SCREEN")
                return "HOME_SCREEN"
            }

            if (usageApp == "com.alaotach.limini") {
                android.util.Log.d("OverlayService", "ðŸ“± Limini app detected - treating as app switch")
                return "HOME_SCREEN"
            }

            android.util.Log.d("OverlayService", "âœ… Final detected app: '$usageApp'")
            usageApp
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "âŒ Error getting current app: ${e.message}")
            null
        }
    }

    private fun getCurrentAppFromUsageStats(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()

            val timeWindows = listOf(
                500L,
                1000L,
                2000L,
                5000L
            )

            for (window in timeWindows) {
                val start = now - window

                val usageStats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    start,
                    now
                )

                val recentApp = usageStats?.filter { stat ->
                    stat.lastTimeUsed > 0 &&
                    stat.totalTimeInForeground > 0 &&
                    !isSystemApp(stat.packageName) &&
                    !isLauncherApp(stat.packageName) &&
                    stat.packageName != "com.alaotach.limini"
                }?.maxByOrNull { stat ->
                    maxOf(
                        stat.lastTimeUsed,
                        stat.lastTimeVisible.takeIf { it > 0 } ?: 0,
                        stat.lastTimeForegroundServiceUsed.takeIf { it > 0 } ?: 0
                    )
                }?.packageName

                if (recentApp != null) {
                    android.util.Log.d("OverlayService", "UsageStats found app: $recentApp (window: ${window}ms)")
                    return recentApp
                }
            }

            android.util.Log.d("OverlayService", "UsageStats: No valid foreground app found")
            null
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error with UsageStats: ${e.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getCurrentAppFromActivityManager(): String? {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return null
            }

            val runningTasks = activityManager.getRunningTasks(1)
            if (runningTasks.isNotEmpty()) {
                runningTasks[0].topActivity?.packageName
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error with ActivityManager: ${e.message}")
            null
        }
    }

    private fun getCurrentAppFromAccessibility(): String? {
        return try {
            null
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error with Accessibility: ${e.message}")
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

            return isSystemApp && isCoreSystemApp
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
        android.util.Log.w("OverlayService", "âš ï¸ Falling back to regular activity")
        val lockIntent = Intent(this, com.alaotach.limini.LockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("appName", appName)
            putExtra("timeLimitMinutes", timeLimitMinutes)
        }
        startActivity(lockIntent)
        stopSelf()
    }

    private fun removeOverlay() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                android.util.Log.d("OverlayService", "ðŸ—‘ï¸ Overlay removed")
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error removing overlay: ${e.message}")
        } finally {
            overlayView = null
            windowManager = null
            isOverlayShowing = false
        }
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
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = null
        try {
            unregisterReceiver(dismissReceiver)
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error unregistering receiver: ${e.message}")
        }

        removeOverlay()
        android.util.Log.d("OverlayService", "ðŸ›‘ Overlay service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
