package com.alaotach.limini.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.BroadcastReceiver
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.ComponentName
import androidx.core.app.NotificationCompat
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
            cleanupAndStop()
        }
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 2
    }

    override fun onCreate() {
        super.onCreate()
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

        val appName = intent?.getStringExtra("appName") ?: "Unknown App"
        val timeLimitMinutes = intent?.getIntExtra("timeLimitMinutes", 60) ?: 60
        blockedPackageName = intent?.getStringExtra("blockedPackageName")
        userDismissed = false

        if (blockedPackageName == null) {
            cleanupAndStop()
            return START_NOT_STICKY
        }

        Log.d(TAG, "🔒 Creating overlay for $appName (blocked: $blockedPackageName)")

        val notification = createNotification(appName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        showOverlay(appName, timeLimitMinutes)

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Limini",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification by Limini"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(appName: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("App Blocked")
            .setContentText("Time limit reached for $appName.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showOverlay(appName: String, timeLimitMinutes: Int) {
        if (isOverlayShowing || isServiceDestroying) {
            return
        }

        try {
            isOverlayShowing = true
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayView = LayoutInflater.from(this).inflate(R.layout.activity_lock, null)
            
            val titleView = overlayView?.findViewById<TextView>(R.id.lockTitle)
            val messageView = overlayView?.findViewById<TextView>(R.id.lockMessage)
            val homeButton = overlayView?.findViewById<Button>(R.id.backToHomeButton)
            val liminiButton = overlayView?.findViewById<Button>(R.id.openLiminiButton)

            titleView?.text = "Time's Up!"
            messageView?.text = "limit exceeded ${timeLimitMinutes} mins for $appName."

            homeButton?.setOnClickListener {
                goHome()
                cleanupAndStop()
            }

            liminiButton?.setOnClickListener {
                openLimini()
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
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.CENTER

            windowManager?.addView(overlayView, params)
            overlayStartTime = System.currentTimeMillis()
            startMonitoringCurrentApp()
        } catch (e: Exception) {
            Log.e(TAG, "Err: ${e.message}", e)
            isOverlayShowing = false
            cleanupAndStop()
        }
    }

    private fun startMonitoringCurrentApp() {
        if (blockedPackageName.isNullOrEmpty() || isServiceDestroying) {
            return
        }

        checkRunnable = object : Runnable {
            override fun run() {
                if (isServiceDestroying || userDismissed) {
                    return
                }

                try {
                    val currentApp = getCurrentForegroundApp()
                    if (currentApp == blockedPackageName) {
                    }
                    handler.postDelayed(this, 2000)
                } catch (e: Exception) {
                    Log.e(TAG, "Err: ${e.message}")
                    handler.postDelayed(this, 3000)
                }
            }
        }
        handler.postDelayed(checkRunnable!!, 1000)
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

            Log.d(TAG, "app: '$currentApp'")
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
                Log.d(TAG, "app: $recentApp")
            } else {
                Log.d(TAG, "UsageStats: No foreground app")
            }

            recentApp
        } catch (e: Exception) {
            Log.e(TAG, "Err: ${e.message}")
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
            Log.e(TAG, "Error checking system app: ${e.message}")
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
                Log.d(TAG, "Identified launcher app: $packageName")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Error checking launcher app: ${e.message}")
            false
        }
    }

    private fun cleanupAndStop() {
        if (isServiceDestroying) return
        isServiceDestroying = true
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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

    private fun removeOverlay() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                Log.d(TAG, "🗑️ Overlay removed")
                if (!blockedPackageName.isNullOrEmpty()) {
                    val dismissIntent = Intent("com.alaotach.limini.OVERLAY_DISMISSED").apply {
                        putExtra("packageName", blockedPackageName)
                    }
                    sendBroadcast(dismissIntent)
                    Log.d(TAG, "📡 Sent overlay dismissed broadcast for $blockedPackageName")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay: ${e.message}")
        } finally {
            overlayView = null
            windowManager = null
            isOverlayShowing = false
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "💥 Overlay service destroyed")
        super.onDestroy()
        cleanupAndStop()
        try {
            unregisterReceiver(dismissReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}