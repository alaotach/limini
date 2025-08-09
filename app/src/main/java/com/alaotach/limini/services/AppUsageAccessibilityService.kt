package com.alaotach.limini.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.os.postDelayed
import java.text.SimpleDateFormat
import java.util.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.IntentFilter

class AppUsageAccessibilityService : AccessibilityService() {
    private val appUsageMap = mutableMapOf<String, AppUsageInfo>()
    private val iconCache = mutableMapOf<String, ByteArray?>()
    private var currPkg = ""
    private var startTime = 0L
    private var h: Handler? = null
    private val activeOverlays = mutableSetOf<String>()
    
    private val overlayDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.alaotach.limini.OVERLAY_DISMISSED" -> {
                    val packageName = intent.getStringExtra("packageName")
                    if (packageName != null) {
                        activeOverlays.remove(packageName)
                        Log.d(TAG, "🗑️ Cleared overlay flag for $packageName")
                    }
                }
            }
        }
    }
    
    companion object {
        private const val TAG = "AppUsageService"
    }

    data class AppUsageInfo(
        val packageName: String,
        var appName: String,
        var iconRes: ByteArray?,
        var usageTime: Long
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val newPkg = event.packageName?.toString()
                if (newPkg.isNullOrBlank()) return
                
                val now = System.currentTimeMillis()
                Log.d(TAG, "🔄 Window state changed: $newPkg")

                if (shouldIgnorePackage(newPkg)) {
                    Log.d(TAG, "⏭️ Ignoring package: $newPkg")
                    stopCurrentSession(now)
                    return
                }

                // Stop previous session if different app
                if (currPkg.isNotEmpty() && currPkg != newPkg && !shouldIgnorePackage(currPkg)) {
                    val sessionTime = now - startTime
                    if (sessionTime > 0) {
                        Log.d(TAG, "📊 $currPkg session: ${sessionTime}ms (not saving - using system stats)")
                    }
                }

                // Start new session
                if (currPkg != newPkg) {
                    currPkg = newPkg
                    startTime = now
                    
                    // Clear overlay flag when switching to different app
                    activeOverlays.clear()
                    
                    // Initialize app info if needed
                    if (!appUsageMap.containsKey(currPkg)) {
                        appUsageMap[currPkg] = AppUsageInfo(
                            currPkg, 
                            getAppLabel(currPkg), 
                            null, // Don't load icons initially to avoid crashes
                            0L
                        )
                    }
                    
                    Log.d(TAG, "▶️ Started tracking: $currPkg (stored: ${getStoredUsageTime(currPkg)}ms)")
                    sendUsageUpdate()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onAccessibilityEvent: ${e.message}", e)
        }
    }
    
    private fun stopCurrentSession(now: Long) {
        if (currPkg.isNotEmpty() && !shouldIgnorePackage(currPkg)) {
            val sessionTime = now - startTime
            if (sessionTime > 0) {
                Log.d(TAG, "⏹️ Stopped $currPkg, session time: ${sessionTime}ms (not saving - using system stats)")
            }
            currPkg = ""
            startTime = 0L
            sendUsageUpdate()
        }
    }
    
    private fun updateAppUsage(packageName: String, sessionTime: Long) {
        try {
            val usageInfo = appUsageMap.getOrPut(packageName) {
                AppUsageInfo(packageName, getAppLabel(packageName), null, 0L)
            }
            usageInfo.usageTime += sessionTime
        } catch (e: Exception) {
            Log.e(TAG, "Error updating app usage for $packageName: ${e.message}")
        }
    }
    
    private fun getStoredUsageTime(packageName: String): Long {
        return try {
            // Use Android's built-in usage statistics as the base
            getSystemUsageTime(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting stored usage for $packageName: ${e.message}")
            0L
        }
    }
    
    private fun getSystemUsageTime(packageName: String): Long {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startOfDay,
                now
            )
            
            val appUsage = usageStats?.find { it.packageName == packageName }
            val totalTime = appUsage?.totalTimeInForeground ?: 0L
            
            Log.d(TAG, "📊 System usage for $packageName: ${totalTime}ms (${formatTime(totalTime)})")
            totalTime
        } catch (e: Exception) {
            Log.e(TAG, "Error getting system usage for $packageName: ${e.message}")
            0L
        }
    }
    
    private fun getTotalUsageTime(packageName: String): Long {
        val storedTime = getStoredUsageTime(packageName)
        val currentSessionTime = if (packageName == currPkg && startTime > 0) {
            System.currentTimeMillis() - startTime
        } else {
            0L
        }
        val total = storedTime + currentSessionTime
        
        if (packageName == currPkg) {
            Log.d(TAG, "⏱️ Usage calc for $packageName: stored=${storedTime}ms + session=${currentSessionTime}ms = ${total}ms")
        }
        
        return total
    }
    
    private fun checkTimeLimit() {
        try {
            if (currPkg.isEmpty()) return
            
            // Check if overlay is already active for this app
            if (activeOverlays.contains(currPkg)) {
                Log.d(TAG, "🚫 Overlay already active for $currPkg - skipping")
                return
            }
            
            val totalUsage = getTotalUsageTime(currPkg)
            val timeLimitPrefs = getSharedPreferences("time_limits", Context.MODE_PRIVATE)
            val timeLimitMinutes = timeLimitPrefs.getInt(currPkg, Int.MAX_VALUE)
            
            if (timeLimitMinutes != Int.MAX_VALUE) {
                val timeLimitMs = timeLimitMinutes * 60 * 1000L
                
                if (totalUsage >= timeLimitMs) {
                    Log.d(TAG, "⚠️ Time limit exceeded for $currPkg: ${totalUsage}ms >= ${timeLimitMs}ms")
                    
                    // Mark overlay as active to prevent repeated triggers
                    activeOverlays.add(currPkg)
                    
                    val appName = getAppLabel(currPkg)
                    val overlayIntent = Intent(this, com.alaotach.limini.services.OverlayService::class.java).apply {
                        putExtra("appName", appName)
                        putExtra("timeLimitMinutes", timeLimitMinutes)
                        putExtra("blockedPackageName", currPkg)
                    }
                    startService(overlayIntent)
                } else {
                    val remainingMs = timeLimitMs - totalUsage
                    val remainingMinutes = remainingMs / (60 * 1000)
                    Log.d(TAG, "⏱️ $currPkg within limit: ${remainingMinutes}m remaining")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking time limit: ${e.message}")
        }
    }

    private fun shouldIgnorePackage(pkg: String): Boolean {
        val ignorePkgs = setOf(
            packageName, // Ignore Limini itself
            "android", 
            "com.android.systemui", 
            "com.google.android.googlequicksearchbox",
            "com.android.launcher", 
            "com.android.launcher3", 
            "com.miui.home", 
            "com.huawei.android.launcher",
            "com.sec.android.app.launcher", 
            "com.oppo.launcher", 
            "com.vivo.launcher", 
            "com.coloros.launcher",
            "com.samsung.android.app.launcher", 
            "com.lge.launcher2", 
            "com.htc.launcher", 
            "com.zui.launcher"
        )
        return pkg in ignorePkgs || pkg.startsWith("com.android.inputmethod") || pkg.isBlank()
    }

    private fun sendUsageUpdate() {
        try {
            val now = System.currentTimeMillis()
            Log.d(TAG, "📡 Sending usage update")
            
            val pm = applicationContext.packageManager
            val allUserApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { appInfo ->
                    try {
                        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        pm.getLaunchIntentForPackage(appInfo.packageName) != null &&
                        !shouldIgnorePackage(appInfo.packageName)
                    } catch (e: Exception) {
                        false
                    }
                }

            Log.d(TAG, "📱 Found ${allUserApps.size} user apps")

            val usageList = allUserApps.mapNotNull { appInfo ->
                try {
                    val pkg = appInfo.packageName
                    val totalUsage = getTotalUsageTime(pkg)
                    val appName = getAppLabel(pkg)

                    mapOf(
                        "packageName" to pkg,
                        "appName" to appName,
                        "icon" to null as ByteArray?, // Don't send icons for now
                        "usageTime" to totalUsage
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing app ${appInfo.packageName}: ${e.message}")
                    null
                }
            }
            .sortedWith(
                compareByDescending<Map<String, Any?>> { (it["usageTime"] as Long) > 0 }
                .thenByDescending { it["usageTime"] as Long }
                .thenBy { it["appName"] as String }
            )

            val intent = Intent("com.alaotach.limini.USAGE_UPDATE")
            intent.putExtra("usageList", ArrayList(usageList.map { HashMap(it) }))
            sendBroadcast(intent)
            
            Log.d(TAG, "✅ Broadcast sent with ${usageList.size} items")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending usage update: ${e.message}", e)
        }
    }

    private fun getAppLabel(pkg: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app label for $pkg: ${e.message}")
            pkg.split(".").lastOrNull()?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            } ?: pkg
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "🚀 Accessibility service connected!")
        
        try {
            // Register overlay dismiss receiver
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Context.RECEIVER_NOT_EXPORTED
            } else {
                0
            }
            registerReceiver(overlayDismissReceiver, IntentFilter("com.alaotach.limini.OVERLAY_DISMISSED"), flags)
            
            // Create notification channel
            createNotificationChannel()
            
            h = Handler(Looper.getMainLooper())

            // Start periodic checks every 3 seconds
            h?.postDelayed(object : Runnable {
                override fun run() {
                    try {
                        sendUsageUpdate()
                        updateNotification() // Update notification with current app
                        checkTimeLimit() // Check if current app exceeds time limit
                        h?.postDelayed(this, 3000)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in periodic update: ${e.message}")
                        h?.postDelayed(this, 3000) // Continue despite errors
                    }
                }
            }, 3000)
            
            Log.d(TAG, "✅ Accessibility service setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up accessibility service: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "usage_channel",
                    "App Usage Tracker",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows current app usage information"
                    setShowBadge(false)
                }
                
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification channel: ${e.message}")
        }
    }
    
    private fun updateNotification() {
        try {
            if (currPkg.isEmpty()) return
            
            val appName = getAppLabel(currPkg)
            val totalTime = getTotalUsageTime(currPkg)
            val usageText = formatTime(totalTime)
            
            // Check if app has time limit
            val prefs = getSharedPreferences("time_limits", Context.MODE_PRIVATE)
            val timeLimitMinutes = prefs.getInt(currPkg, Int.MAX_VALUE)
            
            Log.d(TAG, "📱 Notification update: $currPkg, Total: ${totalTime}ms, Limit: ${timeLimitMinutes}min")
            
            val contentTitle = "Current: $appName"
            val contentText = if (timeLimitMinutes != Int.MAX_VALUE) {
                val limitMs = timeLimitMinutes * 60 * 1000L
                if (totalTime >= limitMs) {
                    "⚠️ LIMIT EXCEEDED: $usageText / ${timeLimitMinutes}m"
                } else {
                    "Usage: $usageText / ${timeLimitMinutes}m limit"
                }
            } else {
                "Usage: $usageText (no limit)"
            }
            
            val notification = NotificationCompat.Builder(this, "usage_channel")
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1, notification)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}")
        }
    }
    
    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes > 0) {
            "${minutes}m ${remainingSeconds}s"
        } else {
            "${remainingSeconds}s"
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Accessibility Service Interrupted")
        try {
            h?.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error during interrupt: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "🔄 Accessibility service destroyed")
        try {
            super.onDestroy()
            
            // Unregister receiver
            try {
                unregisterReceiver(overlayDismissReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering overlay dismiss receiver: ${e.message}")
            }
            
            // Clear notification
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(1)
            
            h?.removeCallbacksAndMessages(null)
            h = null
            appUsageMap.clear()
            iconCache.clear()
            activeOverlays.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error during destroy: ${e.message}")
        }
    }
}
