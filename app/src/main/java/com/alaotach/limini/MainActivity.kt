package com.alaotach.limini

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.content.*
import android.widget.*
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.alaotach.limini.adapters.IndividualUsageAdapter
import com.alaotach.limini.data.UsageItem
import com.alaotach.limini.utils.PermissionManager
import com.alaotach.limini.utils.TimeLimitManager
import android.provider.Settings
import android.util.Log
import android.app.usage.UsageStatsManager
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    
    private lateinit var statusTextView: TextView
    private lateinit var usageTextView: TextView
    private lateinit var permissionCard: CardView
    private lateinit var permissionStatusText: TextView
    private lateinit var setupPermissionsButton: Button
    private lateinit var appCountText: TextView
    private lateinit var usageRecyclerView: RecyclerView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var settingsButton: ImageButton
    
    private lateinit var permissionManager: PermissionManager
    private lateinit var timeLimitManager: TimeLimitManager
    private lateinit var usageAdapter: IndividualUsageAdapter
    
    private var isReceiverOn = false
    private var isMonitoring = false

    private val usageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MainActivity", "Usage data received")
            
            @Suppress("UNCHECKED_CAST", "DEPRECATION")
            val usageList: ArrayList<HashMap<String, Any?>>? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent?.getSerializableExtra("usageList", ArrayList::class.java) as? ArrayList<HashMap<String, Any?>>
            } else {
                intent?.getSerializableExtra("usageList") as? ArrayList<HashMap<String, Any?>>
            }

            if (usageList != null && usageList.isNotEmpty()) {
                updateAppsList(usageList)
            }
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
        
        updatePermissionStatus()
        loadUserApps()
        
        Log.d("MainActivity", "Limini app initialized")
    }

    private fun initializeUI() {
        statusTextView = findViewById(R.id.statusTextView)
        usageTextView = findViewById(R.id.usageTextView)
        permissionCard = findViewById(R.id.permissionCard)
        permissionStatusText = findViewById(R.id.permissionStatusText)
        setupPermissionsButton = findViewById(R.id.setupPermissionsButton)
        appCountText = findViewById(R.id.appCountText)
        usageRecyclerView = findViewById(R.id.usageRecyclerView)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        settingsButton = findViewById(R.id.settingsButton)
        
        // Setup RecyclerView
        usageAdapter = IndividualUsageAdapter { packageName, minutes ->
            timeLimitManager.setTimeLimit(packageName, minutes)
            Log.d("MainActivity", "Time limit set for $packageName: $minutes minutes")
        }
        usageRecyclerView.adapter = usageAdapter
        usageRecyclerView.layoutManager = LinearLayoutManager(this)
        
        // Initial UI state
        updateUIState()
    }

    private fun setupEventListeners() {
        setupPermissionsButton.setOnClickListener {
            Log.d("MainActivity", "Grant Permissions button clicked")
            grantNextPermission()
        }
        
        startButton.setOnClickListener {
            startMonitoring()
        }
        
        stopButton.setOnClickListener {
            stopMonitoring()
        }
        
        settingsButton.setOnClickListener {
            Log.d("MainActivity", "Settings button clicked")
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }
    
    private fun grantNextPermission() {
        when {
            !permissionManager.hasUsagePermission() -> {
                Log.d("MainActivity", "Requesting Usage Access permission")
                Toast.makeText(this, "Step 1: Grant Usage Access permission", Toast.LENGTH_SHORT).show()
                permissionManager.requestUsagePermission()
            }
            !permissionManager.hasNotifPermission() -> {
                Log.d("MainActivity", "Requesting Notification permission")
                Toast.makeText(this, "Step 2: Grant Notification permission", Toast.LENGTH_SHORT).show()
                permissionManager.requestNotifPermission()
            }
            !permissionManager.hasOverlayPermission() -> {
                Log.d("MainActivity", "Requesting Overlay permission")
                Toast.makeText(this, "Step 3: Grant Display over other apps permission", Toast.LENGTH_SHORT).show()
                permissionManager.requestOverlayPermission()
            }
            !permissionManager.isAccessibilityServiceEnabled() -> {
                Log.d("MainActivity", "Requesting Accessibility Service")
                Toast.makeText(this, "Step 4: Enable Accessibility Service", Toast.LENGTH_SHORT).show()
                permissionManager.requestAccessibilityPermission()
            }
            else -> {
                Log.d("MainActivity", "All permissions granted!")
                Toast.makeText(this, "All permissions granted! You can now start monitoring.", Toast.LENGTH_LONG).show()
                updatePermissionStatus()
                updateUIState()
            }
        }
    }

    private fun updatePermissionStatus() {
        val hasUsage = permissionManager.hasUsagePermission()
        val hasNotif = permissionManager.hasNotifPermission()
        val hasOverlay = permissionManager.hasOverlayPermission()
        val hasAccessibility = permissionManager.isAccessibilityServiceEnabled()
        
        if (hasUsage && hasNotif && hasOverlay && hasAccessibility) {
            permissionCard.visibility = android.view.View.GONE
            statusTextView.text = "ready to Monitor"
            usageTextView.text = "All permissions granted. You can now start monitoring your app usage."
        } else {
            permissionCard.visibility = android.view.View.VISIBLE
            statusTextView.text = "Setup Required"
            val statusBuilder = StringBuilder()
            val totalSteps = 4
            var currentStep = 1
            
            if (!hasUsage) {
                statusBuilder.append("Step $currentStep/$totalSteps: Usage Access permission needed")
                setupPermissionsButton.text = "Grant Usage Access"
            } else {
                statusBuilder.append("Usage Access granted\n")
                currentStep++
                
                if (!hasNotif) {
                    statusBuilder.append("Step $currentStep/$totalSteps: Notification permission needed")
                    setupPermissionsButton.text = "Grant Notifications"
                } else {
                    statusBuilder.append("Notifications granted\n")
                    currentStep++
                    
                    if (!hasOverlay) {
                        statusBuilder.append("Step $currentStep/$totalSteps: Display over other apps permission needed")
                        setupPermissionsButton.text = "Grant Overlay Permission"
                    } else {
                        statusBuilder.append(" Display over other apps granted\n")
                        currentStep++
                        
                        if (!hasAccessibility) {
                            statusBuilder.append("Step $currentStep/$totalSteps: Accessibility Service needed")
                            setupPermissionsButton.text = "Enable Accessibility Service"
                        }
                    }
                }
            }
            
            permissionStatusText.text = statusBuilder.toString().trim()
            usageTextView.text = "Follow the step-by-step setup to grant all required permissions for app monitoring and time limits."
        }
    }

    private fun startMonitoring() {
        if (!allPermissionsGranted()) {
            updatePermissionStatus()
            Toast.makeText(this, "Please grant all required permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        
        registerUsageReceiver()
        isMonitoring = true
        updateUIState()
        
        statusTextView.text = "Monitoring Active"
        usageTextView.text = "Monitoring your app usage and enforcing time limits..."
        
        Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Monitoring started")
    }

    private fun stopMonitoring() {
        unregisterUsageReceiver()
        isMonitoring = false
        updateUIState()
        
        statusTextView.text = "Monitoring Stopped"
        usageTextView.text = "App monitoring has been paused. Your apps are accessible without time limits."
        
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Monitoring stopped")
    }

    private fun updateUIState() {
        startButton.isEnabled = !isMonitoring && allPermissionsGranted()
        stopButton.isEnabled = isMonitoring
        
        if (isMonitoring) {
            startButton.text = "Monitoring Active"
            startButton.alpha = 0.6f
            stopButton.alpha = 1.0f
        } else {
            startButton.text = "Start Monitoring"
            startButton.alpha = 1.0f
            stopButton.alpha = 0.6f
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return permissionManager.hasUsagePermission() &&
               permissionManager.hasNotifPermission() &&
               permissionManager.hasOverlayPermission() &&
               permissionManager.isAccessibilityServiceEnabled()
    }

    private fun registerUsageReceiver() {
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
                Log.d("MainActivity", "Usage receiver registered")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to register receiver: ${e.message}")
            }
        }
    }

    private fun unregisterUsageReceiver() {
        if (isReceiverOn) {
            try {
                unregisterReceiver(usageReceiver)
                isReceiverOn = false
                Log.d("MainActivity", "Usage receiver unregistered")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to unregister receiver: ${e.message}")
                isReceiverOn = false
            }
        }
    }

    private fun loadUserApps() {
        if (!permissionManager.hasUsagePermission()) {
            Log.w("MainActivity", "Cannot load apps - no usage permission")
            appCountText.text = "0 apps"
            return
        }

        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val packageManager = packageManager

            val endTime = System.currentTimeMillis()
            val startTime = endTime - (7 * 24 * 60 * 60 * 1000L) // Last 7 days

            val installedApps = packageManager.getInstalledApplications(0)
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            val usageMap = usageStats?.associate { it.packageName to it.totalTimeInForeground } ?: emptyMap()

            val userApps = installedApps
                .filter { appInfo ->
                    !isSystemApp(appInfo.packageName) &&
                    appInfo.packageName != packageName &&
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
                        val bitmap = Bitmap.createBitmap(
                            drawable.intrinsicWidth.coerceAtLeast(1),
                            drawable.intrinsicHeight.coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)

                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
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

            usageAdapter.submitList(userApps)
            appCountText.text = "${userApps.size} apps"
            
            Log.d("MainActivity", "Loaded ${userApps.size} user apps")

        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading apps: ${e.message}", e)
            appCountText.text = "Error loading apps"
        }
    }

    private fun updateAppsList(usageList: ArrayList<HashMap<String, Any?>>) {
        val items = usageList.map {
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
        }.sortedByDescending { it.usageTime }

        usageAdapter.submitList(items)
        appCountText.text = "${items.size} apps"
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
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

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume called - checking permissions")
        updatePermissionStatus()
        if (permissionManager.hasUsagePermission()) {
            loadUserApps()
        }
        if (isMonitoring) {
            registerUsageReceiver()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsageReceiver()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            updatePermissionStatus()
        }
    }
}