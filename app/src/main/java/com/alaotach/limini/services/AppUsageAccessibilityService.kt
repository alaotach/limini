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
                    }
                }
            }
        }
    }
    
    companion object {
        private const val TAG = "AppUsageService"
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val newPkg = event.packageName?.toString()
                if (newPkg.isNullOrBlank()) return
                if (shouldIgnorePackage(newPkg)) {
                    return
                }
                if (currPkg == newPkg) {
                    return
                }
                val now = System.currentTimeMillis()
                Log.d(TAG, "App switch detected from '$currPkg' to '$newPkg'")
                if (currPkg.isNotEmpty()) {
                    val sessionTime = now - startTime
                    if (sessionTime > 0) {
                    }
                }
                currPkg = newPkg
                startTime = now
                activeOverlays.clear()
                sendUsageUpdate()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent: ${e.message}", e)
        }
    }
    
    private fun getStoredUsageTime(packageName: String): Long {
        return try {
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
            
            Log.d(TAG, "System usage for $packageName: ${totalTime}ms (${formatTime(totalTime)})")
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
            Log.d(TAG, "Usage calc for $packageName: stored=${storedTime}ms + session=${currentSessionTime}ms = ${total}ms")
        }
        
        return total
    }
    
    private fun checkTimeLimit() {
        try {
            if (currPkg.isEmpty()) return
            if (activeOverlays.contains(currPkg)) {
                Log.d(TAG, "active")
                return
            }
            
            val totalUsage = getTotalUsageTime(currPkg)
            val timeLimitPrefs = getSharedPreferences("time_limits", Context.MODE_PRIVATE)
            val timeLimitMinutes = timeLimitPrefs.getInt(currPkg, Int.MAX_VALUE)
            
            if (timeLimitMinutes != Int.MAX_VALUE) {
                val timeLimitMs = timeLimitMinutes * 60 * 1000L
                
                if (totalUsage >= timeLimitMs) {
                    Log.d(TAG, "exceeded $currPkg: ${totalUsage}ms >= ${timeLimitMs}ms")
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
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Err: ${e.message}")
        }
    }

    private fun shouldIgnorePackage(pkg: String): Boolean {
        val ignorePkgs = setOf(
            packageName,
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
            Log.d(TAG, "Sending usage update")
            
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

            Log.d(TAG, "${allUserApps.size} apps")

            val usageList = allUserApps.mapNotNull { appInfo ->
                try {
                    val pkg = appInfo.packageName
                    val totalUsage = getTotalUsageTime(pkg)
                    val appName = getAppLabel(pkg)

                    mapOf(
                        "packageName" to pkg,
                        "appName" to appName,
                        "icon" to null as ByteArray?,
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
            
            Log.d(TAG, "Broadcast sent with ${usageList.size} items")
        } catch (e: Exception) {
            Log.e(TAG, "Err: ${e.message}", e)
        }
    }

    private fun getAppLabel(pkg: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Log.e(TAG, "err $pkg: ${e.message}")
            pkg.split(".").lastOrNull()?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            } ?: pkg
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()        
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Context.RECEIVER_NOT_EXPORTED
            } else {
                0
            }
            registerReceiver(overlayDismissReceiver, IntentFilter("com.alaotach.limini.OVERLAY_DISMISSED"), flags)
            createNotificationChannel()
            
            h = Handler(Looper.getMainLooper())
            h?.postDelayed(object : Runnable {
                override fun run() {
                    try {
                        sendUsageUpdate()
                        updateNotification()
                        checkTimeLimit()
                        h?.postDelayed(this, 3000)
                    } catch (e: Exception) {
                        Log.e(TAG, "Err: ${e.message}")
                        h?.postDelayed(this, 3000)
                    }
                }
            }, 3000)
        } catch (e: Exception) {
            Log.e(TAG, "Err: ${e.message}", e)
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
            Log.e(TAG, "Err: ${e.message}")
        }
    }
    
    private fun updateNotification() {
        try {
            if (currPkg.isEmpty()) return
            
            val appName = getAppLabel(currPkg)
            val totalTime = getTotalUsageTime(currPkg)
            val usageText = formatTime(totalTime)
            val prefs = getSharedPreferences("time_limits", Context.MODE_PRIVATE)
            val timeLimitMinutes = prefs.getInt(currPkg, Int.MAX_VALUE)
            
            Log.d(TAG, "📱 Notification update: $currPkg, Total: ${totalTime}ms, Limit: ${timeLimitMinutes}min")
            
            val contentTitle = "Current: $appName"
            val contentText = if (timeLimitMinutes != Int.MAX_VALUE) {
                val limitMs = timeLimitMinutes * 60 * 1000L
                if (totalTime >= limitMs) {
                    "LIMIT EXCEEDED: $usageText / ${timeLimitMinutes}m"
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
            Log.e(TAG, "Err: ${e.message}")
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
        try {
            h?.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "Err: ${e.message}")
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            try {
                unregisterReceiver(overlayDismissReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Err: ${e.message}")
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(1)
            
            h?.removeCallbacksAndMessages(null)
            h = null
            activeOverlays.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Err: ${e.message}")
        }
    }
}
