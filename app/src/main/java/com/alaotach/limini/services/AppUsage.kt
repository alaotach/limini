package com.alaotach.limini.services

import android.app.*
import android.content.*
import android.os.*
import android.app.usage.*
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import com.alaotach.limini.LockActivity
import com.alaotach.limini.services.OverlayService

class AppUsage : Service() {
    private lateinit var bgThread: HandlerThread
    private lateinit var handler: Handler
    private var isRunning = false
    private var prevPkg = ""
    private var prevTime = 0L
    private var currentSessionApp = ""
    private var currentSessionStartTime = 0L
    private var currentSessionDuration = 0L
    private val baseUsageTimes = mutableMapOf<String, Long>()
    private val blockedAppsSessions = mutableSetOf<String>()
    private val lastBlockTime = mutableMapOf<String, Long>()

    private val clearBlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.alaotach.limini.CLEAR_BLOCK") {
                android.util.Log.d("AppUsage", "Clearing all block timers (user went home)")
                lastBlockTime.clear()
                blockedAppsSessions.clear()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                "usage_channel",
                "App Usage Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }

        val notif = NotificationCompat.Builder(this, "usage_channel")
            .setContentTitle("Tracking App Usage")
            .setContentText("Monitoring app usage in real-time")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif)
        }

        bgThread = HandlerThread("AppUsageThread")
        bgThread.start()
        handler = Handler(bgThread.looper)
        val filter = IntentFilter("com.alaotach.limini.CLEAR_BLOCK")
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(clearBlockReceiver, filter, flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true

        handler.post(object : Runnable {
            override fun run() {
                if (!isRunning) return

                try {
                    val usage = getUsageData()
                    android.util.Log.d("AppUsage", "Broadcasting usage: $usage")
                    val brIntent = Intent("com.alaotach.limini.USAGE_UPDATE")
                    brIntent.putExtra("usage", usage)
                    sendBroadcast(brIntent)
                    if (System.currentTimeMillis() % 5000 < 500) {
                        sendStructuredUsageData()
                    }
                    val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                    val topApp = getTopApp(usm)
                    if (topApp != null && !shouldIgnorePackage(topApp.packageName)) {
                        val appName = getAppName(topApp.packageName)
                        if (currentSessionApp != topApp.packageName) {
                            android.util.Log.d("AppUsage", "App switched from $currentSessionApp to ${topApp.packageName}")
                            currentSessionApp = topApp.packageName
                            currentSessionStartTime = System.currentTimeMillis()
                            currentSessionDuration = 0L
                            checkCurrentAppTimeLimit()
                        }

                        updateNotificationWithCurrentApp(appName, topApp.packageName)
                    }
                    checkCurrentAppTimeLimit()
                } catch (e: Exception) {
                    android.util.Log.e("AppUsage", "Error getting usage: ${e.message}")
                }
                handler.postDelayed(this, 250)
            }
        })

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        bgThread.quitSafely()
        try {
            unregisterReceiver(clearBlockReceiver)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    private fun getUsageData(): String {
        return try {
            val now = System.currentTimeMillis()
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val topApp = getTopApp(usm)
            val recent = getRecentApps(usm, now)
            formatUsage(topApp, recent, now)
        } catch (e: SecurityException) {
            "Usage access permission not granted"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun getTopApp(usm: UsageStatsManager): UsageStats? {
        val now = System.currentTimeMillis()
        val timeWindows = listOf(250L, 500L, 1000L, 2000L)

        for (window in timeWindows) {
            val start = now - window
            val list = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, now)
            val filteredApp = list?.filter { stat ->
                stat.lastTimeUsed > 0 &&
                stat.totalTimeInForeground > 0 &&
                !isLauncherApp(stat.packageName) &&
                !isSystemApp(stat.packageName) &&
                stat.packageName != "com.alaotach.limini"
            }?.maxByOrNull { stat ->
                maxOf(
                    stat.lastTimeUsed,
                    stat.lastTimeVisible.takeIf { it > 0 } ?: 0,
                    stat.lastTimeForegroundServiceUsed.takeIf { it > 0 } ?: 0
                )
            }

            if (filteredApp != null) {
                android.util.Log.d("AppUsage", "Detected foreground app: ${filteredApp.packageName} (window: ${window}ms)")
                return filteredApp
            }
        }

        android.util.Log.d("AppUsage", "No valid foreground app found, checking all apps...")
        val start = now - 2000L
        val allApps = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, now)
        return allApps?.filter { it.lastTimeUsed > 0 }?.maxByOrNull { it.lastTimeUsed }
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

            return isLauncher || isCommonLauncher
        } catch (e: Exception) {
            android.util.Log.e("AppUsage", "Error checking launcher app: ${e.message}")
            false
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
            android.util.Log.e("AppUsage", "Error checking system app: ${e.message}")
            false
        }
    }

    private fun getRecentApps(usm: UsageStatsManager, now: Long): List<UsageStats> {
        val times = listOf(
            now - (2 * 60 * 1000),
            now - (5 * 60 * 1000),
            now - (15 * 60 * 1000),
            now - (60 * 60 * 1000)
        )

        for (start in times) {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, now)
            val filtered = stats?.filter {
                it.totalTimeInForeground > 0 || it.lastTimeUsed > start
            }?.sortedByDescending { it.lastTimeUsed }?.take(5)

            if (!filtered.isNullOrEmpty()) return filtered
        }

        return emptyList()
    }

    private fun formatUsage(top: UsageStats?, recent: List<UsageStats>, now: Long): String {
        val sb = StringBuilder()
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        sb.append("Updated: ${fmt.format(java.util.Date(now))}\n\n")

        if (top != null) {
            val name = getAppName(top.packageName)
            val last = fmt.format(java.util.Date(top.lastTimeUsed))
            sb.append("ðŸŸ¢ CURRENT APP:\n$name\nLast active: $last\n\n")

            if (!shouldIgnorePackage(top.packageName)) {
                if (top.packageName != prevPkg) {
                    prevPkg = top.packageName
                    prevTime = now

                    if (currentSessionApp.isNotEmpty() && currentSessionApp != top.packageName) {
                        currentSessionDuration = now - currentSessionStartTime
                    }

                    currentSessionApp = top.packageName
                    currentSessionStartTime = now
                    currentSessionDuration = 0L

                    android.util.Log.d("AppUsage", "Switched to: $name")
                    updateNotificationWithCurrentApp(name, top.packageName)
                } else {
                    currentSessionDuration = now - currentSessionStartTime

                    if ((now - prevTime) > 10000 || prevTime == 0L) {
                        updateNotificationWithCurrentApp(name, top.packageName)
                        prevTime = now
                    }
                }
            } else {
                if (currentSessionApp.isNotEmpty() && !shouldIgnorePackage(currentSessionApp)) {
                    currentSessionDuration = now - currentSessionStartTime
                    if ((now - prevTime) > 10000 || prevTime == 0L) {
                        val previousAppName = getAppName(currentSessionApp)
                        updateNotificationWithCurrentApp(previousAppName, currentSessionApp)
                        prevTime = now
                        android.util.Log.d("AppUsage", "System UI detected, keeping previous app: $previousAppName")
                    }
                }
            }
        } else {
            sb.append("ðŸ” Detecting current app...\n\n")
        }

        if (recent.isNotEmpty()) {
            sb.append("ðŸ“± RECENT ACTIVITY:\n")
            recent.take(3).forEach {
                val name = getAppName(it.packageName)
                val last = fmt.format(java.util.Date(it.lastTimeUsed))
                val fg = if (it.totalTimeInForeground > 0) {
                    " (${it.totalTimeInForeground / 1000}s)"
                } else ""
                sb.append("â€¢ $name$fg\n  Last used: $last\n")
            }
        }

        return sb.toString()
    }

    private fun getAppName(pkg: String): String {
        return try {
            val pm = applicationContext.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg.split(".").lastOrNull()?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            } ?: pkg
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes > 0) {
            String.format("%dm %ds", minutes, remainingSeconds)
        } else {
            String.format("%ds", remainingSeconds)
        }
    }

    private fun shouldIgnorePackage(pkg: String): Boolean {
        val ignorePkgs = setOf(
            "android", "com.android.systemui", "com.google.android.googlequicksearchbox",
            "com.android.launcher", "com.android.launcher3", "com.miui.home", "com.huawei.android.launcher",
            "com.sec.android.app.launcher", "com.oppo.launcher", "com.vivo.launcher", "com.coloros.launcher",
            "com.samsung.android.app.launcher", "com.lge.launcher2", "com.htc.launcher", "com.zui.launcher",
            "com.alaotach.limini","com.android.settings", "com.samsung.android.app.settings", "com.miui.securitycenter",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.samsung.android.packageinstaller"
        )
        return pkg in ignorePkgs ||
               pkg.startsWith("com.android.inputmethod") ||
               pkg.startsWith("com.android.keyguard") ||
               pkg.startsWith("com.android.incallui") ||
               pkg.startsWith("com.android.dialer") ||
               pkg.isBlank()
    }

    private fun checkCurrentAppTimeLimit() {
        if (currentSessionApp.isNotEmpty() && !shouldIgnorePackage(currentSessionApp)) {
            if (currentSessionStartTime > 0) {
                currentSessionDuration = System.currentTimeMillis() - currentSessionStartTime
            }
            if (!baseUsageTimes.containsKey(currentSessionApp)) {
                loadBaseUsageTime(currentSessionApp)
            }

            val baseTime = baseUsageTimes[currentSessionApp] ?: 0L
            val totalTime = baseTime + currentSessionDuration
            val appName = getAppName(currentSessionApp)

            android.util.Log.d("AppUsage", "Checking time limit for $appName: base=${baseTime}ms, session=${currentSessionDuration}ms, total=${totalTime}ms")
            checkTimeLimitAndBlock(currentSessionApp, appName, totalTime)
        }
    }

    private fun loadBaseUsageTime(packageName: String) {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (24 * 60 * 60 * 1000)

            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            val appUsage = usageStats?.find { it.packageName == packageName }
            val baseUsageTime = appUsage?.totalTimeInForeground ?: 0L
            baseUsageTimes[packageName] = baseUsageTime

            android.util.Log.d("AppUsage", "Loaded base usage time for $packageName: ${baseUsageTime}ms")
        } catch (e: Exception) {
            android.util.Log.e("AppUsage", "Error loading base usage time for $packageName: ${e.message}")
            baseUsageTimes[packageName] = 0L
        }
    }

    private fun sendStructuredUsageData() {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val packageManager = packageManager

            val endTime = System.currentTimeMillis()
            val startTime = endTime - (24 * 60 * 60 * 1000)

            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            if (usageStats != null && usageStats.isNotEmpty()) {
                val usageItems = usageStats
                    .filter { it.totalTimeInForeground > 0 }
                    .map { usageStat ->
                        val appName = try {
                            val appInfo = packageManager.getApplicationInfo(usageStat.packageName, 0)
                            packageManager.getApplicationLabel(appInfo).toString()
                        } catch (e: Exception) {
                            usageStat.packageName.split(".").lastOrNull()?.replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase() else it.toString()
                            } ?: usageStat.packageName
                        }

                        val baseTime = usageStat.totalTimeInForeground
                        val sessionTime = if (usageStat.packageName == currentSessionApp) currentSessionDuration else 0L
                        val totalTime = baseTime + sessionTime

                        val iconBytes = try {
                            val drawable = packageManager.getApplicationIcon(usageStat.packageName)
                            val bitmap = android.graphics.Bitmap.createBitmap(
                                drawable.intrinsicWidth.coerceAtLeast(1),
                                drawable.intrinsicHeight.coerceAtLeast(1),
                                android.graphics.Bitmap.Config.ARGB_8888
                            )
                            val canvas = android.graphics.Canvas(bitmap)
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)

                            val stream = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                            stream.toByteArray()
                        } catch (e: Exception) {
                            null
                        }

                        mapOf(
                            "packageName" to usageStat.packageName,
                            "appName" to appName,
                            "icon" to iconBytes,
                            "usageTime" to totalTime
                        )
                    }
                    .sortedByDescending { it["usageTime"] as Long }
                    .take(20)
                val structuredIntent = Intent("com.alaotach.limini.USAGE_UPDATE")
                structuredIntent.putExtra("usageList", ArrayList(usageItems.map { HashMap(it) }))
                sendBroadcast(structuredIntent)

                android.util.Log.d("AppUsage", "Sent structured data with ${usageItems.size} apps")
            }
        } catch (e: Exception) {
            android.util.Log.e("AppUsage", "Error sending structured usage data: ${e.message}")
        }
    }

    private fun checkTimeLimitAndBlock(packageName: String, appName: String, usageTimeMs: Long) {
        try {
            val now = System.currentTimeMillis()
            val lastBlocked = lastBlockTime[packageName] ?: 0L
            val timeSinceLastBlock = now - lastBlocked
            if (timeSinceLastBlock < 10000) {
                android.util.Log.d("AppUsage", "$appName: Skipping check - recently blocked (${timeSinceLastBlock}ms ago)")
                return
            }
            val prefs = getSharedPreferences("time_limits", Context.MODE_PRIVATE)
            val timeLimitMinutes = prefs.getInt(packageName, Int.MAX_VALUE)

            android.util.Log.d("AppUsage", "$appName: Time limit from prefs = $timeLimitMinutes minutes")
            if (timeLimitMinutes == Int.MAX_VALUE) {
                android.util.Log.d("AppUsage", "$appName: Time limit disabled (value=$timeLimitMinutes)")
                return
            }

            val timeLimitMs = timeLimitMinutes * 60 * 1000L
            val usageMinutes = usageTimeMs / (60 * 1000)

            android.util.Log.d("AppUsage", "Checking $appName: Usage=${usageMinutes}min (${usageTimeMs}ms), Limit=${timeLimitMinutes}min (${timeLimitMs}ms)")

            if (usageTimeMs >= timeLimitMs) {
                android.util.Log.w("AppUsage", "âš ï¸ TIME LIMIT EXCEEDED for $appName! Usage: ${usageMinutes}min >= Limit: ${timeLimitMinutes}min")
                android.util.Log.w("AppUsage", "ðŸš« LAUNCHING TRUE SYSTEM OVERLAY for $appName")
                lastBlockTime[packageName] = now
                blockedAppsSessions.add(packageName)
                val overlayIntent = Intent(this, com.alaotach.limini.services.OverlayService::class.java).apply {
                    putExtra("appName", appName)
                    putExtra("timeLimitMinutes", timeLimitMinutes)
                    putExtra("blockedPackageName", packageName)
                }
                android.util.Log.d("AppUsage", "ðŸ”“ Starting TRUE SYSTEM OVERLAY SERVICE for $appName (package: $packageName)")
                startService(overlayIntent)
                val clearIntent = Intent("com.alaotach.limini.IMMEDIATE_BLOCK")
                clearIntent.putExtra("packageName", packageName)
                clearIntent.putExtra("appName", appName)
                sendBroadcast(clearIntent)

            } else {
                android.util.Log.d("AppUsage", "âœ… $appName: Under limit (${usageMinutes}/${timeLimitMinutes} minutes)")
            }
        } catch (e: Exception) {
            android.util.Log.e("AppUsage", "Error checking time limit: ${e.message}")
        }
    }

    private fun updateNotificationWithCurrentApp(appName: String, packageName: String) {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (24 * 60 * 60 * 1000)
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )
            val baseTime = usageStats?.find { it.packageName == packageName }?.totalTimeInForeground ?: 0L
            val totalTime = baseTime + currentSessionDuration
            val usageMinutes = totalTime / (60 * 1000)
            val usageSeconds = (totalTime % (60 * 1000)) / 1000
            val prefs = getSharedPreferences("time_limits", Context.MODE_PRIVATE)
            val timeLimitMinutes = prefs.getInt(packageName, 60)
            val usageText = if (usageMinutes > 0) {
                "${usageMinutes}m ${usageSeconds}s"
            } else {
                "${usageSeconds}s"
            }
            val contentTitle = "Current: $appName"
            val contentText = if (timeLimitMinutes != Int.MAX_VALUE) {
                "Usage: $usageText / ${timeLimitMinutes}m limit"
            } else {
                "Usage: $usageText (no limit)"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val updatedNotification = NotificationCompat.Builder(this, "usage_channel")
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build()

            notificationManager.notify(1, updatedNotification)

            android.util.Log.d("AppUsage", "Updated notification: $appName - $usageText")

        } catch (e: Exception) {
            android.util.Log.e("AppUsage", "Error updating notification: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
