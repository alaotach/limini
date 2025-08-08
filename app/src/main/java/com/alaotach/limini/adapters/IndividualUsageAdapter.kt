package com.alaotach.limini.adapters

import android.graphics.BitmapFactory
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

        val currentTimeLimit = timeLimits[item.packageName] ?: 60

        holder.timeLimitSeekBar.max = 180
        holder.timeLimitSeekBar.progress = currentTimeLimit
        holder.timeLimitLabel.text = "${currentTimeLimit} min"

        val isTimeLimitEnabled = timeLimits.containsKey(item.packageName)
        holder.enableSwitch.isChecked = isTimeLimitEnabled

        holder.timeLimitSeekBar.isEnabled = isTimeLimitEnabled
        holder.timeLimitLabel.alpha = if (isTimeLimitEnabled) 1.0f else 0.5f

        if (isTimeLimitEnabled) {
            val timeLimitMs = currentTimeLimit * 60 * 1000L
            val usagePercentage = (item.usageTime.toFloat() / timeLimitMs * 100).toInt()

            when {
                usagePercentage >= 100 -> {
                    holder.timeView.setTextColor(0xFFFF0000.toInt())
                    holder.nameView.setTextColor(0xFFFF0000.toInt())
                }
                usagePercentage >= 80 -> {
                    holder.timeView.setTextColor(0xFFFF8C00.toInt())
                    holder.nameView.setTextColor(0xFFFF8C00.toInt())
                }
                usagePercentage >= 60 -> {
                    holder.timeView.setTextColor(0xFFFFD700.toInt())
                    holder.nameView.setTextColor(0xFFFFD700.toInt())
                }
                else -> {
                    holder.timeView.setTextColor(0xFF000000.toInt())
                    holder.nameView.setTextColor(0xFF000000.toInt())
                }
            }
        } else {
            holder.timeView.setTextColor(0xFF000000.toInt())
            holder.nameView.setTextColor(0xFF000000.toInt())
        }

        holder.timeLimitSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && holder.enableSwitch.isChecked) {
                    val newLimit = maxOf(5, progress)
                    holder.timeLimitLabel.text = "${newLimit} min"
                    timeLimits[item.packageName] = newLimit
                    onTimeLimitChanged(item.packageName, newLimit)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        holder.enableSwitch.setOnCheckedChangeListener(null)
        holder.enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            holder.timeLimitSeekBar.isEnabled = isChecked
            holder.timeLimitLabel.alpha = if (isChecked) 1.0f else 0.5f

            if (isChecked) {
                val timeLimit = maxOf(5, holder.timeLimitSeekBar.progress)
                timeLimits[item.packageName] = timeLimit
                onTimeLimitChanged(item.packageName, timeLimit)
            } else {
                timeLimits.remove(item.packageName)
                onTimeLimitChanged(item.packageName, Int.MAX_VALUE)
            }
        }
    }

    fun setTimeLimit(packageName: String, minutes: Int) {
        timeLimits[packageName] = minutes
        notifyDataSetChanged()
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
