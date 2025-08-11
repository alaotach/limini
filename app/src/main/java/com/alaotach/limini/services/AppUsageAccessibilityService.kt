package com.alaotach.limini.services

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.alaotach.limini.utils.NotificationUtil
import java.util.*

class AppUsageAccessibilityService : AccessibilityService() {

    private var currPkg = ""
    private var startTime = 0L
    private var h: Handler? = null
    private val activeOverlays = mutableSetOf<String>()
    private var isMonitoringEnabled = true
    private lateinit var notificationManager: NotificationManager

    private val timeLimitCache = mutableMapOf<String, Int>()
    private val extensionGracePeriod = mutableMapOf<String, Long>()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action            
            when (action) {
                "com.alaotach.limini.OVERLAY_DISMISSED" -> {
                    val packageName = intent.getStringExtra("packageName")
                    if (packageName != null) {
                        activeOverlays.remove(packageName)
                    }
                }
                "com.alaotach.limini.EXTEND_TIME_LIMIT" -> {
                    val packageName = intent.getStringExtra("packageName")
                    val extensionMinutes = intent.getIntExtra("extensionMinutes", 0)
                    if (packageName != null && extensionMinutes > 0) {
                        grantTimeExtension(packageName, extensionMinutes)
                    } else {
                        Log.w(TAG, "Invalid extension request: packageName=$packageName, minutes=$extensionMinutes")
                    }
                }
                "com.alaotach.limini.START_MONITORING" -> {
                    isMonitoringEnabled = true
                }
                "com.alaotach.limini.STOP_MONITORING" -> {
                    isMonitoringEnabled = false
                }
            }
        }
    }

    companion object {
        private const val TAG = "AppUsageAccessibility"
        private const val NOTIFICATION_ID = 1
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        val filter = IntentFilter().apply {
            addAction("com.alaotach.limini.OVERLAY_DISMISSED")
            addAction("com.alaotach.limini.EXTEND_TIME_LIMIT")
            addAction("com.alaotach.limini.START_MONITORING")
            addAction("com.alaotach.limini.STOP_MONITORING")
        }
        registerReceiver(broadcastReceiver, filter, flags)

        resetDailyDataIfNeeded()
        
        h = Handler(Looper.getMainLooper())
        h?.postDelayed(object : Runnable {
            override fun run() {
                sendUsageUpdate()
                updateNotification()
                if (isMonitoringEnabled) {
                    checkTimeLimit()
                }
                h?.postDelayed(this, 3000)
            }
        }, 1000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val newPkg = event.packageName?.toString()
            if (newPkg.isNullOrBlank() || shouldIgnorePackage(newPkg) || currPkg == newPkg) {
                return
            }
            val now = System.currentTimeMillis()
            if (currPkg.isNotEmpty()) {
                val sessionTime = now - startTime
                if (sessionTime > 0) {
                    val sessionPrefs = getSharedPreferences("app_sessions", Context.MODE_PRIVATE)
                    val existingTime = sessionPrefs.getLong("${currPkg}_today", 0L)
                    sessionPrefs.edit().putLong("${currPkg}_today", existingTime + sessionTime).apply()
                }
            }
            val previousApp = currPkg
            currPkg = newPkg
            startTime = now
            activeOverlays.clear()
            
            Log.d(TAG, "App switched: $previousApp → ${getAppLabel(currPkg)}")
        }
    }

    private fun checkTimeLimit() {
        if (currPkg.isEmpty() || activeOverlays.contains(currPkg) || (extensionGracePeriod[currPkg] ?: 0) > System.currentTimeMillis()) {
            return
        }

        val totalUsage = getTotalUsageTime(currPkg)
        val limitMinutes = getCurrentTimeLimit(currPkg)

        if (limitMinutes != Int.MAX_VALUE) {
            val limitMs = limitMinutes * 60 * 1000L
            if (totalUsage >= limitMs) {
                activeOverlays.add(currPkg)
                val overlayIntent = Intent(this, OverlayService::class.java).apply {
                    putExtra("appName", getAppLabel(currPkg))
                    putExtra("timeLimitMinutes", limitMinutes)
                    putExtra("blockedPackageName", currPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startService(overlayIntent)
            }
        }
    }

    private fun grantTimeExtension(packageName: String, extensionMinutes: Int) {
        val timeLimitPrefs = getSharedPreferences("time_limits", Context.MODE_PRIVATE)
        val currentLimit = timeLimitPrefs.getInt(packageName, Int.MAX_VALUE)

        if (currentLimit != Int.MAX_VALUE) {
            val actualExtension = if (extensionMinutes > 0) extensionMinutes else 5
            val currentUsageMs = getTotalUsageTime(packageName)
            val currentUsageMinutes = (currentUsageMs / (1000 * 60)).toInt()
            val newLimit = currentUsageMinutes + actualExtension
            
            timeLimitPrefs.edit().putInt(packageName, newLimit).apply()
            timeLimitCache[packageName] = newLimit
            activeOverlays.remove(packageName)
            extensionGracePeriod[packageName] = System.currentTimeMillis() + 30000L

            val updateIntent = Intent("com.alaotach.limini.TIME_EXTENSION_GRANTED")
            updateIntent.putExtra("packageName", packageName)
            updateIntent.putExtra("newTotalLimit", newLimit)
            sendBroadcast(updateIntent)
            
            if (packageName == currPkg) {
                updateNotification()
            }
        } else {
            Log.w(TAG, "no existing limit")
        }
    }

    private fun updateNotification() {
        if (currPkg.isEmpty()) return

        val totalTime = getTotalUsageTime(currPkg)
        val usageText = formatTime(totalTime)
        val limitMinutes = getCurrentTimeLimit(currPkg)
        
        val contentText = if (limitMinutes != Int.MAX_VALUE) {
            val limitMs = limitMinutes * 60 * 1000L
            val remainingMs = (limitMs - totalTime).coerceAtLeast(0)
            "Usage: $usageText / ${limitMinutes}m (${formatTime(remainingMs)} left)"
        } else {
            "Usage: $usageText (No limit)"
        }
        
        val notification = NotificationUtil.createForegroundNotification(this, "Current: ${getAppLabel(currPkg)}\n$contentText")
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun sendUsageUpdate() {
        val pm = applicationContext.packageManager
        val allUserApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { appInfo ->
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        pm.getLaunchIntentForPackage(appInfo.packageName) != null &&
                        !shouldIgnorePackage(appInfo.packageName)
            }
        val usageList = allUserApps.map { appInfo ->
            val pkg = appInfo.packageName
            val usageTime = getTotalUsageTime(pkg)
            mapOf(
                "packageName" to pkg,
                "appName" to getAppLabel(pkg),
                "usageTime" to usageTime
            )
        }.sortedByDescending { it["usageTime"] as Long }

        usageList.take(3).forEach { app ->
            val time = formatTime(app["usageTime"] as Long)
            val name = app["appName"] as String
            Log.d(TAG, "   $name: $time")
        }

        val intent = Intent("com.alaotach.limini.USAGE_UPDATE")
        intent.putExtra("usageList", ArrayList(usageList.map { HashMap(it) }))
        sendBroadcast(intent)
    }
    
    private fun getTotalUsageTime(packageName: String): Long {
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
        
        val systemUsageTime = usageStats?.find { it.packageName == packageName }?.totalTimeInForeground ?: 0L
        val sessionPrefs = getSharedPreferences("app_sessions", Context.MODE_PRIVATE)
        var persistentSessionTime = sessionPrefs.getLong("${packageName}_today", 0L)
        if (systemUsageTime > persistentSessionTime) {
            sessionPrefs.edit().putLong("${packageName}_today", systemUsageTime).apply()
            persistentSessionTime = systemUsageTime
        }
        val currentSessionTime = if (packageName == currPkg && startTime > 0) {
            val sessionDuration = System.currentTimeMillis() - startTime
            if (sessionDuration > 1000) {
                Log.d(TAG, "Realtime tracking for $packageName: ${formatTime(sessionDuration)} current session")
            }
            sessionDuration
        } else {
            0L
        }
        val totalTime = if (packageName == currPkg && startTime > 0) {
            persistentSessionTime + currentSessionTime
        } else {
            persistentSessionTime
        }
        
        return totalTime
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
            getSharedPreferences("app_sessions", Context.MODE_PRIVATE).edit().clear().apply()
            
            val timeLimitPrefs = getSharedPreferences("time_limits", Context.MODE_PRIVATE)
            val originalLimitPrefs = getSharedPreferences("original_time_limits", Context.MODE_PRIVATE)
            val editor = timeLimitPrefs.edit()
            originalLimitPrefs.all.forEach { (key, value) ->
                editor.putInt(key, value as Int)
            }
            editor.apply()
            timeLimitCache.clear()
            
            prefs.edit().putLong("last_reset_date", today).apply()
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
            "com.samsung.android.app.launcher", "com.lge.launcher2", "com.htc.launcher", "com.zui.launcher"
        )
        return pkg in ignorePkgs || pkg.startsWith("com.android.inputmethod") || pkg.isBlank()
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = ms / (1000 * 60)
        return "${minutes}m ${seconds}s"
    }

    override fun onInterrupt() {
        h?.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(broadcastReceiver)
        notificationManager.cancel(NOTIFICATION_ID)
        h?.removeCallbacksAndMessages(null)
    }
}
