package com.raofflineproxy.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.raofflineproxy.R
import com.raofflineproxy.proxy.WatchedFolder
import java.text.DateFormat
import java.util.Date

class WatchFoldersAdapter(
    private val onToggleWatching: (WatchedFolder) -> Unit,
    private val onRemove: (WatchedFolder) -> Unit
) : ListAdapter<WatchedFolder, WatchFoldersAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tv_folder_name)
        private val path: TextView = view.findViewById(R.id.tv_folder_path)
        private val status: TextView = view.findViewById(R.id.tv_folder_status)
        private val watch: MaterialButton = view.findViewById(R.id.btn_folder_watch)
        private val remove: MaterialButton = view.findViewById(R.id.btn_folder_remove)

        fun bind(folder: WatchedFolder) {
            val context = itemView.context
            name.text = folder.name.ifBlank { folder.uri }
            path.text = folder.path
            path.visibility = if (folder.path.isNullOrBlank()) View.GONE else View.VISIBLE
            status.text = when {
                !folder.watching -> context.getString(R.string.watch_folders_not_watching)
                folder.lastScanAt == 0L -> context.getString(R.string.watch_folders_not_checked)
                else -> context.getString(
                    R.string.watch_folders_last_checked,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(folder.lastScanAt))
                )
            }
            val label = context.getString(if (folder.watching) R.string.watch_folders_stop else R.string.watch_folders_start)
            watch.setIconResource(if (folder.watching) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
            watch.iconTint = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(
                    watch,
                    if (folder.watching) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface
                )
            )
            watch.contentDescription = label
            watch.tooltipText = label
            watch.setOnClickListener { onToggleWatching(folder) }
            remove.setOnClickListener { onRemove(folder) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_watch_folder, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<WatchedFolder>() {
        override fun areItemsTheSame(old: WatchedFolder, new: WatchedFolder) = old.uri == new.uri
        override fun areContentsTheSame(old: WatchedFolder, new: WatchedFolder) = old == new
    }
}
