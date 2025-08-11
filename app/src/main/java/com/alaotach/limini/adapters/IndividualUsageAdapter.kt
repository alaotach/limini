package com.alaotach.limini.adapters

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.alaotach.limini.R
import com.alaotach.limini.data.UsageItem

class IndividualUsageAdapter(
    private val onTimeLimitChanged: (String, Int) -> Unit
) : ListAdapter<UsageItem, IndividualUsageAdapter.UsageViewHolder>(IndividualUsageDiffCallback()) {

    private val timeLimits = mutableMapOf<String, Int>()

    class UsageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconView: ImageView = view.findViewById(R.id.appIcon)
        val nameView: TextView = view.findViewById(R.id.appName)
        val timeView: TextView = view.findViewById(R.id.usageTime)
        val timeLimitSeekBar: SeekBar = view.findViewById(R.id.timeLimitSeekBar)
        val timeLimitLabel: TextView = view.findViewById(R.id.timeLimitLabel)
        val enableSwitch: Switch = view.findViewById(R.id.enableSwitch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.usage_item_with_controls, parent, false)
        return UsageViewHolder(view)
    }

    override fun onBindViewHolder(holder: UsageViewHolder, position: Int) {
        val item = getItem(position)

        if (item.icon != null && item.icon.isNotEmpty()) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(item.icon, 0, item.icon.size)
                holder.iconView.setImageBitmap(bitmap)
            } catch (e: Exception) {
                holder.iconView.setImageResource(R.drawable.ic_launcher_foreground)
            }
        } else {
            holder.iconView.setImageResource(R.drawable.ic_launcher_foreground)
        }

        holder.nameView.text = item.appName
        holder.timeView.text = formatTime(item.usageTime)

        val isTimeLimitEnabled = timeLimits.containsKey(item.packageName)
        val currentTimeLimit = if (isTimeLimitEnabled) timeLimits[item.packageName]!! else 60

        holder.timeLimitSeekBar.max = 1440 // 24 hours in minutes
        holder.timeLimitSeekBar.progress = currentTimeLimit

        // Detach listeners before setting state to prevent them from firing during bind
        holder.enableSwitch.setOnCheckedChangeListener(null)
        holder.timeLimitSeekBar.setOnSeekBarChangeListener(null)

        holder.enableSwitch.isChecked = isTimeLimitEnabled
        holder.timeLimitSeekBar.isEnabled = isTimeLimitEnabled
        holder.timeLimitLabel.alpha = if (isTimeLimitEnabled) 1.0f else 0.5f

        fun updateLabel(minutes: Int) {
            holder.timeLimitLabel.text = if (minutes >= 60) {
                "${minutes / 60}h ${minutes % 60}m"
            } else {
                "${minutes}m"
            }
        }

        if (isTimeLimitEnabled) {
            updateLabel(currentTimeLimit)
        } else {
            holder.timeLimitLabel.text = "Disabled"
        }

        // Re-attach listeners now that the view is configured
        holder.timeLimitSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val adjustedProgress = progress.coerceAtLeast(5)
                    updateLabel(adjustedProgress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val finalProgress = seekBar?.progress?.coerceAtLeast(5) ?: 5
                onTimeLimitChanged(item.packageName, finalProgress)
                setTimeLimit(item.packageName, finalProgress)
            }
        })

        holder.enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            holder.timeLimitSeekBar.isEnabled = isChecked
            holder.timeLimitLabel.alpha = if (isChecked) 1.0f else 0.5f

            if (isChecked) {
                val currentProgress = holder.timeLimitSeekBar.progress.coerceAtLeast(5)
                updateLabel(currentProgress)
                onTimeLimitChanged(item.packageName, currentProgress)
                setTimeLimit(item.packageName, currentProgress)
            } else {
                holder.timeLimitLabel.text = "Disabled"
                onTimeLimitChanged(item.packageName, Int.MAX_VALUE)
                setTimeLimit(item.packageName, Int.MAX_VALUE)
            }
        }
    }

    fun setTimeLimits(limits: Map<String, Int>) {
        timeLimits.clear()
        timeLimits.putAll(limits)
        notifyDataSetChanged()
    }

    fun setTimeLimit(packageName: String, minutes: Int) {
        val needsUpdate: Boolean
        if (minutes == Int.MAX_VALUE) {
            needsUpdate = timeLimits.remove(packageName) != null
        } else {
            val oldLimit = timeLimits.put(packageName, minutes)
            needsUpdate = oldLimit != minutes
        }

        if (needsUpdate) {
            val index = currentList.indexOfFirst { it.packageName == packageName }
            if (index != -1) {
                // Post to handler to ensure the update runs on the main thread
                // and doesn't interfere with RecyclerView's layout pass.
                Handler(Looper.getMainLooper()).post {
                    notifyItemChanged(index)
                }
            }
        }
    }

    fun getTimeLimit(packageName: String): Int {
        return timeLimits[packageName] ?: 60
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes > 0) {
            String.format("%dm %ds", minutes, remainingSeconds)
        } else {
            String.format("%ds", remainingSeconds)
        }
    }
}

class IndividualUsageDiffCallback : DiffUtil.ItemCallback<UsageItem>() {
    override fun areItemsTheSame(oldItem: UsageItem, newItem: UsageItem): Boolean {
        return oldItem.packageName == newItem.packageName
    }

    override fun areContentsTheSame(oldItem: UsageItem, newItem: UsageItem): Boolean {
        return oldItem == newItem
    }
}
