package com.alaotach.limini

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        setContentView(R.layout.activity_lock)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        val appName = intent.getStringExtra("appName") ?: "Unknown App"
        val timeLimitMinutes = intent.getIntExtra("timeLimitMinutes", 60)

        android.util.Log.d("LockActivity", "ðŸ”’ LOCK SCREEN SHOWN IMMEDIATELY for $appName (${timeLimitMinutes}min limit)")
        val titleText = findViewById<TextView>(R.id.lockTitle)
        val messageText = findViewById<TextView>(R.id.lockMessage)
        val backButton = findViewById<Button>(R.id.backToHomeButton)
        val openLiminiButton = findViewById<Button>(R.id.openLiminiButton)

        titleText.text = "â° Time Limit Reached"
        messageText.text = "$appName has exceeded the $timeLimitMinutes minute limit.\n\nTake a break and come back later!"

        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        backButton.setOnClickListener {
            goHome()
        }

        openLiminiButton.setOnClickListener {
            openLimini()
        }
    }

    override fun onBackPressed() {
    }

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun openLimini() {
        val liminiIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(liminiIntent)
        finish()
    }
}
