package com.loader.app

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ApiClient {

    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Fetch video metadata + available formats. Throws on failure. */
    fun getInfo(videoUrl: String, baseUrl: String): VideoInfo {
        val apiUrl = "$baseUrl/info?url=${videoUrl.urlEncode()}"
        val request = Request.Builder().url(apiUrl).build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val msg = runCatching {
                    gson.fromJson(body, ErrorBody::class.java).detail
                }.getOrNull() ?: "HTTP ${response.code}"
                throw RuntimeException(msg)
            }
            return gson.fromJson(body, VideoInfo::class.java)
        }
    }

    /** Construct the download URL served by the backend. */
    fun buildDownloadUrl(videoUrl: String, formatId: String, baseUrl: String): String =
        "$baseUrl/download?url=${videoUrl.urlEncode()}&format_id=${formatId.urlEncode()}"

    private fun String.urlEncode() =
        java.net.URLEncoder.encode(this, "UTF-8")

    private data class ErrorBody(val detail: String)
}
