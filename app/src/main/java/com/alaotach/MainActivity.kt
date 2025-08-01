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
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    private lateinit var usageView: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnAccess: Button
    private lateinit var usageListView: RecyclerView
    private var isReceiverOn = false
    private var isUsingAccess = false
    private lateinit var usageAdapter: UsageAdapter

    data class UsageItem(
        val packageName: String,
        val appName: String,
        val icon: ByteArray?,
        val usageTime: Long
    )

    private val usageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            @Suppress("UNCHECKED_CAST", "DEPRECATION")
            val usageList: ArrayList<HashMap<String, Any?>>? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent?.getSerializableExtra("usageList", ArrayList::class.java) as? ArrayList<HashMap<String, Any?>>
            } else {
                intent?.getSerializableExtra("usageList") as? ArrayList<HashMap<String, Any?>>
            }
            if (usageList != null) {
                val items = usageList.map {
                    UsageItem(
                        it["packageName"] as? String ?: "",
                        it["appName"] as? String ?: "",
                        it["icon"] as? ByteArray?,
                        (it["usageTime"] as? Long) ?: 0L
                    )
                }
                usageAdapter.submitList(items)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        usageView = findViewById(R.id.usageTextView)
        btnStart = findViewById(R.id.startButton)
        btnStop = findViewById(R.id.stopButton)
        btnAccess = findViewById(R.id.accessibilityButton)
        usageListView = findViewById(R.id.usageRecyclerView)
        usageAdapter = UsageAdapter()
        usageListView.adapter = usageAdapter
        usageListView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

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

    class UsageAdapter : androidx.recyclerview.widget.ListAdapter<UsageItem, UsageAdapter.UsageViewHolder>(object : androidx.recyclerview.widget.DiffUtil.ItemCallback<UsageItem>() {
        override fun areItemsTheSame(oldItem: UsageItem, newItem: UsageItem) = oldItem.packageName == newItem.packageName
        override fun areContentsTheSame(oldItem: UsageItem, newItem: UsageItem) = oldItem == newItem
    }) {
        class UsageViewHolder(val view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val iconView: ImageView = view.findViewById(R.id.appIcon)
            val nameView: TextView = view.findViewById(R.id.appName)
            val timeView: TextView = view.findViewById(R.id.usageTime)
        }
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): UsageViewHolder {
            val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.usage_item, parent, false)
            return UsageViewHolder(v)
        }
        override fun onBindViewHolder(holder: UsageViewHolder, position: Int) {
            val item = getItem(position)
            if (item.icon != null) {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(item.icon, 0, item.icon.size)
                holder.iconView.setImageBitmap(bmp)
            } else {
                holder.iconView.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            holder.nameView.text = item.appName
            holder.timeView.text = formatTime(item.usageTime)
        }
        private fun formatTime(ms: Long): String {
            val s = ms / 1000
            val m = s / 60
            val s1 = s % 60
            return if (m > 0) String.format("%dm %ds", m, s1) else String.format("%ds", s1)
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
