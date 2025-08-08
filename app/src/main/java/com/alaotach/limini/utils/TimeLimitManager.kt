package com.alaotach.limini.utils

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.alaotach.limini.LockActivity
import com.alaotach.limini.data.UsageItem

class TimeLimitManager(private val activity: AppCompatActivity) {

    private val individualTimeLimits = mutableMapOf<String, Int>()
    private val sharedPrefs = activity.getSharedPreferences("time_limits", Context.MODE_PRIVATE)

    fun checkTimeLimits(items: List<UsageItem>) {
        for (item in items) {
            val timeLimitMinutes = individualTimeLimits[item.packageName] ?: 60

            if (timeLimitMinutes == Int.MAX_VALUE) continue

            val timeLimitMs = timeLimitMinutes * 60 * 1000L

            if (item.usageTime >= timeLimitMs) {
                showTimeLimitAlert(item.appName, item.packageName, timeLimitMinutes)
                closeApp(item.packageName, timeLimitMinutes)
            }
        }
    }

    fun setTimeLimit(packageName: String, minutes: Int) {
        individualTimeLimits[packageName] = minutes
        sharedPrefs.edit()
            .putInt(packageName, minutes)
            .apply()
        android.util.Log.d("TimeLimitManager", "Set time limit for $packageName: $minutes minutes")
    }

    fun getTimeLimit(packageName: String): Int {
        return individualTimeLimits[packageName]
            ?: sharedPrefs.getInt(packageName, 60)
    }

    fun loadTimeLimits(): Map<String, Int> {
        val allPrefs = sharedPrefs.all
        for ((key, value) in allPrefs) {
            if (value is Int) {
                individualTimeLimits[key] = value
            }
        }
        return individualTimeLimits.toMap()
    }

    fun getAllTimeLimits(): Map<String, Int> {
        return individualTimeLimits.toMap()
    }

    private fun showTimeLimitAlert(appName: String, packageName: String, timeLimitMinutes: Int) {
        val message = "$appName has reached the $timeLimitMinutes minute limit and will be closed."
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

        AlertDialog.Builder(activity)
            .setTitle("Time Limit Reached")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun closeApp(packageName: String, timeLimitMinutes: Int) {
        try {
            val lockIntent = Intent(activity, LockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("appName", getAppNameFromPackage(packageName))
                putExtra("timeLimitMinutes", timeLimitMinutes)
            }
            activity.startActivity(lockIntent)

        } catch (e: Exception) {
            android.util.Log.e("TimeLimitCheck", "Could not launch lock activity: ${e.message}")
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(homeIntent)
        }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = activity.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.split(".").lastOrNull()?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            } ?: packageName
        }
    }
}
