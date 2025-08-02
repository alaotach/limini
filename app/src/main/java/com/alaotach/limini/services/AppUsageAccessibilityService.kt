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
    private val iconCache = mutableMapOf<String, ByteArray?>() // Icon cache to improve performance
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
            val hasIcon = info.iconRes != null && info.iconRes!!.isNotEmpty()
            
            // Log apps without icons for debugging
            if (!hasIcon) {
                Log.w("AppUsage", "No icon for app: ${info.appName} (${info.packageName})")
            }
            
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
            pkg.split(".").lastOrNull()?.replaceFirstChar { 
                if (it.isLowerCase()) it.titlecase() else it.toString() 
            } ?: pkg
        }
    }

    private fun getAppIconBytes(pkg: String): ByteArray? {
        // Check cache first
        if (iconCache.containsKey(pkg)) {
            return iconCache[pkg]
        }
        
        val pm = applicationContext.packageManager
        
        try {
            // Method 1: Try to get the actual app icon (works best for popular apps)
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            val drawable = pm.getApplicationIcon(appInfo)
            
            // Handle different types of drawables
            val bitmap = when {
                // Adaptive icons (Android 8.0+) - YouTube, Instagram, etc.
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && 
                drawable is android.graphics.drawable.AdaptiveIconDrawable -> {
                    createBitmapFromAdaptiveIcon(drawable)
                }
                
                // Vector drawables - many modern apps use these
                drawable is android.graphics.drawable.VectorDrawable -> {
                    createBitmapFromVectorDrawable(drawable)
                }
                
                // Layered drawables
                drawable is android.graphics.drawable.LayerDrawable -> {
                    createBitmapFromLayerDrawable(drawable)
                }
                
                // Standard bitmap drawables
                drawable is android.graphics.drawable.BitmapDrawable -> {
                    val bmp = drawable.bitmap
                    if (bmp != null && !bmp.isRecycled) bmp else null
                }
                
                // Generic drawable conversion
                else -> {
                    createBitmapFromGenericDrawable(drawable)
                }
            }
            
            val iconBytes = bitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    compressBitmapToBytes(bmp, pkg)
                } else null
            }
            
            // Cache the result
            iconCache[pkg] = iconBytes
            return iconBytes
            
        } catch (e: Exception) {
            Log.e("AppUsage", "Failed to get icon for $pkg: ${e.message}")
            
            // Fallback: Try launcher intent method
            val fallbackIcon = tryLauncherIconFallback(pkg, pm)
            iconCache[pkg] = fallbackIcon
            return fallbackIcon
        }
    }

    private fun createBitmapFromAdaptiveIcon(adaptiveIcon: android.graphics.drawable.AdaptiveIconDrawable): android.graphics.Bitmap? {
        return try {
            val size = 108 // Standard size for adaptive icons
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            
            // Set bounds and draw
            adaptiveIcon.setBounds(0, 0, size, size)
            adaptiveIcon.draw(canvas)
            
            bitmap
        } catch (e: Exception) {
            Log.e("AppUsage", "Error creating bitmap from adaptive icon: ${e.message}")
            null
        }
    }

    private fun createBitmapFromVectorDrawable(vectorDrawable: android.graphics.drawable.VectorDrawable): android.graphics.Bitmap? {
        return try {
            val size = 96 // Good size for vector drawables
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            
            vectorDrawable.setBounds(0, 0, size, size)
            vectorDrawable.draw(canvas)
            
            bitmap
        } catch (e: Exception) {
            Log.e("AppUsage", "Error creating bitmap from vector drawable: ${e.message}")
            null
        }
    }

    private fun createBitmapFromLayerDrawable(layerDrawable: android.graphics.drawable.LayerDrawable): android.graphics.Bitmap? {
        return try {
            val size = 96
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            
            layerDrawable.setBounds(0, 0, size, size)
            layerDrawable.draw(canvas)
            
            bitmap
        } catch (e: Exception) {
            Log.e("AppUsage", "Error creating bitmap from layer drawable: ${e.message}")
            null
        }
    }

    private fun createBitmapFromGenericDrawable(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap? {
        return try {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            
            // Limit size to prevent memory issues
            val finalWidth = minOf(width, 192)
            val finalHeight = minOf(height, 192)
            
            val bitmap = android.graphics.Bitmap.createBitmap(finalWidth, finalHeight, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            
            drawable.setBounds(0, 0, finalWidth, finalHeight)
            drawable.draw(canvas)
            
            bitmap
        } catch (e: Exception) {
            Log.e("AppUsage", "Error creating bitmap from generic drawable: ${e.message}")
            null
        }
    }

    private fun tryLauncherIconFallback(pkg: String, pm: PackageManager): ByteArray? {
        return try {
            // Method 1: Launch intent
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                val resolveInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pm.resolveActivity(launchIntent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.resolveActivity(launchIntent, 0)
                }
                
                resolveInfo?.let { info ->
                    val drawable = info.loadIcon(pm)
                    val bitmap = createBitmapFromGenericDrawable(drawable)
                    return bitmap?.let { compressBitmapToBytes(it, pkg) }
                }
            }
            
            // Method 2: Query main activities
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(pkg)
            }
            
            val activities = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            
            if (activities.isNotEmpty()) {
                val drawable = activities[0].loadIcon(pm)
                val bitmap = createBitmapFromGenericDrawable(drawable)
                return bitmap?.let { compressBitmapToBytes(it, pkg) }
            }
            
            // Final fallback: default icon
            val defaultDrawable = pm.defaultActivityIcon
            val bitmap = createBitmapFromGenericDrawable(defaultDrawable)
            return bitmap?.let { compressBitmapToBytes(it, pkg) }
            
        } catch (e: Exception) {
            Log.e("AppUsage", "Fallback icon method failed for $pkg: ${e.message}")
            null
        }
    }

    private fun compressBitmapToBytes(bitmap: android.graphics.Bitmap, pkg: String): ByteArray? {
        return try {
            val stream = java.io.ByteArrayOutputStream()
            
            // Use PNG for better quality with transparency support
            val success = bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, stream)
            
            if (success) {
                val bytes = stream.toByteArray()
                Log.d("AppUsage", "Successfully compressed icon for $pkg, size: ${bytes.size} bytes")
                bytes
            } else {
                Log.w("AppUsage", "Failed to compress bitmap for $pkg")
                null
            }
        } catch (e: Exception) {
            Log.e("AppUsage", "Error compressing bitmap for $pkg: ${e.message}")
            null
        }
    }

    // Debug method for troubleshooting specific apps
    private fun debugIconForApp(pkg: String) {
        val pm = applicationContext.packageManager
        
        try {
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            val drawable = pm.getApplicationIcon(appInfo)
            
            Log.d("AppUsage", "=== DEBUG INFO FOR $pkg ===")
            Log.d("AppUsage", "Drawable type: ${drawable.javaClass.simpleName}")
            Log.d("AppUsage", "Intrinsic size: ${drawable.intrinsicWidth} x ${drawable.intrinsicHeight}")
            Log.d("AppUsage", "Is adaptive: ${drawable is android.graphics.drawable.AdaptiveIconDrawable}")
            Log.d("AppUsage", "Is vector: ${drawable is android.graphics.drawable.VectorDrawable}")
            Log.d("AppUsage", "Is bitmap: ${drawable is android.graphics.drawable.BitmapDrawable}")
            Log.d("AppUsage", "Is layer: ${drawable is android.graphics.drawable.LayerDrawable}")
            
        } catch (e: Exception) {
            Log.e("AppUsage", "Debug failed for $pkg: ${e.message}")
        }
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

    override fun onDestroy() {
        super.onDestroy()
        // Clear cache to prevent memory leaks
        iconCache.clear()
        h?.removeCallbacksAndMessages(null)
    }

    override fun onInterrupt() {
        // Clear handler callbacks when service is interrupted
        h?.removeCallbacksAndMessages(null)
    }
}