package com.alaotach.limini.services

import android.app.*
import android.content.*
import android.os.*
import android.app.usage.*
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager

class AppUsage : Service() {
    private lateinit var bgThread: HandlerThread
    private lateinit var handler: Handler
    private var isRunning = false
    private var prevPkg = ""
    private var prevTime = 0L

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
                } catch (e: Exception) {
                    android.util.Log.e("AppUsage", "Error getting usage: ${e.message}")
                }

                handler.postDelayed(this, 1000)
            }
        })

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        bgThread.quitSafely()
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
        val start = now - 2000
        val list = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, now)
        return list?.filter { it.lastTimeUsed > 0 }?.maxByOrNull { it.lastTimeUsed }
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
            sb.append("🟢 CURRENT APP:\n$name\nLast active: $last\n\n")

            if (top.packageName != prevPkg) {
                prevPkg = top.packageName
                prevTime = now
                android.util.Log.d("AppUsage", "Switched to: $name")
            }
        } else {
            sb.append("🔍 Detecting current app...\n\n")
        }

        if (recent.isNotEmpty()) {
            sb.append("📱 RECENT ACTIVITY:\n")
            recent.take(3).forEach {
                val name = getAppName(it.packageName)
                val last = fmt.format(java.util.Date(it.lastTimeUsed))
                val fg = if (it.totalTimeInForeground > 0) {
                    " (${it.totalTimeInForeground / 1000}s)"
                } else ""
                sb.append("• $name$fg\n  Last used: $last\n")
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

    override fun onBind(intent: Intent?): IBinder? = null
}
