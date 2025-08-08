package com.alaotach.limini.adapters

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.alaotach.limini.R
import com.alaotach.limini.data.UsageItem

class UsageAdapter : ListAdapter<UsageItem, UsageAdapter.UsageViewHolder>(UsageDiffCallback()) {

    class UsageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconView: ImageView = view.findViewById(R.id.appIcon)
        val nameView: TextView = view.findViewById(R.id.appName)
        val timeView: TextView = view.findViewById(R.id.usageTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.usage_item, parent, false)
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

class UsageDiffCallback : DiffUtil.ItemCallback<UsageItem>() {
    override fun areItemsTheSame(oldItem: UsageItem, newItem: UsageItem): Boolean {
        return oldItem.packageName == newItem.packageName
    }

    override fun areContentsTheSame(oldItem: UsageItem, newItem: UsageItem): Boolean {
        return oldItem == newItem
    }
}
