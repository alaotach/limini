package com.alaotach.limini

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.content.*
import android.widget.*
import com.alaotach.limini.services.AppUsage
import android.provider.Settings

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.alaotach.limini.adapters.IndividualUsageAdapter
import com.alaotach.limini.data.UsageItem
import com.alaotach.limini.utils.PermissionManager
import com.alaotach.limini.utils.TimeLimitManager

class MainActivity : AppCompatActivity() {
    private lateinit var usageView: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnAccess: Button
    private lateinit var btnShowAll: Button
    private lateinit var btnTestData: Button
    private lateinit var btnTestBroadcast: Button
    private lateinit var btnLoadRealApps: Button
    private lateinit var btnTestOverlay: Button
    private lateinit var usageListView: RecyclerView
    private var isReceiverOn = false
    private var isUsingAccess = false
    private lateinit var usageAdapter: IndividualUsageAdapter
    private var showAllApps = false

    private lateinit var permissionManager: PermissionManager
    private lateinit var timeLimitManager: TimeLimitManager

    private val usageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("MainActivity", "Broadcast received! Action: ${intent?.action}")
            @Suppress("UNCHECKED_CAST", "DEPRECATION")
            val usageList: ArrayList<HashMap<String, Any?>>? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent?.getSerializableExtra("usageList", ArrayList::class.java) as? ArrayList<HashMap<String, Any?>>
            } else {
                intent?.getSerializableExtra("usageList") as? ArrayList<HashMap<String, Any?>>
            }

            android.util.Log.d("MainActivity", "Usage list received: ${usageList?.size ?: 0} items")

            if (usageList == null || usageList.isEmpty()) {
                android.util.Log.d("MainActivity", "No usage data received, loading real apps")
                loadAllUserApps()
                return
            }

            android.util.Log.d("MainActivity", "Processing usage list with ${usageList.size} items")
            val allItems = usageList.map {
                val iconRaw = it["icon"]
                val iconBytes: ByteArray? = when (iconRaw) {
                    is ByteArray -> iconRaw
                    is ArrayList<*> -> {
                        val intList = iconRaw.filterIsInstance<Int>()
                        ByteArray(intList.size) { i -> intList[i].toByte() }
                    }
                    else -> null
                }
                UsageItem(
                    it["packageName"] as? String ?: "",
                    it["appName"] as? String ?: "",
                    iconBytes,
                    (it["usageTime"] as? Long) ?: 0L
                )
            }

            android.util.Log.d("MainActivity", "Mapped ${allItems.size} items")

            val filteredApps = if (allItems.any { it.packageName.startsWith("com.test.") }) {
                android.util.Log.d("MainActivity", "Test data detected, showing all items")
                allItems
            } else {
                allItems.filter { item ->
                    !isSystemApp(item.packageName) && item.packageName != "com.alaotach.limini"
                }
            }

            val sortedApps = filteredApps.sortedByDescending { it.usageTime }

            android.util.Log.d("MainActivity", "Final list: ${sortedApps.size} apps")

            usageAdapter.submitList(sortedApps)
            usageView.text = "Received ${sortedApps.size} apps via broadcast"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionManager = PermissionManager(this)
        timeLimitManager = TimeLimitManager(this)

        timeLimitManager.loadTimeLimits()

        initializeUI()
        setupEventListeners()

        android.util.Log.d("MainActivity", "App started")
        android.util.Log.d("MainActivity", "Accessibility service enabled: ${permissionManager.isAccessibilityServiceEnabled()}")
        android.util.Log.d("MainActivity", "Usage permission: ${permissionManager.hasUsagePermission()}")

        permissionManager.checkAllPermissions()

        if (permissionManager.hasUsagePermission()) {
            android.util.Log.d("MainActivity", "Usage permission granted, loading apps")
            loadAllUserApps()
        } else {
            android.util.Log.d("MainActivity", "Usage permission not granted, showing placeholder")
            usageView.text = "Grant Usage Access permission to see apps"
        }
    }

    private fun initializeUI() {
        usageView = findViewById(R.id.usageTextView)
        btnStart = findViewById(R.id.startButton)
        btnStop = findViewById(R.id.stopButton)
        btnAccess = findViewById(R.id.accessibilityButton)
        btnShowAll = findViewById(R.id.showAllButton)
        btnTestData = findViewById(R.id.testDataButton)
        btnTestBroadcast = findViewById(R.id.testBroadcastButton)
        btnLoadRealApps = findViewById(R.id.loadRealAppsButton)
        btnTestOverlay = findViewById(R.id.testOverlayButton)
        usageListView = findViewById(R.id.usageRecyclerView)

        usageAdapter = IndividualUsageAdapter { packageName, minutes ->
            timeLimitManager.setTimeLimit(packageName, minutes)
        }
        usageListView.adapter = usageAdapter
        usageListView.layoutManager = LinearLayoutManager(this)

        usageListView.setHasFixedSize(false)
        usageListView.isNestedScrollingEnabled = false
    }

    private fun setupEventListeners() {
        btnShowAll.setOnClickListener {
            showAllApps = !showAllApps
            android.util.Log.d("MainActivity", "Show all apps toggled: $showAllApps")
        }

        btnStart.setOnClickListener {
            android.util.Log.d("MainActivity", "Start button clicked")
            if (permissionManager.isAccessibilityServiceEnabled()) {
                isUsingAccess = true
                registerUsageReceiver()
                usageView.text = "tracking..."
                Toast.makeText(this, "Started with accessibility service", Toast.LENGTH_SHORT).show()
                android.util.Log.d("MainActivity", "Using accessibility service")
            } else {
                // OLD SERVICE DISABLED - Using only AccessibilityService now
                android.util.Log.d("MainActivity", "Please enable Accessibility Service")
                Toast.makeText(this, "Please enable the Accessibility Service for app tracking", Toast.LENGTH_LONG).show()
                // Redirect to accessibility settings
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        btnStop.setOnClickListener {
            // OLD SERVICE DISABLED - Only using AccessibilityService now
            unregisterUsageReceiver()
            usageView.text = "stopped"
        }

        btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnTestData.setOnClickListener {
            android.util.Log.d("MainActivity", "Loading test data")
            loadTestData()
        }

        btnTestBroadcast.setOnClickListener {
            android.util.Log.d("MainActivity", "Sending test broadcast")
            sendTestBroadcast()
        }

        btnLoadRealApps.setOnClickListener {
            android.util.Log.d("MainActivity", "Loading real apps using UsageStatsManager")
            loadRealAppsData()
        }

        btnTestOverlay.setOnClickListener {
            android.util.Log.d("MainActivity", "Testing overlay manually")
            testOverlay()
        }

        val btnTestReceiver = Button(this).apply {
            text = "Test Receiver"
            setOnClickListener {
                android.util.Log.d("MainActivity", "Testing receiver registration")
                testReceiverRegistration()
            }
        }

        val btnSimulateLimit = Button(this).apply {
            text = "Simulate Limit"
            setOnClickListener {
                android.util.Log.d("MainActivity", "Simulating time limit exceeded")
                simulateTimeLimitExceeded()
            }
        }

        val btnCheckSystem = Button(this).apply {
            text = "Check System"
            setOnClickListener {
                android.util.Log.d("MainActivity", "Checking permissions and service")
                checkPermissionsAndService()
            }
        }

        val btnDismissOverlay = Button(this).apply {
            text = "Dismiss Overlay"
            setOnClickListener {
                android.util.Log.d("MainActivity", "Manually dismissing overlay")
                dismissOverlay()
            }
        }
    }

    private fun registerUsageReceiver() {
        android.util.Log.d("MainActivity", "Registering usage receiver, isReceiverOn: $isReceiverOn")
        if (!isReceiverOn) {
            try {
                val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    Context.RECEIVER_NOT_EXPORTED
                } else {
                    0
                }
                registerReceiver(
                    usageReceiver,
                    IntentFilter("com.alaotach.limini.USAGE_UPDATE"),
                    flags
                )
                isReceiverOn = true
                android.util.Log.d("MainActivity", "Usage receiver registered successfully")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to register receiver: ${e.message}")
            }
        } else {
            android.util.Log.d("MainActivity", "Receiver already registered")
        }
    }

    private fun unregisterUsageReceiver() {
        android.util.Log.d("MainActivity", "Unregistering usage receiver, isReceiverOn: $isReceiverOn")
        if (isReceiverOn) {
            try {
                unregisterReceiver(usageReceiver)
                isReceiverOn = false
                android.util.Log.d("MainActivity", "Usage receiver unregistered successfully")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to unregister receiver: ${e.message}")
                isReceiverOn = false
            }
        } else {
            android.util.Log.d("MainActivity", "Receiver already unregistered")
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1001) permissionManager.checkAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("MainActivity", "onResume called")
        registerUsageReceiver()
        permissionManager.checkAllPermissions()

        if (permissionManager.hasUsagePermission()) {
            loadAllUserApps()

            if (permissionManager.hasNotifPermission()) {
                startTrackingService()
            }
        } else {
            usageView.text = "Grant Usage Access permission to see apps"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsageReceiver()
    }

    private fun loadTestData() {
        val testItems = listOf(
            UsageItem("com.android.chrome", "Chrome", null, 120000L),
            UsageItem("com.whatsapp", "WhatsApp", null, 180000L),
            UsageItem("com.instagram.android", "Instagram", null, 300000L),
            UsageItem("com.facebook.katana", "Facebook", null, 90000L),
            UsageItem("com.spotify.music", "Spotify", null, 240000L),
            UsageItem("com.twitter.android", "Twitter", null, 150000L),
            UsageItem("com.google.android.youtube", "YouTube", null, 600000L),
            UsageItem("com.netflix.mediaclient", "Netflix", null, 45000L),
            UsageItem("com.tiktok", "TikTok", null, 480000L),
            UsageItem("com.snapchat.android", "Snapchat", null, 360000L),
            UsageItem("com.telegram.messenger", "Telegram", null, 200000L),
            UsageItem("com.discord", "Discord", null, 420000L),
            UsageItem("com.reddit.frontpage", "Reddit", null, 340000L),
            UsageItem("com.pinterest", "Pinterest", null, 280000L),
            UsageItem("com.linkedin.android", "LinkedIn", null, 100000L),
            UsageItem("com.amazon.mShop.android", "Amazon", null, 80000L),
            UsageItem("com.ebay.mobile", "eBay", null, 60000L),
            UsageItem("com.microsoft.teams", "Microsoft Teams", null, 320000L),
        )

        usageAdapter.submitList(testItems)
        btnShowAll.text = "Show All Apps (${testItems.size})"
        usageView.text = "Test data loaded - ${testItems.size} apps"

        android.util.Log.d("MainActivity", "Setting test time limit for YouTube: 1 minute")
        timeLimitManager.setTimeLimit("com.google.android.youtube", 1)

        Toast.makeText(this, "Test data loaded with test time limit on YouTube", Toast.LENGTH_SHORT).show()
    }

    private fun sendTestBroadcast() {
        android.util.Log.d("MainActivity", "Creating test broadcast")

        registerUsageReceiver()

        val testUsageList = arrayListOf(
            hashMapOf<String, Any?>(
                "packageName" to "com.test.app1",
                "appName" to "Test App 1",
                "icon" to null,
                "usageTime" to 300000L
            ),
            hashMapOf<String, Any?>(
                "packageName" to "com.test.app2",
                "appName" to "Test App 2",
                "icon" to null,
                "usageTime" to 150000L
            ),
            hashMapOf<String, Any?>(
                "packageName" to "com.test.app3",
                "appName" to "Test App 3",
                "icon" to null,
                "usageTime" to 480000L
            ),
            hashMapOf<String, Any?>(
                "packageName" to "com.test.higusage",
                "appName" to "High Usage Test App",
                "icon" to null,
                "usageTime" to 3600000L
            )
        )

        android.util.Log.d("MainActivity", "Test broadcast data created with ${testUsageList.size} items")

        val intent = Intent("com.alaotach.limini.USAGE_UPDATE")
        intent.putExtra("usageList", testUsageList)

        sendBroadcast(intent)

        android.util.Log.d("MainActivity", "Test broadcast sent with action: ${intent.action}")
        Toast.makeText(this, "Test broadcast sent with ${testUsageList.size} apps", Toast.LENGTH_SHORT).show()

        timeLimitManager.setTimeLimit("com.test.higusage", 30)
        android.util.Log.d("MainActivity", "Set 30-minute limit on High Usage Test App")
    }

    private fun loadRealAppsData() {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val packageManager = packageManager

            val endTime = System.currentTimeMillis()
            val startTime = endTime - (24 * 60 * 60 * 1000)

            val usageStats = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            android.util.Log.d("MainActivity", "Found ${usageStats?.size ?: 0} usage stats")

            if (usageStats != null && usageStats.isNotEmpty()) {
                val realItems = usageStats
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

                        UsageItem(
                            usageStat.packageName,
                            appName,
                            iconBytes,
                            usageStat.totalTimeInForeground
                        )
                    }
                    .sortedByDescending { it.usageTime }
                    .take(20)

                android.util.Log.d("MainActivity", "Processed ${realItems.size} real apps")

                realItems.forEach { item ->
                    val usageMinutes = item.usageTime / (60 * 1000)
                    android.util.Log.d("MainActivity", "App: ${item.appName} - Usage: ${usageMinutes}min (${item.usageTime}ms)")
                }

                usageAdapter.submitList(realItems)
                btnShowAll.text = "Show All Apps (${realItems.size})"
                usageView.text = "Real apps loaded - ${realItems.size} apps"
                Toast.makeText(this, "Real apps loaded successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No usage data found. Make sure Usage Access permission is granted.", Toast.LENGTH_LONG).show()
                android.util.Log.w("MainActivity", "No usage stats found")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error loading real apps: ${e.message}", e)
            Toast.makeText(this, "Error loading real apps: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadAllUserApps() {
        android.util.Log.d("MainActivity", "Loading all user apps automatically")

        if (!permissionManager.hasUsagePermission()) {
            android.util.Log.w("MainActivity", "Cannot load apps - no usage permission")
            usageView.text = "Usage Access permission required"
            return
        }

        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val packageManager = packageManager

            val endTime = System.currentTimeMillis()
            val startTime = endTime - (7 * 24 * 60 * 60 * 1000)

            val installedApps = packageManager.getInstalledApplications(0)
            android.util.Log.d("MainActivity", "Found ${installedApps.size} total installed apps")

            val usageStats = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            val usageMap = usageStats?.associate { it.packageName to it.totalTimeInForeground } ?: emptyMap()

            val userApps = installedApps
                .filter { appInfo ->
                    !isSystemApp(appInfo.packageName) &&
                    appInfo.packageName != "com.alaotach.limini" &&
                    packageManager.getLaunchIntentForPackage(appInfo.packageName) != null
                }
                .map { appInfo ->
                    val appName = try {
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        appInfo.packageName.split(".").lastOrNull()?.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase() else it.toString()
                        } ?: appInfo.packageName
                    }

                    val iconBytes = try {
                        val drawable = packageManager.getApplicationIcon(appInfo.packageName)
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

                    val usageTime = usageMap[appInfo.packageName] ?: 0L

                    UsageItem(
                        appInfo.packageName,
                        appName,
                        iconBytes,
                        usageTime
                    )
                }
                .sortedByDescending { it.usageTime }

            android.util.Log.d("MainActivity", "Loaded ${userApps.size} user apps")

            usageAdapter.submitList(userApps)
            usageView.text = "Auto-loaded ${userApps.size} user apps"

            startTrackingService()

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error loading all user apps: ${e.message}", e)
            usageView.text = "Error loading apps: ${e.message}"
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
            false
        }
    }

    private fun startTrackingService() {
        android.util.Log.d("MainActivity", "OLD SERVICE DISABLED - Only using AccessibilityService now")
        // OLD SERVICE DISABLED - Only using AccessibilityService now
        // The AccessibilityService starts automatically when enabled
    }

    private fun testOverlay() {
        android.util.Log.d("MainActivity", "Testing TRUE SYSTEM OVERLAY - checking permissions")

        if (!permissionManager.hasOverlayPermission()) {
            Toast.makeText(this, "Overlay permission not granted! Please enable 'Display over other apps'", Toast.LENGTH_LONG).show()
            permissionManager.checkAllPermissions()
            return
        }

        android.util.Log.d("MainActivity", "Overlay permission granted, launching TRUE SYSTEM OVERLAY")

        val overlayIntent = Intent(this, com.alaotach.limini.services.OverlayService::class.java).apply {
            putExtra("appName", "Test App (True Overlay)")
            putExtra("timeLimitMinutes", 5)
            putExtra("blockedPackageName", "com.test.blocked")
        }

        try {
            startService(overlayIntent)
            android.util.Log.d("MainActivity", "TRUE SYSTEM OVERLAY service started successfully")
            Toast.makeText(this, "True system overlay launched!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to launch overlay service: ${e.message}", e)
            Toast.makeText(this, "Failed to launch overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun testReceiverRegistration() {
        android.util.Log.d("MainActivity", "Testing receiver registration and broadcast")

        unregisterUsageReceiver()
        registerUsageReceiver()

        android.util.Log.d("MainActivity", "Receiver status: isReceiverOn = $isReceiverOn")

        val testData = arrayListOf(
            hashMapOf<String, Any?>(
                "packageName" to "com.test.simple",
                "appName" to "Simple Test",
                "icon" to null,
                "usageTime" to 120000L
            )
        )

        val intent = Intent("com.alaotach.limini.USAGE_UPDATE")
        intent.putExtra("usageList", testData)

        android.util.Log.d("MainActivity", "Sending simple test broadcast...")
        sendBroadcast(intent)

        Toast.makeText(this, "Receiver test sent - check logs", Toast.LENGTH_SHORT).show()
    }

    private fun simulateTimeLimitExceeded() {
        android.util.Log.d("MainActivity", "Simulating time limit exceeded")

        if (!permissionManager.hasOverlayPermission()) {
            Toast.makeText(this, "Overlay permission required! Enable 'Display over other apps'", Toast.LENGTH_LONG).show()
            permissionManager.checkAllPermissions()
            return
        }

        timeLimitManager.setTimeLimit("com.android.chrome", 0)
        timeLimitManager.setTimeLimit("com.whatsapp", 0)
        timeLimitManager.setTimeLimit("com.google.android.youtube", 0)
        timeLimitManager.setTimeLimit("com.instagram.android", 0)
        timeLimitManager.setTimeLimit("com.facebook.katana", 0)

        android.util.Log.d("MainActivity", "Set 0-minute limits (immediate block) on Chrome, WhatsApp, YouTube, Instagram, Facebook")

        val overlayIntent = Intent(this, com.alaotach.limini.services.OverlayService::class.java).apply {
            putExtra("appName", "Test App (Immediate Block)")
            putExtra("timeLimitMinutes", 0)
            putExtra("blockedPackageName", "com.android.chrome")
        }

        try {
            startService(overlayIntent)
            android.util.Log.d("MainActivity", "TRUE SYSTEM OVERLAY service started successfully")
            Toast.makeText(this, "True system overlay launched!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to launch overlay service: ${e.message}", e)
            Toast.makeText(this, "Failed to launch overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }

        Toast.makeText(this, "Set IMMEDIATE BLOCK on Chrome/WhatsApp/YouTube/Instagram/Facebook. Try using them!", Toast.LENGTH_LONG).show()
    }

    private fun checkPermissionsAndService() {
        android.util.Log.d("MainActivity", "=== PERMISSION & SERVICE CHECK ===")
        android.util.Log.d("MainActivity", "Usage permission: ${permissionManager.hasUsagePermission()}")
        android.util.Log.d("MainActivity", "Notification permission: ${permissionManager.hasNotifPermission()}")
        android.util.Log.d("MainActivity", "Overlay permission: ${permissionManager.hasOverlayPermission()}")
        android.util.Log.d("MainActivity", "Accessibility service: ${permissionManager.isAccessibilityServiceEnabled()}")

        val intent = Intent("com.alaotach.limini.CLEAR_BLOCK")
        sendBroadcast(intent)
        android.util.Log.d("MainActivity", "Sent test broadcast to service")

        val savedLimits = timeLimitManager.getAllTimeLimits()
        android.util.Log.d("MainActivity", "Saved time limits: $savedLimits")

        Toast.makeText(this, "Check logs for permissions & service status", Toast.LENGTH_SHORT).show()
    }

    private fun dismissOverlay() {
        android.util.Log.d("MainActivity", "Sending dismiss overlay broadcast")
        val dismissIntent = Intent("com.alaotach.limini.DISMISS_OVERLAY")
        sendBroadcast(dismissIntent)
        Toast.makeText(this, "Overlay dismiss broadcast sent", Toast.LENGTH_SHORT).show()
    }
}
