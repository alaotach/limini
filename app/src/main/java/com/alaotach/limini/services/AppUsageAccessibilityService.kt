package com.alaotach.limini.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.os.Handler
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.core.os.postDelayed
import java.text.SimpleDateFormat
import java.util.*

class AppUsageAccessibilityService : AccessibilityService() {

    private var currPkg = ""
    private var currApp = ""
    private var startTime = 0L
    private var currTime = 0L
    private var h: Handler? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val newPkg = event.packageName?.toString() ?: return
            if (newPkg != currPkg) {
                val prevPkg = currPkg
                val prevApp = currApp
                val dur = if (startTime != 0L) System.currentTimeMillis() - startTime else 0L
                currPkg = newPkg
                currApp = getAppLabel(newPkg)
                startTime = System.currentTimeMillis()
                currTime = 0L
                sendUpdate(prevPkg, prevApp, dur)
                Log.d("AccessibilityService", "switch $prevApp -> $currApp")
            }
        }
    }

    private fun sendUpdate(prevPkg: String, prevApp: String, dur: Long) {
        val now = System.currentTimeMillis()
        val sess = if (startTime != 0L) now - startTime else 0L
        val msg = makeText(sess, prevApp, dur)

        Log.d("AccessibilityService", msg)

        val i = Intent("com.alaotach.limini.USAGE_UPDATE")
        i.putExtra("usage", msg)
        i.putExtra("currentApp", currApp)
        i.putExtra("currentPackage", currPkg)
        i.putExtra("sessionTime", sess)
        i.putExtra("previousApp", prevApp)
        i.putExtra("previousSessionTime", dur)
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun makeText(sess: Long, prevApp: String?, prevDur: Long?): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val s = StringBuilder()
        s.append("⏱ $time\n\n")

        if (currApp.isNotEmpty()) {
            s.append("Now: $currApp\n")
            if (sess > 1000) {
                val m = sess / 60000
                val s1 = (sess % 60000) / 1000
                s.append("Time: ${if (m > 0) "${m}m " else ""}${s1}s\n")
            } else {
                s.append("New app\n")
            }
            s.append("Pkg: $currPkg\n")
        } else {
            s.append("Finding app...\n")
        }

        if (!prevApp.isNullOrEmpty() && prevDur != null && prevDur > 0) {
            val m = prevDur / 60000
            val s1 = (prevDur % 60000) / 1000
            s.append("\nBefore: $prevApp\n")
            s.append("Time: ${if (m > 0) "${m}m " else ""}${s1}s\n")
        }

        return s.toString()
    }

    private fun getAppLabel(pkg: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg.split(".").lastOrNull()?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                ?: pkg
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AccessibilityService", "connected")

        startTime = System.currentTimeMillis()
        h = Handler(mainLooper)
        h?.postDelayed(object : Runnable {
            override fun run() {
                sendUpdate(currPkg, currApp, System.currentTimeMillis() - startTime)
                h?.postDelayed(this, 5000)
            }
        }, 5000)
    }

    override fun onInterrupt() {
        Log.d("AccessibilityService", "interrupted")
    }
}
