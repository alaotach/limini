package com.alaotach.limini.utils

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager

class PermissionManager(private val activity: AppCompatActivity) {

    fun checkAllPermissions() {
        if (!hasUsagePermission()) {
            requestUsagePermission()
        } else if (!hasNotifPermission()) {
            requestNotifPermission()
        } else if (!hasOverlayPermission()) {
            requestOverlayPermission()
        } else if (!isAccessibilityServiceEnabled()) {
            requestAccessibilityPermission()
        } else {
            Toast.makeText(activity, "All permissions granted! 🎉", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun requestUsagePermission() {
        Toast.makeText(activity, "Please grant 'Usage Access' permission to monitor app usage", Toast.LENGTH_LONG).show()
        activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
    
    fun requestAccessibilityPermission() {
        Toast.makeText(activity, "Please enable 'Limini' in Accessibility settings for app monitoring", Toast.LENGTH_LONG).show()
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun hasUsagePermission(): Boolean {
        val manager = activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 60000
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        return stats != null && stats.isNotEmpty()
    }

    fun hasNotifPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(activity)
        } else true
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        
        for (service in services) {
            val servicePkg = service.resolveInfo.serviceInfo.packageName
            val serviceName = service.resolveInfo.serviceInfo.name

            if (servicePkg == activity.packageName &&
                (serviceName.contains("AppUsageAccessibilityService") || 
                 serviceName == "com.alaotach.limini.services.AppUsageAccessibilityService")) {
                return true
            }
        }
        return false
    }

    fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Toast.makeText(activity, "Please enable 'Display over other apps' permission for time limit enforcement", Toast.LENGTH_LONG).show()
        }
    }
}
