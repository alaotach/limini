package com.alaotach.limini.data

data class AppTimeLimitSettings(
    val packageName: String,
    val appName: String,
    var timeLimitMinutes: Int = 60,
    var isEnabled: Boolean = true
)
