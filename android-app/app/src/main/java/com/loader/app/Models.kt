package com.loader.app

import java.util.UUID

data class VideoInfo(
    val title: String,
    val thumbnail: String,
    val duration: Int,
    val uploader: String,
    val formats: List<Format>
)

data class Format(
    val format_id: String,
    val type: String,       // "video" | "audio"
    val quality: Int,
    val ext: String,
    val label: String,
    val filesize: Long?
) {
    fun filesizeLabel(): String {
        val bytes = filesize ?: return ""
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576    -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024        -> "%.0f KB".format(bytes / 1_024.0)
            else                  -> "$bytes B"
        }
    }
}

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceUrl: String,
    val title: String,
    val format: Format,
    var status: DownloadStatus = DownloadStatus.QUEUED,
    var progress: Int = 0,
    var downloadId: Long = -1L,
    var errorMsg: String = ""
)

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED }
