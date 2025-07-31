package com.alaotach.limini

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.content.*
import android.widget.*
import com.alaotach.limini.services.AppUsage
import android.provider.Settings
import android.app.usage.UsageStatsManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {
    private lateinit var usageView: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnAccess: Button
    private var isReceiverOn = false
    private var isUsingAccess = false

    private val usageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val data = intent?.getStringExtra("usage") ?: ""
            usageView.text = data
            android.util.Log.d("AccessibilityService", data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usageView = findViewById(R.id.usageTextView)
        btnStart = findViewById(R.id.startButton)
        btnStop = findViewById(R.id.stopButton)
        btnAccess = findViewById(R.id.accessibilityButton)

        checkPermissions()

        btnStart.setOnClickListener {
            if (isAccessEnabled()) {
                isUsingAccess = true
                registerReceiver()
                usageView.text = "tracking..."
                Toast.makeText(this, "ok", Toast.LENGTH_SHORT).show()
            } else if (hasUsagePermission() && hasNotifPermission()) {
                isUsingAccess = false
                try {
                    startForegroundService(Intent(this, AppUsage::class.java))
                    registerReceiver()
                    Toast.makeText(this, "running", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "err", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "perms?", Toast.LENGTH_SHORT).show()
                checkPermissions()
            }
        }

        btnStop.setOnClickListener {
            if (!isUsingAccess) stopService(Intent(this, AppUsage::class.java))
            unregisterReceiver()
            usageView.text = "stopped"
        }

        btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun checkPermissions() {
        if (!hasUsagePermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } else if (!hasNotifPermission()) {
            requestNotifPermission()
        }
    }

    private fun isAccessEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in services) {
            if (service.resolveInfo.serviceInfo.packageName == packageName &&
                service.resolveInfo.serviceInfo.name == "com.alaotach.limini.services.AppUsageAccessibilityService") {
                return true
            }
        }
        return false
    }

    private fun hasUsagePermission(): Boolean {
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 60000
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        return stats != null && stats.isNotEmpty()
    }

    private fun hasNotifPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun registerReceiver() {
        if (!isReceiverOn) {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                usageReceiver,
                IntentFilter("com.alaotach.limini.USAGE_UPDATE")
            )
            isReceiverOn = true
        }
    }

    private fun unregisterReceiver() {
        if (isReceiverOn) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(usageReceiver)
            isReceiverOn = false
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1001) checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver()
        checkPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver()
    }
}
