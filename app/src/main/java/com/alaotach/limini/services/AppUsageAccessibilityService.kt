package com.alaotach.limini.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.core.os.postDelayed
import java.text.SimpleDateFormat
import java.util.*

class AppUsageAccessibilityService : AccessibilityService() {
    private val appUsageMap = mutableMapOf<String, AppUsageInfo>()
    private var currPkg = ""
    private var startTime = 0L
    private var h: Handler? = null

    data class AppUsageInfo(
        val packageName: String,
        var appName: String,
        var iconRes: ByteArray?,
        var usageTime: Long
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val newPkg = event.packageName?.toString() ?: return
            val now = System.currentTimeMillis()
            if (shouldIgnorePackage(newPkg)) {
                if (currPkg.isNotEmpty() && !shouldIgnorePackage(currPkg)) {
                    val prev = appUsageMap.getOrPut(currPkg) {
                        AppUsageInfo(currPkg, getAppLabel(currPkg), getAppIconBytes(currPkg), 0L)
                    }
                    prev.usageTime += now - startTime
                    currPkg = ""
                    startTime = 0L
                    sendAllUsageUpdate()
                }
                return
            }
            if (currPkg.isNotEmpty() && currPkg != newPkg && !shouldIgnorePackage(currPkg)) {
                val prev = appUsageMap.getOrPut(currPkg) {
                    AppUsageInfo(currPkg, getAppLabel(currPkg), getAppIconBytes(currPkg), 0L)
                }
                prev.usageTime += now - startTime
            }
            if (currPkg != newPkg) {
                currPkg = newPkg
                startTime = now
                appUsageMap.getOrPut(currPkg) {
                    AppUsageInfo(currPkg, getAppLabel(currPkg), getAppIconBytes(currPkg), 0L)
                }
                sendAllUsageUpdate()
            }
        }
    }

    private fun shouldIgnorePackage(pkg: String): Boolean {
        val ignorePkgs = setOf(
            "android", "com.android.systemui", "com.google.android.googlequicksearchbox",
            "com.android.launcher", "com.android.launcher3", "com.miui.home", "com.huawei.android.launcher",
            "com.sec.android.app.launcher", "com.oppo.launcher", "com.vivo.launcher", "com.coloros.launcher",
            "com.samsung.android.app.launcher", "com.lge.launcher2", "com.htc.launcher", "com.zui.launcher"
        )
        return pkg in ignorePkgs || pkg.startsWith("com.android.inputmethod") || pkg.isBlank()
    }

    private fun sendAllUsageUpdate() {
        val now = System.currentTimeMillis()
        val usageList = appUsageMap.values.map { info ->
            val session = if (info.packageName == currPkg) (now - startTime) else 0L
            mapOf(
                "packageName" to info.packageName,
                "appName" to info.appName,
                "icon" to info.iconRes,
                "usageTime" to info.usageTime + session
            )
        }.sortedByDescending { it["usageTime"] as Long }
        val i = Intent("com.alaotach.limini.USAGE_UPDATE")
        i.putExtra("usageList", ArrayList(usageList.map { HashMap(it) }))
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun getAppLabel(pkg: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg.split(".").lastOrNull()?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } ?: pkg
        }
    }

    private fun getAppIconBytes(pkg: String): ByteArray? {
        return try {
            val pm = applicationContext.packageManager
            val drawable = pm.getApplicationIcon(pkg)
            val bmp = drawableToBitmap(drawable)
            if (bmp != null) {
                val stream = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap? {
        if (drawable is android.graphics.drawable.BitmapDrawable) {
            return drawable.bitmap
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        h = Handler(mainLooper)
        h?.postDelayed(object : Runnable {
            override fun run() {
                checkForegroundApp()
                sendAllUsageUpdate()
                h?.postDelayed(this, 2000)
            }
        }, 2000)
    }

    private fun checkForegroundApp() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 5000, now)
            val top = stats?.maxByOrNull { it.lastTimeUsed }
            val topPkg = top?.packageName ?: return
            if (topPkg != currPkg && !shouldIgnorePackage(topPkg)) {
                if (currPkg.isNotEmpty() && !shouldIgnorePackage(currPkg)) {
                    val prev = appUsageMap.getOrPut(currPkg) {
                        AppUsageInfo(currPkg, getAppLabel(currPkg), getAppIconBytes(currPkg), 0L)
                    }
                    prev.usageTime += now - startTime
                }
                currPkg = topPkg
                startTime = now
                appUsageMap.getOrPut(currPkg) {
                    AppUsageInfo(currPkg, getAppLabel(currPkg), getAppIconBytes(currPkg), 0L)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}
