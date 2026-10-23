package org.jellyfin.mobile.downloads.smart

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_OFFLINE_SUBTITLE_BYTES = 4L * 1024 * 1024
private const val SUBTITLE_BUFFER_SIZE = 8 * 1024
private const val SUBTITLE_PREFIX_BYTES = 16
private const val WEBVTT_SIGNATURE = "WEBVTT"

internal class OfflineSubtitlesPending : IOException("Offline subtitles are incomplete")

/** Always builds the URL on the verified media endpoint; server-supplied delivery URLs are never followed. */
internal class SmartOfflineSubtitleTransfer {
    suspend fun download(
        request: SmartOfflineSubtitleRequest,
        index: Int,
        partial: File,
        budget: Long,
        permitted: suspend () -> Boolean,
    ): Long {
        checkPermitted(permitted)
        val source = requireNotNull(request.entry.item.mediaSources?.firstOrNull()?.id)
        val url = request.endpoint.url.newBuilder().addPathSegment("Videos")
            .addPathSegment(request.entry.item.id.toString()).addPathSegment(source)
            .addPathSegment("Subtitles").addPathSegment(index.toString()).addPathSegment("Stream.vtt")
            .addQueryParameter("copyTimestamps", "true").build()
        val api = request.api
        val http = Request.Builder().url(url).header("Accept-Encoding", "identity").header(
            "Authorization",
            AuthorizationHeaderBuilder.buildHeader(
                clientName = api.clientInfo.name,
                clientVersion = api.clientInfo.version,
                deviceId = api.deviceInfo.id,
                deviceName = api.deviceInfo.name,
                accessToken = api.accessToken,
            ),
        ).build()
        val client = request.endpoint.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var complete = false
        try {
            client.newCall(http).execute().use { response ->
                val body = subtitleBody(response)
                val limit = minOf(MAX_OFFLINE_SUBTITLE_BYTES, budget)
                if (body.contentLength() > limit) throw StorageBudgetExceeded()
                val bytes = copy(body.byteStream(), partial, limit, permitted)
                if (body.contentLength() >= 0 && bytes != body.contentLength()) throw IOException("Partial subtitle")
                checkPermitted(permitted)
                requireWebVtt(partial)
                complete = true
                return bytes
            }
        } finally {
            // Small text files restart; an interrupted response is never registered or used by the player.
            if (!complete) partial.delete()
        }
    }

    private fun subtitleBody(response: Response): ResponseBody {
        if (!response.isSuccessful) throw DownloadRejected(response.code)
        return response.body ?: throw IOException("Missing subtitle body")
    }

    private suspend fun copy(
        input: InputStream,
        target: File,
        limit: Long,
        permitted: suspend () -> Boolean,
    ): Long = input.use { stream ->
        FileOutputStream(target).use { output ->
            val written = copyBody(stream, output, requireNotNull(target.parentFile), limit, permitted)
            output.fd.sync()
            written
        }
    }

    private suspend fun copyBody(
        input: InputStream,
        output: FileOutputStream,
        directory: File,
        limit: Long,
        permitted: suspend () -> Boolean,
    ): Long {
        val buffer = ByteArray(SUBTITLE_BUFFER_SIZE)
        var written = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            checkPermitted(permitted)
            val read = input.read(buffer)
            if (read < 0) break
            val free = SmartOfflineDiskSpace.available(directory)
            if (written > limit - read || free < SmartOfflinePolicy.FREE_SPACE_RESERVE + read) {
                throw StorageBudgetExceeded()
            }
            output.write(buffer, 0, read)
            written += read
        }
        return written
    }

    private fun requireWebVtt(file: File) {
        val prefix = file.inputStream().use { input ->
            ByteArray(SUBTITLE_PREFIX_BYTES).let { bytes ->
                val count = input.read(bytes)
                if (count < 0) "" else String(bytes, 0, count, Charsets.UTF_8)
            }
        }.removePrefix("\uFEFF")
        if (!prefix.startsWith(WEBVTT_SIGNATURE) || prefix.getOrNull(WEBVTT_SIGNATURE.length)?.isWhitespace() != true) {
            throw IOException("Invalid subtitle format")
        }
    }

    private suspend fun checkPermitted(permitted: suspend () -> Boolean) {
        if (!permitted()) throw IOException("Subtitle transfer paused by policy")
    }
}
