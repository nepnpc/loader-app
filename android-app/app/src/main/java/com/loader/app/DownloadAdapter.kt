package com.loader.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DownloadAdapter(
    private val items: List<DownloadItem>,
    private val onRemove: (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView     = view.findViewById(R.id.tv_dl_title)
        val tvFormat: TextView    = view.findViewById(R.id.tv_dl_format)
        val tvStatus: TextView    = view.findViewById(R.id.tv_dl_status)
        val progress: ProgressBar = view.findViewById(R.id.pb_dl)
        val btnRemove: ImageButton = view.findViewById(R.id.btn_dl_remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.tvTitle.text = item.title
        h.tvFormat.text = item.format.label

        when (item.status) {
            DownloadStatus.QUEUED -> {
                h.tvStatus.text = "Queued"
                h.progress.visibility = View.VISIBLE
                h.progress.isIndeterminate = true
            }
            DownloadStatus.DOWNLOADING -> {
                h.tvStatus.text = "${item.progress}%"
                h.progress.visibility = View.VISIBLE
                h.progress.isIndeterminate = false
                h.progress.progress = item.progress
            }
            DownloadStatus.DONE -> {
                h.tvStatus.text = "Done ✓"
                h.tvStatus.setTextColor(h.itemView.context.getColor(R.color.success))
                h.progress.visibility = View.GONE
            }
            DownloadStatus.FAILED -> {
                val msg = if (item.errorMsg.isNotEmpty()) "Failed: ${item.errorMsg}" else "Failed"
                h.tvStatus.text = msg
                h.tvStatus.setTextColor(h.itemView.context.getColor(R.color.error))
                h.progress.visibility = View.GONE
            }
        }

        h.btnRemove.setOnClickListener { onRemove(item) }
    }
}
