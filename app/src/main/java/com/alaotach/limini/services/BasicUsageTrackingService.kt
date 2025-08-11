package com.alaotach.limini.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.usage.UsageStatsManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Log
import com.alaotach.limini.utils.NotificationUtil
import java.util.*

class BasicUsageTrackingService : Service() {

    private var handler: Handler? = null
    private lateinit var notificationManager: NotificationManager
    private var isMonitoringEnabled = false
    private val timeLimitCache = mutableMapOf<String, Int>()
    private val activeOverlays = mutableSetOf<String>()
    private var wakeLock: PowerManager.WakeLock? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.alaotach.limini.START_MONITORING" -> {
                    isMonitoringEnabled = true
                    Log.d(TAG, "✅ Basic monitoring started via broadcast")
                }
                "com.alaotach.limini.STOP_MONITORING" -> {
                    isMonitoringEnabled = false
                    Log.d(TAG, "⏹️ Basic monitoring stopped via broadcast")
                }
                "com.alaotach.limini.OVERLAY_DISMISSED" -> {
                    val packageName = intent.getStringExtra("packageName")
                    if (packageName != null) {
                        activeOverlays.remove(packageName)
                        Log.d(TAG, "🔓 Overlay dismissed for $packageName")
                    }
                }
                "com.alaotach.limini.EXTEND_TIME_LIMIT" -> {
                    val packageName = intent.getStringExtra("packageName")
                    val extensionMinutes = intent.getIntExtra("extensionMinutes", 0)
                    if (packageName != null && extensionMinutes > 0) {
                        grantTimeExtension(packageName, extensionMinutes)
                        Log.d(TAG, "⏰ Time extension granted: $packageName + ${extensionMinutes}min")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "BasicUsageTracking"
        private const val NOTIFICATION_ID = 2
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Acquire wake lock to prevent system from killing the service
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Limini:BasicUsageTrackingService"
        )
        wakeLock?.acquire(10*60*1000L /*10 minutes*/)
        
        // Register broadcast receiver
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        val filter = IntentFilter().apply {
            addAction("com.alaotach.limini.START_MONITORING")
            addAction("com.alaotach.limini.STOP_MONITORING")
            addAction("com.alaotach.limini.OVERLAY_DISMISSED")
            addAction("com.alaotach.limini.EXTEND_TIME_LIMIT")
        }
        registerReceiver(broadcastReceiver, filter, flags)
        
        resetDailyDataIfNeeded()
        
        // Start monitoring immediately - no need to wait for broadcasts
        isMonitoringEnabled = true
        Log.d(TAG, "✅ BasicUsageTrackingService starting monitoring immediately")
        
        // Start monitoring loop
        handler = Handler(Looper.getMainLooper())
        handler?.postDelayed(object : Runnable {
            override fun run() {
                if (isMonitoringEnabled) {
                    Log.d(TAG, "🔄 Running monitoring cycle...")
                    sendUsageUpdate()
                    updateNotification()
                    checkTimeLimitsBasic()
                } else {
                    Log.d(TAG, "⏸️ Monitoring disabled")
                }
                handler?.postDelayed(this, 3000) // Check every 3 seconds
            }
        }, 1000)
        
        Log.d(TAG, "BasicUsageTrackingService created and monitoring started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create and show foreground notification
        val notification = NotificationUtil.createForegroundNotification(
            this, 
            "Basic monitoring active - tracking app usage"
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        Log.d(TAG, "🚀 Service started in foreground mode")
        return START_STICKY // Restart service if killed by system
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "📱 App task removed - keeping service alive")
        
        // Restart the service to keep it running
        val restartServiceIntent = Intent(applicationContext, BasicUsageTrackingService::class.java)
        applicationContext.startForegroundService(restartServiceIntent)
        
        super.onTaskRemoved(rootIntent)
    }

    private fun sendUsageUpdate() {
        try {
            val pm = applicationContext.packageManager
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            
            val endTime = System.currentTimeMillis()
            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startOfDay,
                endTime
            )
            
            val usageMap = usageStats?.associate { it.packageName to it.totalTimeInForeground } ?: emptyMap()
            
            val allUserApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { appInfo ->
                    (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
                            pm.getLaunchIntentForPackage(appInfo.packageName) != null &&
                            !shouldIgnorePackage(appInfo.packageName)
                }
            
            val usageList = allUserApps.mapNotNull { appInfo ->
                val pkg = appInfo.packageName
                val usageTime = usageMap[pkg] ?: 0L
                
                // Only include apps with some usage
                if (usageTime > 0) {
                    mapOf(
                        "packageName" to pkg,
                        "appName" to getAppLabel(pkg),
                        "usageTime" to usageTime
                    )
                } else null
            }.sortedByDescending { it["usageTime"] as Long }

            val intent = Intent("com.alaotach.limini.USAGE_UPDATE")
            intent.putExtra("usageList", ArrayList(usageList.map { HashMap(it) }))
            sendBroadcast(intent)
            
            Log.d(TAG, "Usage update sent: ${usageList.size} apps with usage")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending usage update: ${e.message}")
        }
    }

    private fun updateNotification() {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startOfDay,
                endTime
            )
            
            val topApp = usageStats?.maxByOrNull { it.totalTimeInForeground }
            val contentText = if (topApp != null && topApp.totalTimeInForeground > 0) {
                "Most used: ${getAppLabel(topApp.packageName)} (${formatTime(topApp.totalTimeInForeground)})"
            } else {
                "Basic monitoring active"
            }
            
            val notification = NotificationUtil.createForegroundNotification(this, contentText)
            notificationManager.notify(NOTIFICATION_ID, notification)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}")
        }
    }

    private fun checkTimeLimitsBasic() {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startOfDay,
                endTime
            )
            
            // Get current foreground app
            val currentApp = getCurrentForegroundApp()
            Log.d(TAG, "🎯 Current app: $currentApp")
            
            // Check ALL apps with time limits, not just current app
            val timeLimitPrefs = getSharedPreferences("time_limits", Context.MODE_PRIVATE)
            val allLimits = timeLimitPrefs.all
            
            for ((packageName, limitValue) in allLimits) {
                if (limitValue is Int && limitValue != Int.MAX_VALUE && !activeOverlays.contains(packageName)) {
                    val usageTime = usageStats?.find { it.packageName == packageName }?.totalTimeInForeground ?: 0L
                    val limitMs = limitValue * 60 * 1000L
                    
                    // Show overlay if this app has exceeded its limit AND it's the current app
                    if (usageTime >= limitMs && packageName == currentApp) {
                        activeOverlays.add(packageName)
                        
                        val overlayIntent = Intent(this, OverlayService::class.java).apply {
                            putExtra("appName", getAppLabel(packageName))
                            putExtra("timeLimitMinutes", limitValue)
                            putExtra("blockedPackageName", packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startService(overlayIntent)
                        
                        Log.d(TAG, "🚫 BLOCKING: ${getAppLabel(packageName)} - Used: ${formatTime(usageTime)} >= Limit: ${limitValue}m")
                        return // Only block one app at a time
                    } else if (packageName == currentApp) {
                        Log.d(TAG, "✅ OK: ${getAppLabel(packageName)} - Used: ${formatTime(usageTime)} < Limit: ${limitValue}m")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking time limits: ${e.message}")
        }
    }

    private fun getCurrentForegroundApp(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val start = now - 5000L // Last 5 seconds

            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                start,
                now
            )
            
            val recentApp = usageStats
                ?.filter { stat ->
                    stat.lastTimeUsed > 0 && 
                    stat.totalTimeInForeground > 0 &&
                    (now - stat.lastTimeUsed) < 4000 && // Used within last 4 seconds
                    !isSystemApp(stat.packageName) &&
                    !isLauncherApp(stat.packageName)
                }
                ?.maxByOrNull { it.lastTimeUsed }
                ?.packageName
            
            if (recentApp != null) {
                Log.d(TAG, "🎯 Current foreground app: $recentApp")
            }
            
            recentApp
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current app: ${e.message}")
            null
        }
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val packageManager = packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            
            if (isUpdatedSystemApp) return false

            val isCoreSystemApp = packageName.startsWith("android.") ||
                                 packageName.startsWith("com.android.") ||
                                 packageName == "com.google.android.gms" ||
                                 packageName == "com.google.android.gsf"

            isSystemApp && isCoreSystemApp
        } catch (e: Exception) {
            false
        }
    }

    private fun isLauncherApp(packageName: String): Boolean {
        return try {
            val packageManager = packageManager
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            val launcherApps = packageManager.queryIntentActivities(intent, 0)
            launcherApps.any { it.activityInfo.packageName == packageName } ||
            packageName.contains("launcher", ignoreCase = true) ||
            packageName.contains("home", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private fun grantTimeExtension(packageName: String, extensionMinutes: Int) {
        val timeLimitPrefs = getSharedPreferences("time_limits", Context.MODE_PRIVATE)
        val originalLimitPrefs = getSharedPreferences("original_time_limits", Context.MODE_PRIVATE)
        val currentLimit = timeLimitPrefs.getInt(packageName, Int.MAX_VALUE)

        if (currentLimit != Int.MAX_VALUE) {
            val actualExtension = if (extensionMinutes > 0) extensionMinutes else 5
            
            // Save original limit if not already saved
            if (!originalLimitPrefs.contains(packageName)) {
                originalLimitPrefs.edit().putInt(packageName, currentLimit).apply()
            }
            
            // Get current usage to calculate new limit properly
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startOfDay,
                endTime
            )
            
            val currentUsageMs = usageStats?.find { it.packageName == packageName }?.totalTimeInForeground ?: 0L
            val currentUsageMinutes = (currentUsageMs / (1000 * 60)).toInt()
            val newLimit = currentUsageMinutes + actualExtension
            
            timeLimitPrefs.edit().putInt(packageName, newLimit).apply()
            timeLimitCache[packageName] = newLimit
            activeOverlays.remove(packageName)

            val updateIntent = Intent("com.alaotach.limini.TIME_EXTENSION_GRANTED")
            updateIntent.putExtra("packageName", packageName)
            updateIntent.putExtra("newTotalLimit", newLimit)
            sendBroadcast(updateIntent)
            
            Log.d(TAG, "✅ Extension granted: $packageName")
            Log.d(TAG, "   Current usage: ${formatTime(currentUsageMs)} ($currentUsageMinutes minutes)")
            Log.d(TAG, "   Extension: $actualExtension minutes")
            Log.d(TAG, "   New limit: $newLimit minutes")
        } else {
            Log.w(TAG, "❌ Cannot extend time for $packageName - no existing limit found")
        }
    }

    private fun getCurrentTimeLimit(packageName: String): Int {
        return timeLimitCache[packageName] ?: run {
            val storedLimit = getSharedPreferences("time_limits", Context.MODE_PRIVATE).getInt(packageName, Int.MAX_VALUE)
            if (storedLimit != Int.MAX_VALUE) {
                timeLimitCache[packageName] = storedLimit
            }
            storedLimit
        }
    }

    private fun resetDailyDataIfNeeded() {
        val prefs = getSharedPreferences("daily_reset", Context.MODE_PRIVATE)
        val lastResetDate = prefs.getLong("last_reset_date", 0L)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        if (lastResetDate < today) {
            val timeLimitPrefs = getSharedPreferences("time_limits", Context.MODE_PRIVATE)
            val originalLimitPrefs = getSharedPreferences("original_time_limits", Context.MODE_PRIVATE)
            val editor = timeLimitPrefs.edit()
            originalLimitPrefs.all.forEach { (key, value) ->
                if (value is Int) {
                    editor.putInt(key, value)
                }
            }
            editor.apply()
            timeLimitCache.clear()
            
            prefs.edit().putLong("last_reset_date", today).apply()
            Log.d(TAG, "Daily data reset completed")
        }
    }

    private fun getAppLabel(pkg: String): String {
        return try {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun shouldIgnorePackage(pkg: String): Boolean {
        val ignorePkgs = setOf(
            packageName, "android", "com.android.systemui", "com.google.android.googlequicksearchbox",
            "com.android.launcher", "com.android.launcher3", "com.miui.home", "com.huawei.android.launcher",
            "com.sec.android.app.launcher", "com.oppo.launcher", "com.vivo.launcher", "com.coloros.launcher",
            "com.samsung.android.app.launcher", "com.lge.launcher2", "com.htc.launcher"
        )
        return pkg in ignorePkgs || pkg.startsWith("com.android.inputmethod") || pkg.isBlank()
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = ms / (1000 * 60)
        return "${minutes}m ${seconds}s"
    }

    override fun onDestroy() {
        super.onDestroy()
        
        Log.w(TAG, "⚠️ Service being destroyed - attempting to restart")
        
        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        
        try {
            unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
        
        handler?.removeCallbacksAndMessages(null)
        notificationManager.cancel(NOTIFICATION_ID)
        
        // Try to restart the service
        val restartServiceIntent = Intent(applicationContext, BasicUsageTrackingService::class.java)
        applicationContext.startForegroundService(restartServiceIntent)
        
        Log.d(TAG, "🔄 BasicUsageTrackingService destroyed - restart scheduled")
    }
}
