package com.alaotach.limini.data

data class UsageItem(
    val packageName: String,
    val appName: String,
    val icon: ByteArray?,
    val usageTime: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UsageItem

        if (packageName != other.packageName) return false
        if (appName != other.appName) return false
        if (icon != null) {
            if (other.icon == null) return false
            if (!icon.contentEquals(other.icon)) return false
        } else if (other.icon != null) return false
        if (usageTime != other.usageTime) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + appName.hashCode()
        result = 31 * result + (icon?.contentHashCode() ?: 0)
        result = 31 * result + usageTime.hashCode()
        return result
    }
}
