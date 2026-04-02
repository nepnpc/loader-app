package com.loader.app

import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.*
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.loader.app.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val downloads = mutableListOf<DownloadItem>()
    private lateinit var adapter: DownloadAdapter
    private var currentInfo: VideoInfo? = null
    private var currentUrl: String = ""

    // ── Preferences ──────────────────────────────────────────────────────────
    private val prefs by lazy { getSharedPreferences("loader", MODE_PRIVATE) }

    private var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE_URL)!!
        set(v) = prefs.edit().putString("base_url", v).apply()

    companion object {
        // ⚠ Change this to your deployed backend URL after deployment
        const val DEFAULT_BASE_URL = "https://loader-app-production.up.railway.app"
    }

    // ── Download completion receiver ──────────────────────────────────────────
    private val dlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            downloads.find { it.downloadId == id }?.let { item ->
                item.status = DownloadStatus.DONE
                item.progress = 100
                adapter.notifyDataSetChanged()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupListeners()
        registerReceiver(dlReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))

        // Handle share-intent (URL shared from browser)
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                val url = extractUrl(text)
                if (url.isNotEmpty()) binding.etUrl.setText(url)
            }
        } else {
            pasteIfUrlInClipboard()
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupRecycler() {
        adapter = DownloadAdapter(downloads) { item ->
            removeDownload(item)
        }
        binding.rvDownloads.layoutManager = LinearLayoutManager(this)
        binding.rvDownloads.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnFetch.setOnClickListener { fetchInfo() }
        binding.btnServer.setOnClickListener { showServerDialog() }
        binding.btnDownloadVideo.setOnClickListener { showFormatSheet("video") }
        binding.btnDownloadAudio.setOnClickListener { showFormatSheet("audio") }
    }

    // ── Clipboard ─────────────────────────────────────────────────────────────

    private fun pasteIfUrlInClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        if (text.startsWith("http://") || text.startsWith("https://")) {
            binding.etUrl.setText(text)
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        binding.etUrl.setText(text)
        binding.etUrl.setSelection(text.length)
    }

    // ── Fetch video info ──────────────────────────────────────────────────────

    private fun fetchInfo() {
        val url = binding.etUrl.text.toString().trim()
        if (url.isEmpty()) {
            binding.etUrl.error = "Paste a URL first"
            return
        }
        if (!url.startsWith("http")) {
            binding.etUrl.error = "Invalid URL"
            return
        }

        // Check backend URL configured
        if (baseUrl == DEFAULT_BASE_URL) {
            showServerDialog("Set your backend URL before fetching")
            return
        }

        hideKeyboard()
        currentUrl = url
        setFetchLoading(true)
        binding.cardResult.visibility = View.GONE

        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { ApiClient.getInfo(url, baseUrl) }
                currentInfo = info
                showResult(info)
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            } finally {
                setFetchLoading(false)
            }
        }
    }

    private fun setFetchLoading(loading: Boolean) {
        binding.btnFetch.isEnabled = !loading
        binding.progressFetch.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showResult(info: VideoInfo) {
        binding.cardResult.visibility = View.VISIBLE
        binding.tvTitle.text = info.title
        binding.tvMeta.text = buildString {
            if (info.uploader.isNotEmpty()) append(info.uploader)
            if (info.duration > 0) {
                if (isNotEmpty()) append("  ·  ")
                append(formatDuration(info.duration))
            }
        }

        Glide.with(this)
            .load(info.thumbnail)
            .centerCrop()
            .placeholder(R.drawable.bg_thumb_placeholder)
            .into(binding.ivThumbnail)

        binding.btnDownloadVideo.visibility =
            if (info.formats.any { it.type == "video" }) View.VISIBLE else View.GONE
        binding.btnDownloadAudio.visibility =
            if (info.formats.any { it.type == "audio" }) View.VISIBLE else View.GONE

        // Auto-scroll to result
        binding.scrollView.post {
            binding.scrollView.smoothScrollTo(0, binding.cardResult.top)
        }
    }

    // ── Format selection bottom sheet ─────────────────────────────────────────

    private fun showFormatSheet(type: String) {
        val info = currentInfo ?: return
        val formats = info.formats.filter { it.type == type }
        if (formats.isEmpty()) { toast("No $type formats available"); return }

        val sheet = BottomSheetDialog(this, R.style.BottomSheetStyle)
        val view = layoutInflater.inflate(R.layout.sheet_formats, null)
        sheet.setContentView(view)

        view.findViewById<TextView>(R.id.tv_sheet_title).text = info.title
        view.findViewById<TextView>(R.id.tv_sheet_type).text =
            if (type == "video") "Select Video Quality" else "Select Audio Quality"

        val container = view.findViewById<LinearLayout>(R.id.ll_formats)
        formats.forEach { fmt ->
            val btn = layoutInflater.inflate(R.layout.item_format_btn, container, false)
            btn.findViewById<TextView>(R.id.tv_fmt_label).text = fmt.label
            val sizeLabel = fmt.filesizeLabel()
            btn.findViewById<TextView>(R.id.tv_fmt_size).text = sizeLabel
            btn.setOnClickListener {
                sheet.dismiss()
                startDownload(currentUrl, info.title, fmt)
            }
            container.addView(btn)
        }

        sheet.show()
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun startDownload(videoUrl: String, title: String, format: Format) {
        val downloadUrl = ApiClient.buildDownloadUrl(videoUrl, format.format_id, baseUrl)
        val safeTitle = sanitizeFilename(title)
        val fileName = "$safeTitle.${format.ext}"

        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle(title)
            setDescription(format.label)
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType(if (format.type == "audio") "audio/*" else "video/*")
        }

        val dlId = dm.enqueue(request)
        val item = DownloadItem(
            sourceUrl = videoUrl,
            title = title,
            format = format,
            downloadId = dlId,
            status = DownloadStatus.DOWNLOADING
        )
        downloads.add(0, item)
        updateList()
        toast("Download started")

        pollProgress(item)
    }

    private fun pollProgress(item: DownloadItem) {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        scope.launch(Dispatchers.IO) {
            while (item.status == DownloadStatus.DOWNLOADING) {
                delay(600)
                val cursor = dm.query(
                    DownloadManager.Query().setFilterById(item.downloadId)
                )
                if (cursor.moveToFirst()) {
                    val colBytes = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val colTotal = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val colStatus = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val colReason = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

                    val bytes = if (colBytes >= 0) cursor.getLong(colBytes) else 0L
                    val total = if (colTotal >= 0) cursor.getLong(colTotal) else 0L
                    val status = if (colStatus >= 0) cursor.getInt(colStatus) else 0
                    val reason = if (colReason >= 0) cursor.getInt(colReason) else 0

                    if (total > 0) item.progress = ((bytes * 100) / total).toInt()

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            item.status = DownloadStatus.DONE
                            item.progress = 100
                        }
                        DownloadManager.STATUS_FAILED -> {
                            item.status = DownloadStatus.FAILED
                            item.errorMsg = "Code $reason"
                        }
                    }
                }
                cursor.close()
                withContext(Dispatchers.Main) { adapter.notifyDataSetChanged() }
            }
        }
    }

    private fun removeDownload(item: DownloadItem) {
        if (item.downloadId != -1L && item.status == DownloadStatus.DOWNLOADING) {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(item.downloadId)
        }
        downloads.remove(item)
        updateList()
    }

    private fun updateList() {
        binding.tvEmptyDownloads.visibility =
            if (downloads.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ── Server URL dialog ─────────────────────────────────────────────────────

    private fun showServerDialog(message: String = "") {
        val input = EditText(this).apply {
            setText(baseUrl)
            hint = "https://your-app.railway.app"
            setSingleLine()
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this, R.style.DialogStyle)
            .setTitle("Backend Server URL")
            .apply { if (message.isNotEmpty()) setMessage(message) }
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString().trimEnd('/')
                if (url.startsWith("http")) {
                    baseUrl = url
                    toast("Saved")
                } else {
                    toast("Invalid URL")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun sanitizeFilename(name: String) =
        name.replace(Regex("[^\\w\\s\\-]"), "_").trim().take(50)

    private fun extractUrl(text: String): String {
        val regex = Regex("https?://\\S+")
        return regex.find(text)?.value ?: ""
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        unregisterReceiver(dlReceiver)
    }
}
