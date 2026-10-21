package org.jellyfin.mobile.downloads.smart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class DownloadRejected(val status: Int) : IOException("Download HTTP status $status")
class StorageBudgetExceeded : IOException("Smart download storage limit reached")

class SmartOfflineTransfer {
    /** A strong ETag and If-Range prevent appending bytes from a changed server file. */
    suspend fun download(
        endpoint: VerifiedDownloadEndpoint,
        api: ApiClient,
        managed: SmartOfflineManaged,
        file: File,
        budget: Long,
        permitted: suspend () -> Boolean,
        saveValidator: suspend (String?) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        if (!permitted()) throw IOException("Network policy changed")
        val offset = if (managed.etag != null && !managed.etag.startsWith("W/")) file.length() else 0L
        val url = endpoint.url.newBuilder().addPathSegments("Items/${managed.item.id}/Download").build()
        val request = Request.Builder().url(url).header(
            "Authorization",
            AuthorizationHeaderBuilder.buildHeader(
                clientName = api.clientInfo.name,
                clientVersion = api.clientInfo.version,
                deviceId = api.deviceInfo.id,
                deviceName = api.deviceInfo.name,
                accessToken = api.accessToken,
            ),
        ).header("Accept-Encoding", "identity").apply {
            if (offset > 0) {
                header("Range", "bytes=$offset-")
                header("If-Range", managed.etag!!)
            }
        }.build()
        endpoint.client.newCall(request).execute().use { response ->
            if (response.code == 416 && offset > 0 && response.header("ETag") == managed.etag &&
                response.header("Content-Range") == "bytes */$offset"
            ) {
                if (offset > budget) throw StorageBudgetExceeded()
                return@withContext offset
            }
            if (!response.isSuccessful) throw DownloadRejected(response.code)
            val range = response.header("Content-Range")?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }
            val start = if (response.code == 206) range?.groupValues?.get(1)?.toLongOrNull() else 0L
            val total = if (response.code == 206) {
                range?.groupValues?.get(
                    3
                )?.toLongOrNull()
            } else {
                response.body?.contentLength()
            }
            if (start == null || total == null || total <= 0 || (response.code == 206 && start != offset)) {
                saveValidator(null)
                throw IOException("Invalid download range or length")
            }
            if (response.code == 206 && response.header("ETag") != managed.etag) {
                saveValidator(null)
                throw IOException("Download validator changed")
            }
            if (response.header("Content-Encoding")?.let { it != "identity" } == true) {
                throw IOException(
                    "Unexpected content encoding"
                )
            }
            if (total > budget || total - start > file.parentFile!!.usableSpace - SmartOfflinePolicy.FREE_SPACE_RESERVE) {
                throw StorageBudgetExceeded()
            }
            val validator = response.header("ETag")?.takeIf { !it.startsWith("W/") }
            RandomAccessFile(file, "rw").use { output ->
                if (start == 0L) output.setLength(0)
                // Persist only after truncating: a process death cannot associate a new validator with old bytes.
                saveValidator(validator)
                output.seek(start)
                var written = start
                val input = response.body?.byteStream() ?: throw IOException("Missing download body")
                input.use {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        if (!permitted()) throw IOException("Download paused by network policy")
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (written > total - read || written > budget - read ||
                            file.parentFile!!.usableSpace < SmartOfflinePolicy.FREE_SPACE_RESERVE + read
                        ) {
                            throw StorageBudgetExceeded()
                        }
                        output.write(buffer, 0, read)
                        written += read
                    }
                }
                output.fd.sync()
                if (written != total) throw IOException("Interrupted download")
                output.setLength(total)
                total
            }
        }
    }
}
